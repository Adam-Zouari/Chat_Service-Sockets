package chat.server;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.CRC32;

/** Dependency-free persistence tests, including restart and interrupted writes. */
public final class ChatStoreTest {
    private static int passed;

    private ChatStoreTest() { }

    public static void main(String[] args) throws Exception {
        if (args.length == 2 && args[0].equals("--crash-writer")) {
            ChatStore store = new ChatStore(Path.of(args[1]));
            store.saveAccount("CrashUser", new byte[16], new byte[32]);
            store.saveSession("CrashUser", new byte[32], 123456789L);
            store.appendMessage("Message committed before abrupt process exit");
            // Deliberately skip close(), finally blocks, and shutdown hooks.
            Runtime.getRuntime().halt(0);
        }
        run("accounts and UTF-8 history survive reopening", ChatStoreTest::reopen);
        run("acknowledged writes survive abrupt process exit", ChatStoreTest::abruptExit);
        run("snapshots and credentials cannot mutate stored state", ChatStoreTest::immutableSnapshots);
        run("history compacts and retains the latest 500 messages", ChatStoreTest::boundedHistory);
        run("an interrupted trailing history record is recovered", ChatStoreTest::incompleteTail);
        run("checksums reject damaged accounts and history", ChatStoreTest::corruption);
        run("one server owns each data directory", ChatStoreTest::exclusiveLock);
        run("failed account writes preserve committed data", ChatStoreTest::failedWrite);
        run("resume tokens survive replacement and reopening", ChatStoreTest::sessionReopen);
        run("only the token owner can durably revoke a session", ChatStoreTest::sessionOwnership);
        run("existing stores work without a session snapshot", ChatStoreTest::oldStore);
        run("invalid session snapshots fail closed", ChatStoreTest::sessionCorruption);
        run("failed session writes preserve committed records", ChatStoreTest::failedSessionWrite);
        run("invalid session writes leave stored records unchanged", ChatStoreTest::sessionValidation);
        System.out.println("Passed " + passed + " persistence tests.");
    }

    private static void run(String name, CheckedRunnable test) throws Exception {
        test.run();
        passed++;
        System.out.println("PASS " + name);
    }

    private static void reopen() throws Exception {
        withDirectory(directory -> {
            byte[] salt = new byte[16];
            byte[] hash = new byte[32];
            Arrays.fill(salt, (byte) 12);
            Arrays.fill(hash, (byte) 49);
            try (ChatStore store = new ChatStore(directory)) {
                store.saveAccount("Alice", salt, hash);
                store.appendMessage("Alice: Hello, أهلا 🌍");
            }
            try (ChatStore store = new ChatStore(directory)) {
                ChatStore.Snapshot snapshot = store.load();
                check(snapshot.accounts().size() == 1, "Account did not survive restart");
                check(Arrays.equals(snapshot.accounts().get("Alice").salt(), salt), "Salt changed");
                check(Arrays.equals(snapshot.accounts().get("Alice").hash(), hash), "Hash changed");
                check(snapshot.history().equals(List.of("Alice: Hello, أهلا 🌍")), "UTF-8 history changed");
                store.saveAccount("Bob", new byte[16], new byte[32]);
                store.appendMessage("Bob: Still working");
            }
            try (ChatStore store = new ChatStore(directory)) {
                check(store.load().accounts().size() == 2, "Second account did not survive restart");
                check(store.load().history().size() == 2, "Second message overwrote the first");
            }
        });
    }

    private static void immutableSnapshots() throws Exception {
        withDirectory(directory -> {
            try (ChatStore store = new ChatStore(directory)) {
                byte[] salt = new byte[16];
                byte[] tokenHash = new byte[32];
                store.saveAccount("Alice", salt, new byte[32]);
                store.saveSession("Alice", tokenHash, 123L);
                ChatStore.Snapshot original = store.load();
                salt[0] = 42;
                tokenHash[0] = 42;
                original.accounts().get("Alice").salt()[0] = 99;
                original.sessions().get("Alice").tokenHash()[0] = 99;
                check(store.load().accounts().get("Alice").salt()[0] == 0, "Credential was mutated");
                check(store.load().sessions().get("Alice").tokenHash()[0] == 0, "Session hash was mutated");
                expect(UnsupportedOperationException.class, () -> original.accounts().clear());
                expect(UnsupportedOperationException.class, () -> original.sessions().clear());
                expect(UnsupportedOperationException.class, () -> original.history().add("Oops"));
                store.saveAccount("Bob", new byte[16], new byte[32]);
                store.saveSession("Bob", new byte[32], 456L);
                store.appendMessage("One message");
                check(original.accounts().size() == 1 && original.history().isEmpty(), "Snapshot changed");
                check(original.sessions().size() == 1
                        && original.sessions().get("Alice").expiresAtMillis() == 123L,
                        "Session snapshot changed");
                expect(IllegalArgumentException.class,
                        () -> store.saveAccount("Alice", new byte[16], new byte[32]));
            }
        });
    }

    private static void abruptExit() throws Exception {
        withDirectory(directory -> {
            String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
            Path javaExecutable = Path.of(System.getProperty("java.home"), "bin", executable);
            Process writer = new ProcessBuilder(javaExecutable.toString(), "-cp", System.getProperty("java.class.path"),
                    ChatStoreTest.class.getName(), "--crash-writer", directory.toString()).redirectErrorStream(true).start();
            try {
                check(writer.waitFor(10, TimeUnit.SECONDS), "Crash writer did not finish");
                check(writer.exitValue() == 0, "Crash writer failed: "
                        + new String(writer.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
                try (ChatStore store = new ChatStore(directory)) {
                    check(store.load().accounts().containsKey("CrashUser"), "Committed account was lost");
                    check(store.load().sessions().get("CrashUser").expiresAtMillis() == 123456789L,
                            "Committed session was lost");
                    check(store.load().history().equals(List.of("Message committed before abrupt process exit")),
                            "Committed message was lost");
                }
            } finally {
                if (writer.isAlive()) {
                    writer.destroyForcibly();
                    writer.waitFor(10, TimeUnit.SECONDS);
                }
            }
        });
    }

    private static void boundedHistory() throws Exception {
        withDirectory(directory -> {
            try (ChatStore store = new ChatStore(directory)) {
                for (int i = 0; i < 1600; i++) {
                    store.appendMessage("message " + i);
                }
            }
            check(Files.size(directory.resolve("messages.bin")) < 50_000, "Journal was not compacted");
            try (ChatStore store = new ChatStore(directory)) {
                List<String> history = store.load().history();
                check(history.size() == 500, "History size is not bounded");
                for (int i = 0; i < 500; i++) {
                    check(history.get(i).equals("message " + (1100 + i)), "History order changed");
                }
            }
        });
    }

    private static void incompleteTail() throws Exception {
        withDirectory(directory -> {
            Path messages = directory.resolve("messages.bin");
            try (ChatStore store = new ChatStore(directory)) {
                store.appendMessage("first");
                store.appendMessage("interrupted");
            }
            try (FileChannel file = FileChannel.open(messages, StandardOpenOption.WRITE)) {
                file.truncate(file.size() - 3);
            }
            try (ChatStore store = new ChatStore(directory)) {
                check(store.load().history().equals(List.of("first")), "Partial record was replayed");
                store.appendMessage("recovered");
            }
            Files.write(messages, ByteBuffer.allocate(4).putInt(12).array(), StandardOpenOption.APPEND);
            try (ChatStore store = new ChatStore(directory)) {
                check(store.load().history().equals(List.of("first", "recovered")),
                        "Partial framing prevented recovery");
                store.appendMessage("third");
            }
            try (ChatStore store = new ChatStore(directory)) {
                check(store.load().history().equals(List.of("first", "recovered", "third")),
                        "Recovery did not leave a usable journal");
            }
        });
    }

    private static void corruption() throws Exception {
        withDirectory(directory -> {
            try (ChatStore store = new ChatStore(directory)) {
                store.saveAccount("Alice", new byte[16], new byte[32]);
                store.appendMessage("test message");
            }
            Path accounts = directory.resolve("accounts.bin");
            byte[] original = Files.readAllBytes(accounts);
            byte[] damaged = original.clone();
            damaged[damaged.length - 10] ^= 1;
            Files.write(accounts, damaged);
            expect(IOException.class, () -> new ChatStore(directory));
            check(Arrays.equals(Files.readAllBytes(accounts), damaged), "Corrupt accounts were reset");
            Files.write(accounts, original);
            Path messages = directory.resolve("messages.bin");
            byte[] history = Files.readAllBytes(messages);
            history[history.length - 1] ^= 1;
            Files.write(messages, history);
            expect(IOException.class, () -> new ChatStore(directory));
            check(Arrays.equals(Files.readAllBytes(messages), history), "Corrupt history was reset");
        });
    }

    private static void exclusiveLock() throws Exception {
        withDirectory(directory -> {
            ChatStore first = new ChatStore(directory);
            try {
                expect(IOException.class, () -> new ChatStore(directory));
            } finally {
                first.close();
                first.close();
            }
            expect(IOException.class, first::load);
            try (ChatStore second = new ChatStore(directory)) {
                check(second.load().accounts().isEmpty(), "Lock was not released");
            }
        });
    }

    private static void failedWrite() throws Exception {
        withDirectory(directory -> {
            Path blocker = directory.resolve("accounts.bin.tmp");
            try (ChatStore store = new ChatStore(directory)) {
                store.saveAccount("Alice", new byte[16], new byte[32]);
                Files.createDirectory(blocker);
                expect(IOException.class, () -> store.saveAccount("Bob", new byte[16], new byte[32]));
                expect(IOException.class, store::load);
            }
            Files.delete(blocker);
            try (ChatStore store = new ChatStore(directory)) {
                check(store.load().accounts().size() == 1
                        && store.load().accounts().containsKey("Alice"), "Committed account was lost");
                store.saveAccount("Bob", new byte[16], new byte[32]);
            }
        });
    }

    private static void sessionReopen() throws Exception {
        withDirectory(directory -> {
            byte[] tokenHash = new byte[32];
            Arrays.fill(tokenHash, (byte) 87);
            try (ChatStore store = new ChatStore(directory)) {
                store.saveAccount("Alice", new byte[16], new byte[32]);
                store.saveSession("Alice", new byte[32], 123L);
                store.saveSession("Alice", tokenHash, 456L);
            }
            try (ChatStore store = new ChatStore(directory)) {
                ChatStore.SessionCredential session = store.load().sessions().get("Alice");
                check(store.load().sessions().size() == 1, "Replacing a session added another record");
                check(Arrays.equals(session.tokenHash(), tokenHash), "Session hash changed after reopening");
                check(session.expiresAtMillis() == 456L, "Session expiration changed after reopening");
            }
        });
    }

    private static void sessionOwnership() throws Exception {
        withDirectory(directory -> {
            byte[] current = new byte[32];
            current[0] = 1;
            try (ChatStore store = new ChatStore(directory)) {
                store.saveAccount("Alice", new byte[16], new byte[32]);
                store.saveSession("Alice", new byte[32], 123L);
                ChatStore.Snapshot previous = store.load();
                store.saveSession("Alice", current, 456L);
                byte[] committed = Files.readAllBytes(directory.resolve("sessions.bin"));
                check(!store.removeSession("Alice", new byte[32]), "Stale token revoked its replacement");
                check(!store.removeSession("Unknown", current), "Nonexistent session was removed");
                check(Arrays.equals(committed, Files.readAllBytes(directory.resolve("sessions.bin"))),
                        "Failed ownership check rewrote the snapshot");
                check(store.removeSession("Alice", current), "Matching token could not revoke itself");
                check(!store.removeSession("Alice", current), "Token was removed twice");
                check(store.load().sessions().isEmpty(), "Revoked token remained in memory");
                check(previous.sessions().containsKey("Alice"), "Revocation changed an older snapshot");
            }
            try (ChatStore store = new ChatStore(directory)) {
                check(store.load().sessions().isEmpty(), "Revoked token returned after reopening");
            }
        });
    }

    private static void oldStore() throws Exception {
        withDirectory(directory -> {
            try (ChatStore store = new ChatStore(directory)) {
                store.saveAccount("Alice", new byte[16], new byte[32]);
                store.appendMessage("Existing history");
            }
            check(Files.notExists(directory.resolve("sessions.bin")), "Unused sessions created a snapshot");
            byte[] accounts = Files.readAllBytes(directory.resolve("accounts.bin"));
            byte[] history = Files.readAllBytes(directory.resolve("messages.bin"));
            try (ChatStore store = new ChatStore(directory)) {
                check(store.load().sessions().isEmpty(), "Missing session file did not start empty");
                check(store.load().accounts().containsKey("Alice"), "Old account format was not loaded");
                check(store.load().history().equals(List.of("Existing history")), "Old history was not loaded");
                store.saveSession("Alice", new byte[32], 123L);
            }
            check(Arrays.equals(accounts, Files.readAllBytes(directory.resolve("accounts.bin"))),
                    "Adding sessions changed the account format");
            check(Arrays.equals(history, Files.readAllBytes(directory.resolve("messages.bin"))),
                    "Adding sessions changed the history format");
        });
    }

    private static void sessionCorruption() throws Exception {
        withDirectory(directory -> {
            Path path = directory.resolve("sessions.bin");
            try (ChatStore store = new ChatStore(directory)) {
                store.saveAccount("Alice", new byte[16], new byte[32]);
                store.saveAccount("Bob", new byte[16], new byte[32]);
                store.saveSession("Alice", new byte[32], 123L);
            }
            byte[] valid = Files.readAllBytes(path);
            byte[] checksum = valid.clone();
            checksum[checksum.length - 1] ^= 1;
            byte[] magic = valid.clone();
            magic[0] ^= 1;
            byte[] version = valid.clone();
            ByteBuffer.wrap(version).putInt(4, 2);
            byte[] count = valid.clone();
            ByteBuffer.wrap(count).putInt(8, 10001);
            byte[] negativeCount = valid.clone();
            ByteBuffer.wrap(negativeCount).putInt(8, -1);
            byte[] shortenedHash = new byte[valid.length - 1];
            System.arraycopy(valid, 0, shortenedHash, 0, 25);
            System.arraycopy(valid, 26, shortenedHash, 25, valid.length - 26);
            byte[][] corruptions = {checksum, magic, version, count, negativeCount,
                    Arrays.copyOf(valid, valid.length - 1), Arrays.copyOf(valid, valid.length + 1),
                    withChecksum(shortenedHash), new byte[19], new byte[760021],
                    sessionSnapshot(new String[] {"Ghost"}, new long[] {123L}),
                    sessionSnapshot(new String[] {"Alice"}, new long[] {0L}),
                    sessionSnapshot(new String[] {"Alice"}, new long[] {-1L}),
                    sessionSnapshot(new String[] {"Alice", "Alice"}, new long[] {123L, 456L})};
            for (byte[] corrupted : corruptions) {
                Files.write(path, corrupted);
                expect(IOException.class, () -> new ChatStore(directory));
                check(Arrays.equals(corrupted, Files.readAllBytes(path)), "Corrupt sessions were reset");
            }
            Files.write(path, valid);
            try (ChatStore store = new ChatStore(directory)) {
                check(store.load().sessions().size() == 1, "Failed loading did not release the store lock");
            }
        });
    }

    private static byte[] sessionSnapshot(String[] usernames, long[] expirations) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(0x43485353);
            output.writeInt(1);
            output.writeInt(usernames.length);
            for (int i = 0; i < usernames.length; i++) {
                byte[] username = usernames[i].getBytes(java.nio.charset.StandardCharsets.UTF_8);
                output.writeInt(username.length);
                output.write(username);
                output.write(new byte[32]);
                output.writeLong(expirations[i]);
            }
            output.writeLong(0L);
        }
        return withChecksum(bytes.toByteArray());
    }

    private static byte[] withChecksum(byte[] bytes) {
        CRC32 checksum = new CRC32();
        checksum.update(bytes, 0, bytes.length - Long.BYTES);
        ByteBuffer.wrap(bytes).putLong(bytes.length - Long.BYTES, checksum.getValue());
        return bytes;
    }

    private static void failedSessionWrite() throws Exception {
        for (boolean removal : new boolean[] {false, true}) {
            withDirectory(directory -> {
                Path blocker = directory.resolve("sessions.bin.tmp");
                byte[] committed;
                try (ChatStore store = new ChatStore(directory)) {
                    store.saveAccount("Alice", new byte[16], new byte[32]);
                    store.saveSession("Alice", new byte[32], 123L);
                    committed = Files.readAllBytes(directory.resolve("sessions.bin"));
                    Files.createDirectory(blocker);
                    if (removal) {
                        expect(IOException.class, () -> store.removeSession("Alice", new byte[32]));
                    } else {
                        expect(IOException.class, () -> store.saveSession("Alice", new byte[32], 456L));
                    }
                    check(Arrays.equals(committed, Files.readAllBytes(directory.resolve("sessions.bin"))),
                            "Failed session write changed committed snapshot");
                    expect(IOException.class, store::load);
                }
                Files.delete(blocker);
                try (ChatStore store = new ChatStore(directory)) {
                    check(store.load().sessions().get("Alice").expiresAtMillis() == 123L,
                            "Failed session write lost the previously committed record");
                    check(store.removeSession("Alice", new byte[32]), "Restored store could not revoke token");
                }
            });
        }
    }

    private static void sessionValidation() throws Exception {
        withDirectory(directory -> {
            try (ChatStore store = new ChatStore(directory)) {
                store.saveAccount("Alice", new byte[16], new byte[32]);
                expect(IllegalArgumentException.class, () -> store.saveSession("Ghost", new byte[32], 123L));
                expect(IllegalArgumentException.class, () -> store.saveSession(null, new byte[32], 123L));
                expect(IllegalArgumentException.class, () -> store.saveSession("Alice", null, 123L));
                expect(IllegalArgumentException.class, () -> store.saveSession("Alice", new byte[31], 123L));
                expect(IllegalArgumentException.class, () -> store.saveSession("Alice", new byte[33], 123L));
                expect(IllegalArgumentException.class, () -> store.saveSession("Alice", new byte[32], 0L));
                expect(IllegalArgumentException.class, () -> store.saveSession("Alice", new byte[32], -1L));
                expect(IllegalArgumentException.class, () -> store.removeSession("Alice", null));
                expect(IllegalArgumentException.class, () -> store.removeSession("Alice", new byte[31]));
                check(store.load().sessions().isEmpty(), "Invalid session request changed the store");
                store.saveSession("Alice", new byte[32], 123L);
                check(store.load().sessions().size() == 1, "Validation failure disabled a healthy store");
            }
        });
    }

    private static void withDirectory(DirectoryTest test) throws Exception {
        Path directory = Files.createTempDirectory("chat-store-test-").toAbsolutePath().normalize();
        try {
            test.run(directory);
        } finally {
            try (Stream<Path> paths = Files.walk(directory)) {
                for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) {
                    if (!path.toAbsolutePath().normalize().startsWith(directory)) {
                        throw new IOException("Cleanup escaped the temporary test directory.");
                    }
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private static void expect(Class<? extends Throwable> type, CheckedRunnable task) throws Exception {
        try {
            task.run();
        } catch (Throwable error) {
            if (type.isInstance(error)) {
                return;
            }
            throw error;
        }
        throw new AssertionError("Expected " + type.getSimpleName());
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    @FunctionalInterface
    private interface CheckedRunnable { void run() throws Exception; }

    @FunctionalInterface
    private interface DirectoryTest { void run(Path directory) throws Exception; }
}
