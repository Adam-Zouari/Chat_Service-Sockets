package chat.server;

import chat.protocol.ChatProtocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;

/** Small, exclusive, checksummed local store. Passwords are never stored here. */
public final class ChatStore implements AutoCloseable {
    private static final int ACCOUNTS_MAGIC = 0x43484143;
    private static final int HISTORY_MAGIC = 0x43484D53;
    private static final int SESSIONS_MAGIC = 0x43485353;
    private static final int VERSION = 1;
    private static final int HISTORY_LIMIT = 500;
    private static final int COMPACT_AFTER = 1000;
    private static final int MAX_ACCOUNTS = 10_000;
    private static final int MAX_ACCOUNTS_BYTES = 2_000_000;
    private static final int MAX_SESSIONS_BYTES = 20 + MAX_ACCOUNTS * (4 + 32 + 32 + 8);
    private static final int MAX_MESSAGE_BYTES = 16_384;

    private final Path directory;
    private final FileChannel lockChannel;
    private final FileLock lock;
    private final Map<String, Credential> accounts = new LinkedHashMap<>();
    private final Map<String, SessionCredential> sessions = new LinkedHashMap<>();
    private final ArrayDeque<String> history = new ArrayDeque<>();
    private FileChannel journal;
    private int journalRecords;
    private boolean closed;
    private boolean failed;

    public ChatStore(Path directory) throws IOException {
        if (directory == null) {
            throw new IllegalArgumentException("A storage directory is required.");
        }
        this.directory = directory.toAbsolutePath().normalize();
        Files.createDirectories(this.directory);
        lockChannel = FileChannel.open(this.directory.resolve("store.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock acquired = null;
        try {
            try {
                acquired = lockChannel.tryLock();
            } catch (OverlappingFileLockException e) {
                throw new IOException("Another server is already using " + this.directory, e);
            }
            if (acquired == null) {
                throw new IOException("Another server is already using " + this.directory);
            }
            lock = acquired;
            readAccounts();
            readSessions();
            readHistory();
        } catch (IOException | RuntimeException e) {
            if (journal != null) {
                try { journal.close(); } catch (IOException cleanup) { e.addSuppressed(cleanup); }
            }
            if (acquired != null) {
                try { acquired.release(); } catch (IOException cleanup) { e.addSuppressed(cleanup); }
            }
            try { lockChannel.close(); } catch (IOException cleanup) { e.addSuppressed(cleanup); }
            throw e;
        }
    }

    public synchronized Snapshot load() throws IOException {
        ensureOpen();
        return new Snapshot(accounts, sessions, new ArrayList<>(history));
    }

    /** Replaces this account's resume-token hash only after its snapshot is durable. */
    public synchronized void saveSession(String username, byte[] tokenHash, long expiresAtMillis)
            throws IOException {
        ensureOpen();
        if (!accounts.containsKey(username) || tokenHash == null || tokenHash.length != 32
                || expiresAtMillis <= 0) {
            throw new IllegalArgumentException("Invalid stored session.");
        }
        Map<String, SessionCredential> updated = new LinkedHashMap<>(sessions);
        updated.put(username, new SessionCredential(tokenHash, expiresAtMillis));
        try {
            writeSessions(updated);
            sessions.put(username, updated.get(username));
        } catch (IOException e) {
            failed = true;
            throw e;
        }
    }

    /** A stale connection cannot revoke a token issued to a newer connection. */
    public synchronized boolean removeSession(String username, byte[] expectedHash) throws IOException {
        ensureOpen();
        if (expectedHash == null || expectedHash.length != 32) {
            throw new IllegalArgumentException("Invalid session hash.");
        }
        SessionCredential existing = sessions.get(username);
        if (existing == null || !MessageDigest.isEqual(existing.tokenHash, expectedHash)) {
            return false;
        }
        Map<String, SessionCredential> updated = new LinkedHashMap<>(sessions);
        updated.remove(username);
        try {
            writeSessions(updated);
            sessions.remove(username);
            return true;
        } catch (IOException e) {
            failed = true;
            throw e;
        }
    }

    private void writeSessions(Map<String, SessionCredential> updated) throws IOException {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(payload)) {
            output.writeInt(SESSIONS_MAGIC);
            output.writeInt(VERSION);
            output.writeInt(updated.size());
            for (Map.Entry<String, SessionCredential> entry : updated.entrySet()) {
                byte[] name = entry.getKey().getBytes(StandardCharsets.UTF_8);
                output.writeInt(name.length);
                output.write(name);
                output.write(entry.getValue().tokenHash);
                output.writeLong(entry.getValue().expiresAtMillis);
            }
            output.flush();
            CRC32 checksum = new CRC32();
            checksum.update(payload.toByteArray());
            output.writeLong(checksum.getValue());
        }
        replaceFile("sessions.bin", payload.toByteArray());
    }

    /** Returns only after the account snapshot has been forced to disk and replaced. */
    public synchronized void saveAccount(String username, byte[] salt, byte[] hash) throws IOException {
        ensureOpen();
        if (!validUsername(username) || salt == null || salt.length != 16 || hash == null || hash.length != 32) {
            throw new IllegalArgumentException("Invalid stored account.");
        }
        if (accounts.containsKey(username)) {
            throw new IllegalArgumentException("The account already exists.");
        }
        if (accounts.size() == MAX_ACCOUNTS) {
            throw new IOException("The account storage limit has been reached.");
        }
        Map<String, Credential> updated = new LinkedHashMap<>(accounts);
        updated.put(username, new Credential(salt, hash));
        try {
            ByteArrayOutputStream payload = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(payload)) {
                output.writeInt(ACCOUNTS_MAGIC);
                output.writeInt(VERSION);
                output.writeInt(updated.size());
                for (Map.Entry<String, Credential> entry : updated.entrySet()) {
                    byte[] name = entry.getKey().getBytes(StandardCharsets.UTF_8);
                    output.writeInt(name.length);
                    output.write(name);
                    output.write(entry.getValue().salt);
                    output.write(entry.getValue().hash);
                }
                output.flush();
                CRC32 checksum = new CRC32();
                checksum.update(payload.toByteArray());
                output.writeLong(checksum.getValue());
            }
            replaceFile("accounts.bin", payload.toByteArray());
            accounts.put(username, updated.get(username));
        } catch (IOException e) {
            failed = true;
            throw e;
        }
    }

    /** Appends a forced record; only the latest 500 messages are replayed. */
    public synchronized void appendMessage(String message) throws IOException {
        ensureOpen();
        byte[] bytes = encodeMessage(message);
        try {
            if (journalRecords >= COMPACT_AFTER) {
                compactHistory();
            }
            writeFully(journal, messageRecord(bytes));
            journal.force(true);
            journalRecords++;
            if (history.size() == HISTORY_LIMIT) {
                history.removeFirst();
            }
            history.addLast(message);
        } catch (IOException e) {
            failed = true;
            throw e;
        }
    }

    private void readAccounts() throws IOException {
        Path path = directory.resolve("accounts.bin");
        // An indeterminate existence check (for example, denied access) must fail closed.
        if (Files.notExists(path)) {
            return;
        }
        long size = Files.size(path);
        if (size < 20 || size > MAX_ACCOUNTS_BYTES) {
            throw corrupt(path, "invalid snapshot size");
        }
        byte[] bytes = Files.readAllBytes(path);
        CRC32 checksum = new CRC32();
        checksum.update(bytes, 0, bytes.length - Long.BYTES);
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (input.readInt() != ACCOUNTS_MAGIC || input.readInt() != VERSION) {
                throw corrupt(path, "unknown file format");
            }
            int count = input.readInt();
            if (count < 0 || count > MAX_ACCOUNTS) {
                throw corrupt(path, "invalid account count");
            }
            for (int i = 0; i < count; i++) {
                int length = input.readInt();
                if (length < 1 || length > 32) {
                    throw corrupt(path, "invalid username size");
                }
                byte[] name = new byte[length];
                input.readFully(name);
                String username = decode(name);
                byte[] salt = new byte[16];
                byte[] hash = new byte[32];
                input.readFully(salt);
                input.readFully(hash);
                if (!validUsername(username) || accounts.put(username, new Credential(salt, hash)) != null) {
                    throw corrupt(path, "invalid or repeated username");
                }
            }
            if (input.readLong() != checksum.getValue() || input.read() != -1) {
                throw corrupt(path, "checksum mismatch or unexpected trailing data");
            }
        } catch (EOFException | CharacterCodingException e) {
            throw corrupt(path, "incomplete or invalid snapshot", e);
        }
    }

    private void readHistory() throws IOException {
        Path path = directory.resolve("messages.bin");
        if (Files.notExists(path)) {
            replaceFile("messages.bin", historyHeader());
        }
        journal = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE);
        ByteBuffer header = ByteBuffer.allocate(8);
        if (!readFully(journal, header) || header.getInt(0) != HISTORY_MAGIC || header.getInt(4) != VERSION) {
            throw corrupt(path, "incomplete or unknown history header");
        }
        while (journal.position() < journal.size()) {
            long recordStart = journal.position();
            ByteBuffer framing = ByteBuffer.allocate(8);
            if (!readFully(journal, framing)) {
                truncateTail(recordStart);
                break;
            }
            int length = framing.getInt(0);
            if (length < 1 || length > MAX_MESSAGE_BYTES || framing.getInt(4) != ~length) {
                throw corrupt(path, "invalid history record framing");
            }
            ByteBuffer content = ByteBuffer.allocate(length + Integer.BYTES);
            if (!readFully(journal, content)) {
                truncateTail(recordStart);
                break;
            }
            byte[] bytes = new byte[length];
            content.flip();
            content.get(bytes);
            CRC32 checksum = new CRC32();
            checksum.update(bytes);
            if (content.getInt() != (int) checksum.getValue()) {
                throw corrupt(path, "history checksum mismatch");
            }
            String message;
            try {
                message = decode(bytes);
                encodeMessage(message);
            } catch (CharacterCodingException | IllegalArgumentException e) {
                throw corrupt(path, "invalid history message", e);
            }
            if (history.size() == HISTORY_LIMIT) {
                history.removeFirst();
            }
            history.addLast(message);
            journalRecords++;
            // A valid store never exceeds this bound, even immediately before compaction.
            if (journalRecords > COMPACT_AFTER) {
                throw corrupt(path, "history journal exceeds its record limit");
            }
        }
    }

    private void readSessions() throws IOException {
        Path path = directory.resolve("sessions.bin");
        // Older installations have no resume sessions yet.
        if (Files.notExists(path)) {
            return;
        }
        long size = Files.size(path);
        if (size < 20 || size > MAX_SESSIONS_BYTES) {
            throw corrupt(path, "invalid session snapshot size");
        }
        byte[] bytes = Files.readAllBytes(path);
        if (bytes.length != size) {
            throw corrupt(path, "session snapshot changed while loading");
        }
        CRC32 checksum = new CRC32();
        checksum.update(bytes, 0, bytes.length - Long.BYTES);
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (input.readInt() != SESSIONS_MAGIC || input.readInt() != VERSION) {
                throw corrupt(path, "unknown session file format");
            }
            int count = input.readInt();
            if (count < 0 || count > MAX_ACCOUNTS || count > accounts.size()
                    || size < 20L + count * 45L || size > 20L + count * 76L) {
                throw corrupt(path, "invalid session count or snapshot size");
            }
            for (int i = 0; i < count; i++) {
                int length = input.readInt();
                if (length < 1 || length > 32) {
                    throw corrupt(path, "invalid session username size");
                }
                byte[] name = new byte[length];
                input.readFully(name);
                String username = decode(name);
                byte[] tokenHash = new byte[32];
                input.readFully(tokenHash);
                long expiresAtMillis = input.readLong();
                if (!accounts.containsKey(username) || expiresAtMillis <= 0
                        || sessions.put(username, new SessionCredential(tokenHash, expiresAtMillis)) != null) {
                    throw corrupt(path, "invalid, unknown or repeated session account");
                }
            }
            if (input.readLong() != checksum.getValue() || input.read() != -1) {
                throw corrupt(path, "session checksum mismatch or unexpected trailing data");
            }
        } catch (EOFException | CharacterCodingException e) {
            throw corrupt(path, "incomplete or invalid session snapshot", e);
        }
    }

    private void truncateTail(long recordStart) throws IOException {
        journal.truncate(recordStart);
        journal.position(recordStart);
        journal.force(true);
    }

    private void compactHistory() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write(historyHeader());
        for (String message : history) {
            bytes.write(messageRecord(encodeMessage(message)).array());
        }
        // Windows requires the old handle to be closed before the file is replaced.
        journal.close();
        replaceFile("messages.bin", bytes.toByteArray());
        journal = FileChannel.open(directory.resolve("messages.bin"),
                StandardOpenOption.READ, StandardOpenOption.WRITE);
        journal.position(journal.size());
        journalRecords = history.size();
    }

    private void replaceFile(String name, byte[] bytes) throws IOException {
        Path temporary = directory.resolve(name + ".tmp");
        try (FileChannel output = FileChannel.open(temporary, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            writeFully(output, ByteBuffer.wrap(bytes));
            output.force(true);
        }
        try {
            Files.move(temporary, directory.resolve(name), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            // A non-atomic replacement could destroy the previous committed snapshot.
            throw new IOException("Chat storage requires atomic file replacement: " + directory, e);
        }
    }

    private static byte[] historyHeader() {
        return ByteBuffer.allocate(8).putInt(HISTORY_MAGIC).putInt(VERSION).array();
    }

    private static ByteBuffer messageRecord(byte[] bytes) {
        CRC32 checksum = new CRC32();
        checksum.update(bytes);
        ByteBuffer record = ByteBuffer.allocate(12 + bytes.length);
        record.putInt(bytes.length).putInt(~bytes.length).put(bytes).putInt((int) checksum.getValue());
        record.flip();
        return record;
    }

    private static byte[] encodeMessage(String message) {
        if (message == null || message.isEmpty() || message.length() > 4096
                || !ChatProtocol.isAllowedText(message)) {
            throw new IllegalArgumentException("Invalid stored message.");
        }
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_MESSAGE_BYTES) {
            throw new IllegalArgumentException("Stored message is too long.");
        }
        return bytes;
    }

    private static String decode(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }

    private static boolean validUsername(String username) {
        return username != null && username.matches("[A-Za-z][A-Za-z0-9_]{0,31}")
                && !username.equalsIgnoreCase("login") && !username.equalsIgnoreCase("signup");
    }

    private static boolean readFully(FileChannel channel, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) == -1) {
                return false;
            }
        }
        return true;
    }

    private static void writeFully(FileChannel channel, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
    }

    private static IOException corrupt(Path path, String reason) {
        return corrupt(path, reason, null);
    }

    private static IOException corrupt(Path path, String reason, Throwable cause) {
        return new IOException("Cannot load chat storage " + path + ": " + reason
                + ". Restore a backup or choose a new data directory; existing data was not reset.", cause);
    }

    private void ensureOpen() throws IOException {
        if (closed || failed) {
            throw new IOException(closed ? "Chat storage is closed."
                    : "Chat storage is unavailable after a write failure; restart the server after fixing storage.");
        }
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        IOException failure = null;
        if (journal != null) {
            try { journal.close(); } catch (IOException e) { failure = e; }
        }
        try { lock.release(); } catch (IOException e) {
            if (failure == null) { failure = e; } else { failure.addSuppressed(e); }
        }
        try { lockChannel.close(); } catch (IOException e) {
            if (failure == null) { failure = e; } else { failure.addSuppressed(e); }
        }
        if (failure != null) {
            throw failure;
        }
    }

    public static final class Credential {
        private final byte[] salt;
        private final byte[] hash;

        private Credential(byte[] salt, byte[] hash) {
            this.salt = salt.clone();
            this.hash = hash.clone();
        }

        public byte[] salt() { return salt.clone(); }
        public byte[] hash() { return hash.clone(); }
    }

    public static final class Snapshot {
        private final Map<String, Credential> accounts;
        private final Map<String, SessionCredential> sessions;
        private final List<String> history;

        private Snapshot(Map<String, Credential> accounts, Map<String, SessionCredential> sessions,
                List<String> history) {
            this.accounts = Collections.unmodifiableMap(new LinkedHashMap<>(accounts));
            this.sessions = Collections.unmodifiableMap(new LinkedHashMap<>(sessions));
            this.history = Collections.unmodifiableList(history);
        }

        public Map<String, Credential> accounts() { return accounts; }
        public Map<String, SessionCredential> sessions() { return sessions; }
        public List<String> history() { return history; }
    }

    public static final class SessionCredential {
        private final byte[] tokenHash;
        private final long expiresAtMillis;

        private SessionCredential(byte[] tokenHash, long expiresAtMillis) {
            this.tokenHash = tokenHash.clone();
            this.expiresAtMillis = expiresAtMillis;
        }

        public byte[] tokenHash() { return tokenHash.clone(); }
        public long expiresAtMillis() { return expiresAtMillis; }
    }
}
