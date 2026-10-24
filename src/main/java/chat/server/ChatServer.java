package chat.server;

import chat.protocol.ChatProtocol;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** A bounded chat room with optional file persistence. No external dependencies. */
public final class ChatServer implements AutoCloseable {
    private static final int MAX_CLIENTS = 64;
    private static final int MAX_ACCOUNTS = 10_000;
    private static final int HISTORY_LIMIT = 500;
    private static final int AUTH_TIMEOUT_SECONDS = 60;
    private static final long SESSION_LIFETIME_MILLIS = TimeUnit.DAYS.toMillis(7);
    private static final SecureRandom RANDOM = new SecureRandom();

    // Orders durable state changes, history replay, and live broadcasts.
    // Socket writes happen on sender threads, never under this lock.
    private final Object stateLock = new Object();
    private final Map<String, PasswordHash> accounts = new HashMap<>();
    private final Map<String, ClientHandler> clients = new HashMap<>();
    private final Map<String, ResumeSession> resumeSessions = new HashMap<>();
    private final ArrayDeque<ChatProtocol.ChatMessage> history = new ArrayDeque<>();
    private final Set<ClientHandler> connections = ConcurrentHashMap.newKeySet();
    private final Semaphore connectionSlots = new Semaphore(MAX_CLIENTS);
    private final ScheduledThreadPoolExecutor timeouts = createTimeoutExecutor();
    private final InetAddress bindAddress;
    private final int requestedPort;
    private volatile ServerSocket listener;
    private volatile boolean closed;
    private boolean started;
    private ChatStore store;

    private static ScheduledThreadPoolExecutor createTimeoutExecutor() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task, "chat-auth-timeouts");
            thread.setDaemon(true);
            return thread;
        });
        // Completed logins must not retain sockets and queues until their old deadline.
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    /** Creates an ephemeral server, useful for embedded rooms and isolated tests. */
    public ChatServer(InetAddress bindAddress, int port) {
        if (bindAddress == null || port < 0 || port > 65535) {
            throw new IllegalArgumentException("A bind address and port from 0 to 65535 are required.");
        }
        this.bindAddress = bindAddress;
        this.requestedPort = port;
    }

    /** Loads durable accounts and recent history before accepting connections. */
    public ChatServer(InetAddress bindAddress, int port, Path directory) throws IOException {
        this(bindAddress, port);
        store = new ChatStore(directory);
        try {
            ChatStore.Snapshot snapshot = store.load();
            snapshot.accounts().forEach((name, credential) -> accounts.put(name,
                    new PasswordHash(credential.salt(), credential.hash())));
            snapshot.sessions().forEach((name, session) -> resumeSessions.put(name,
                    new ResumeSession(session.tokenHash(), session.expiresAtMillis())));
            for (String stored : snapshot.history()) {
                history.addLast(ChatProtocol.parseStoredMessage(stored));
            }
        } catch (IOException | RuntimeException e) {
            try {
                store.close();
            } catch (IOException closeError) {
                e.addSuppressed(closeError);
            }
            throw e;
        }
    }

    public int getPort() {
        synchronized (stateLock) {
            return listener == null || !listener.isBound() ? -1 : listener.getLocalPort();
        }
    }

    /** Binds once and accepts clients until close() is called. */
    public void start() throws IOException {
        synchronized (stateLock) {
            if (started || closed) {
                throw new IllegalStateException("This server has already been started or closed.");
            }
            started = true;
            listener = new ServerSocket();
            try {
                listener.setReuseAddress(true);
                listener.bind(new InetSocketAddress(bindAddress, requestedPort));
            } catch (IOException e) {
                close();
                throw e;
            }
        }
        System.out.println("Chat server listening on " + bindAddress.getHostAddress() + ":" + getPort());
        try {
            while (!closed) {
                Socket socket = listener.accept();
                synchronized (stateLock) {
                    if (closed || !connectionSlots.tryAcquire()) {
                        socket.close();
                        continue;
                    }
                    ClientHandler handler = new ClientHandler(socket);
                    connections.add(handler);
                    new Thread(handler, "chat-reader-" + socket.getPort()).start();
                }
            }
        } catch (SocketException e) {
            if (!closed) {
                throw e;
            }
        } finally {
            close();
        }
    }

    @Override
    public void close() {
        List<ClientHandler> active;
        synchronized (stateLock) {
            if (closed) {
                return;
            }
            closed = true;
            if (listener != null) {
                try {
                    listener.close();
                } catch (IOException ignored) { }
            }
            active = new ArrayList<>(connections);
        }
        timeouts.shutdownNow();
        active.forEach(ClientHandler::close);
        if (store != null) {
            try {
                store.close();
            } catch (IOException e) {
                System.err.println("Cannot close chat storage: " + e.getMessage());
            }
        }
    }

    public static void main(String[] args) {
        if (args.length > 3) {
            System.err.println("Usage: java -cp build/classes/main chat.server.ChatServer [port] [bind-address] [data-directory]");
            return;
        }
        try {
            int port = args.length > 0 ? Integer.parseInt(args[0]) : ChatProtocol.PORT;
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException("Port must be from 1 to 65535.");
            }
            InetAddress address = InetAddress.getByName(args.length > 1 ? args[1] : "127.0.0.1");
            Path directory = Paths.get(args.length > 2 ? args[2] : "server-data");
            try (ChatServer server = new ChatServer(address, port, directory)) {
                System.out.println("Chat data: " + directory.toAbsolutePath().normalize());
                Thread shutdownHook = new Thread(server::close, "chat-shutdown");
                Runtime.getRuntime().addShutdownHook(shutdownHook);
                try {
                    server.start();
                } finally {
                    try {
                        Runtime.getRuntime().removeShutdownHook(shutdownHook);
                    } catch (IllegalStateException ignored) {
                        // Ctrl+C may already have started JVM shutdown.
                    }
                }
            }
        } catch (IOException | IllegalArgumentException e) {
            System.err.println("Cannot start server: " + e.getMessage());
        }
    }

    private static final class PasswordHash {
        private final byte[] salt;
        private final byte[] hash;

        private PasswordHash(String password) {
            salt = new byte[16];
            RANDOM.nextBytes(salt);
            hash = derive(password, salt);
        }

        private PasswordHash(byte[] salt, byte[] hash) {
            this.salt = salt.clone();
            this.hash = hash.clone();
        }

        private boolean matches(String password) {
            byte[] candidate = derive(password, salt);
            try {
                return MessageDigest.isEqual(hash, candidate);
            } finally {
                Arrays.fill(candidate, (byte) 0);
            }
        }

        private static byte[] derive(String password, byte[] salt) {
            char[] chars = password.toCharArray();
            PBEKeySpec spec = new PBEKeySpec(chars, salt, 210_000, 256);
            Arrays.fill(chars, '\0');
            try {
                return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException("Password hashing is unavailable.", e);
            } finally {
                spec.clearPassword();
            }
        }
    }

    private static final class ResumeSession {
        private final byte[] hash;
        private final long expiresAt;

        private ResumeSession(byte[] hash, long expiresAt) {
            this.hash = hash.clone();
            this.expiresAt = expiresAt;
        }
    }

    private static String newSessionToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    private static byte[] sessionTokenHash(String token) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Session hashing is unavailable.", e);
        }
    }

    private static boolean validUsername(String username) {
        return ChatProtocol.validUsername(username);
    }

    private static boolean validPassword(String password) {
        return ChatProtocol.validPassword(password);
    }

    private void disconnectSlowClients(List<ClientHandler> failed) {
        failed.forEach(ClientHandler::close);
    }

    /** Call with stateLock held; each client sees the same event order. */
    private void broadcastLocked(String message, ClientHandler except, List<ClientHandler> failed) {
        for (ClientHandler client : clients.values()) {
            if (client != except && !client.outgoing.offer(message)) {
                failed.add(client);
            }
        }
    }

    /** Full snapshots keep presence correct after joins, disconnects, and session takeovers. */
    private String onlineUsersLocked() {
        List<String> names = new ArrayList<>(clients.keySet());
        names.sort(String::compareTo);
        return names.isEmpty() ? "@users" : ChatProtocol.USERS_PREFIX + String.join("\t", names);
    }

    private final class ClientHandler implements Runnable {
        private final Socket socket;
        // Enough room for an entire replay plus authentication and presence messages.
        private final ArrayBlockingQueue<String> outgoing = new ArrayBlockingQueue<>(HISTORY_LIMIT + 64);
        private final AtomicBoolean disconnected = new AtomicBoolean();
        private final String endOfOutput = new String();
        private volatile String username;
        private volatile Thread sender;
        private BufferedReader reader;
        private boolean drainOutput;
        private int invalidInputs;
        private int badPasswords;
        private boolean resumableForms;
        private byte[] sessionHash;

        private ClientHandler(Socket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            ScheduledFuture<?> authenticationTimeout = null;
            try {
                if (disconnected.get()) {
                    return;
                }
                socket.setTcpNoDelay(true);
                reader = ChatProtocol.newUtf8Reader(socket.getInputStream());
                BufferedWriter writer = ChatProtocol.newUtf8Writer(socket.getOutputStream());
                sender = new Thread(() -> writeOutput(writer), "chat-writer-" + socket.getPort());
                sender.start();
                authenticationTimeout = timeouts.schedule(() -> {
                    if (username == null) {
                        close();
                    }
                }, AUTH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                send("Welcome to the chat server!");
                send(ChatProtocol.CHOICE_PROMPT);
                if (!authenticate()) {
                    return;
                }
                authenticationTimeout.cancel(false);
                String message;
                while (!disconnected.get() && (message = ChatProtocol.readLine(reader)) != null) {
                    if (message.equals(ChatProtocol.LOGOUT)) {
                        if (revokeSession()) {
                            send("Goodbye.");
                        } else {
                            send("Logout could not be confirmed: server storage is unavailable.");
                        }
                        drainOutput = true;
                        break;
                    }
                    if (message.equals(ChatProtocol.USERS_REQUEST)) {
                        synchronized (stateLock) {
                            if (clients.get(username) == this) {
                                send(onlineUsersLocked());
                            }
                        }
                        continue;
                    }
                    // Wrapping every desktop message keeps text such as @logout ordinary chat.
                    if (message.startsWith(ChatProtocol.SEND_PREFIX)) {
                        message = message.substring(ChatProtocol.SEND_PREFIX.length());
                    }
                    if (!ChatProtocol.isAllowedText(message) || message.length() > ChatProtocol.MAX_MESSAGE_LENGTH) {
                        send("Messages must contain at most " + ChatProtocol.MAX_MESSAGE_LENGTH
                                + " characters and no control characters.");
                    } else if (message.isBlank()) {
                        send("Please enter a message.");
                    } else {
                        List<ClientHandler> failed = new ArrayList<>();
                        synchronized (stateLock) {
                            if (closed || disconnected.get() || clients.get(username) != this) {
                                break;
                            }
                            ChatProtocol.ChatMessage entry = new ChatProtocol.ChatMessage(
                                    System.currentTimeMillis(), username, message);
                            if (store != null) {
                                try {
                                    store.appendMessage(ChatProtocol.encodeStoredMessage(entry));
                                } catch (IOException e) {
                                    System.err.println("Cannot save chat message: " + e.getMessage());
                                    send("Message delivery could not be confirmed: server storage is unavailable.");
                                    continue;
                                }
                            }
                            if (history.size() == HISTORY_LIMIT) {
                                history.removeFirst();
                            }
                            history.addLast(entry);
                            broadcastLocked(ChatProtocol.encodeMessage(entry), null, failed);
                        }
                        disconnectSlowClients(failed);
                    }
                }
            } catch (IOException e) {
                // Malformed input and peer disconnects only affect this connection.
            } catch (java.util.concurrent.RejectedExecutionException e) {
                // The server was closed while this connection was starting.
            } finally {
                if (authenticationTimeout != null) {
                    authenticationTimeout.cancel(false);
                }
                List<ClientHandler> failed = new ArrayList<>();
                synchronized (stateLock) {
                    if (username != null && clients.remove(username, this) && !closed) {
                        broadcastLocked(onlineUsersLocked(), null, failed);
                    }
                }
                disconnectSlowClients(failed);
                if (drainOutput && sender != null && outgoing.offer(endOfOutput)) {
                    try {
                        sender.join(1000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                close();
                connections.remove(this);
                connectionSlots.release();
            }
        }

        private boolean authenticate() throws IOException {
            String mode = null;
            String candidateName = null;
            while (!disconnected.get()) {
                String input = ChatProtocol.readLine(reader);
                if (input == null) {
                    return false;
                }
                if (input.equals(ChatProtocol.FORM_HELLO) || input.equals(ChatProtocol.FORM_SESSION_HELLO)) {
                    resumableForms = input.equals(ChatProtocol.FORM_SESSION_HELLO);
                    return authenticateForms();
                }
                if (!ChatProtocol.isAllowedText(input)) {
                    if (!invalid("Control characters are not allowed.")) {
                        return false;
                    }
                    send(mode == null ? ChatProtocol.CHOICE_PROMPT
                            : candidateName == null ? ChatProtocol.USERNAME_PROMPT : ChatProtocol.PASSWORD_PROMPT);
                    continue;
                }
                String command = input.trim().toLowerCase(Locale.ROOT);
                if ((mode == null || !command.equals(mode))
                        && (command.equals("login") || command.equals("signup"))) {
                    mode = command;
                    candidateName = null;
                    send(mode);
                    send(ChatProtocol.USERNAME_PROMPT);
                    continue;
                }
                if (mode == null) {
                    if (!invalid("Invalid choice. Type login or signup.")) {
                        return false;
                    }
                    send(ChatProtocol.CHOICE_PROMPT);
                } else if (candidateName == null) {
                    String error = null;
                    if (!validUsername(input)) {
                        error = "Use 1-32 letters, numbers or _, starting with a letter; login/signup are reserved.";
                    } else {
                        synchronized (stateLock) {
                            if (mode.equals("signup") && accounts.containsKey(input)) {
                                error = "Username already exists. Try again.";
                            } else if (mode.equals("signup") && accounts.size() >= MAX_ACCOUNTS) {
                                error = "Account limit reached. Please log in to an existing account.";
                            } else if (mode.equals("login") && !accounts.containsKey(input)) {
                                error = "Username doesn't exist. Try again.";
                            } else if (clients.containsKey(input)) {
                                error = "User already connected. Try again.";
                            }
                        }
                    }
                    if (error != null) {
                        if (!invalid(error)) {
                            return false;
                        }
                        send(ChatProtocol.USERNAME_PROMPT);
                    } else {
                        candidateName = input;
                        send(ChatProtocol.PASSWORD_PROMPT);
                    }
                } else {
                    PasswordHash credential;
                    if (mode.equals("signup")) {
                        if (!validPassword(input)) {
                            if (!invalid("Password needs 8-128 characters, an uppercase letter, a digit and a special character.")) {
                                return false;
                            }
                            send(ChatProtocol.PASSWORD_PROMPT);
                            continue;
                        }
                        // Expensive work stays outside the room lock.
                        credential = new PasswordHash(input);
                    } else {
                        synchronized (stateLock) {
                            credential = accounts.get(candidateName);
                        }
                        if (input.length() > ChatProtocol.MAX_PASSWORD_LENGTH
                                || credential == null || !credential.matches(input)) {
                            send("Invalid password. Please try again.");
                            if (++badPasswords >= 5) {
                                send("Too many failed login attempts. Please reconnect.");
                                drainOutput = true;
                                return false;
                            }
                            send(ChatProtocol.PASSWORD_PROMPT);
                            continue;
                        }
                    }
                    String error = completeAuthentication(mode, candidateName, credential, false);
                    if (error == null) {
                        return !disconnected.get();
                    }
                    if (!invalid(error)) {
                        return false;
                    }
                    candidateName = null;
                    send(ChatProtocol.USERNAME_PROMPT);
                }
            }
            return false;
        }

        /** A complete form submission is one frame, so retries never consume the wrong field. */
        private boolean authenticateForms() throws IOException {
            send(ChatProtocol.FORM_READY);
            String frame;
            while (!disconnected.get() && (frame = ChatProtocol.readLine(reader)) != null) {
                String[] fields = frame.split("\t", -1);
                if (resumableForms && fields.length > 0 && fields[0].equals("@resume")) {
                    String resumeError = fields.length == 3
                            ? resumeSession(fields[1], fields[2]) : "Invalid session. Please log in again.";
                    if (resumeError == null) {
                        return !disconnected.get();
                    }
                    if (++invalidInputs >= 20) {
                        send(ChatProtocol.FORM_ERROR_PREFIX + "Too many failed attempts. Please reconnect.");
                        drainOutput = true;
                        return false;
                    }
                    send(ChatProtocol.FORM_ERROR_PREFIX + resumeError);
                    continue;
                }
                String error = null;
                String name = null;
                String password = null;
                String mode = fields.length > 1 ? fields[1] : "";
                if (fields.length != 4 || !fields[0].equals("@auth")
                        || !(mode.equals("login") || mode.equals("signup"))) {
                    error = "Invalid authentication request. Please try again.";
                } else {
                    try {
                        name = ChatProtocol.decodeField(fields[2]);
                        password = ChatProtocol.decodeField(fields[3]);
                    } catch (IOException e) {
                        error = "Invalid authentication request. Please try again.";
                    }
                }
                if (error == null && !validUsername(name)) {
                    error = "Use 1-32 letters, numbers or _, starting with a letter; login/signup are reserved.";
                }
                if (error == null && !validPassword(password)) {
                    error = "Password needs 8-128 characters, an uppercase letter, a digit and a special character.";
                }
                PasswordHash credential = null;
                if (error == null) {
                    synchronized (stateLock) {
                        credential = accounts.get(name);
                        if (mode.equals("signup") && credential != null) {
                            error = "Username already exists. Please log in or choose another username.";
                        } else if (mode.equals("login") && credential == null) {
                            error = "Invalid username or password.";
                        }
                    }
                    if (error == null && mode.equals("signup")) {
                        credential = new PasswordHash(password);
                    } else if (error == null && !credential.matches(password)) {
                        error = "Invalid username or password.";
                    }
                    if (error != null && mode.equals("login")) {
                        badPasswords++;
                    }
                }
                if (error == null) {
                    error = completeAuthentication(mode, name, credential, true);
                    if (error == null) {
                        return !disconnected.get();
                    }
                }
                if (++invalidInputs >= 20 || badPasswords >= 5) {
                    send(ChatProtocol.FORM_ERROR_PREFIX + "Too many failed attempts. Reconnecting is required.");
                    drainOutput = true;
                    return false;
                }
                send(ChatProtocol.FORM_ERROR_PREFIX + error);
            }
            return false;
        }

        /** Returns an error, or null after atomically registering the authenticated session. */
        private String completeAuthentication(String mode, String name, PasswordHash credential, boolean form) {
            List<ClientHandler> failed = new ArrayList<>();
            synchronized (stateLock) {
                if (closed || disconnected.get()) {
                    return "Connection closed. Please reconnect.";
                }
                if (mode.equals("signup") && accounts.containsKey(name)) {
                    return "Username already exists. Try again.";
                }
                if (mode.equals("signup") && accounts.size() >= MAX_ACCOUNTS) {
                    return "Account limit reached. Please log in to an existing account.";
                }
                if (clients.containsKey(name)) {
                    return "User already connected. Try again.";
                }
                if (mode.equals("signup")) {
                    if (store != null) {
                        try {
                            store.saveAccount(name, credential.salt, credential.hash);
                        } catch (IOException e) {
                            System.err.println("Cannot save chat account: " + e.getMessage());
                            return "Account creation could not be confirmed: server storage is unavailable.";
                        }
                    }
                    accounts.put(name, credential);
                }
                String token = resumableForms ? newSessionToken() : null;
                ResumeSession resume = token == null ? null : new ResumeSession(sessionTokenHash(token),
                        System.currentTimeMillis() + SESSION_LIFETIME_MILLIS);
                try {
                    if (resume != null) {
                        if (store != null) {
                            store.saveSession(name, resume.hash, resume.expiresAt);
                        }
                        resumeSessions.put(name, resume);
                    } else {
                        // A fresh password login invalidates any earlier resume credential.
                        ResumeSession previous = resumeSessions.get(name);
                        if (previous != null) {
                            if (store != null) {
                                store.removeSession(name, previous.hash);
                            }
                            resumeSessions.remove(name);
                        }
                    }
                } catch (IOException e) {
                    System.err.println("Cannot save chat session: " + e.getMessage());
                    return "Login could not be confirmed: server storage is unavailable.";
                }
                publishSessionLocked(name, form, token, resume, failed);
            }
            disconnectSlowClients(failed);
            return null;
        }

        /** A valid bearer token may replace its own half-open connection, never another account. */
        private String resumeSession(String name, String token) {
            if (!validUsername(name) || !ChatProtocol.validSessionToken(token)) {
                return "Invalid session. Please log in again.";
            }
            List<ClientHandler> failed = new ArrayList<>();
            synchronized (stateLock) {
                ResumeSession resume = resumeSessions.get(name);
                if (closed || disconnected.get()) {
                    return "Connection closed. Please reconnect.";
                }
                if (resume == null || resume.expiresAt <= System.currentTimeMillis()
                        || !MessageDigest.isEqual(resume.hash, sessionTokenHash(token))) {
                    return "Session expired or no longer valid. Please log in again.";
                }
                ClientHandler previous = clients.get(name);
                if (previous != null) {
                    failed.add(previous);
                }
                publishSessionLocked(name, true, token, resume, failed);
            }
            disconnectSlowClients(failed);
            return null;
        }

        /** Called under stateLock, after credentials and any durable updates are complete. */
        private void publishSessionLocked(String name, boolean form, String token, ResumeSession resume,
                                          List<ClientHandler> failed) {
            username = name;
            sessionHash = resume == null ? null : resume.hash;
            if (token != null && !outgoing.offer(ChatProtocol.FORM_SESSION_PREFIX + token + "\t" + resume.expiresAt)) {
                failed.add(this);
            }
            if (!outgoing.offer(form ? ChatProtocol.FORM_OK_PREFIX + username
                    : "Welcome, " + username + "! You are now connected.")) {
                failed.add(this);
            }
            if (!outgoing.offer(ChatProtocol.HISTORY_START)) {
                failed.add(this);
            }
            for (ChatProtocol.ChatMessage previous : history) {
                if (!outgoing.offer(ChatProtocol.encodeMessage(previous))) {
                    failed.add(this);
                    break;
                }
            }
            if (!outgoing.offer(ChatProtocol.HISTORY_END)) {
                failed.add(this);
            }
            clients.put(username, this);
            broadcastLocked(onlineUsersLocked(), null, failed);
        }

        private boolean revokeSession() {
            synchronized (stateLock) {
                ResumeSession resume = resumeSessions.get(username);
                if (clients.get(username) != this || sessionHash == null || resume == null
                        || !MessageDigest.isEqual(sessionHash, resume.hash)) {
                    return true;
                }
                try {
                    if (store != null && !store.removeSession(username, sessionHash)) {
                        throw new IOException("Session record did not match.");
                    }
                    resumeSessions.remove(username);
                    return true;
                } catch (IOException e) {
                    System.err.println("Cannot revoke chat session: " + e.getMessage());
                    return false;
                }
            }
        }

        private boolean invalid(String message) {
            send(message);
            if (++invalidInputs >= 20) {
                send("Too many invalid attempts. Please reconnect.");
                drainOutput = true;
                return false;
            }
            return true;
        }

        private void send(String message) {
            if (!outgoing.offer(message)) {
                close();
            }
        }

        private void writeOutput(BufferedWriter writer) {
            try {
                while (!disconnected.get()) {
                    String message = outgoing.take();
                    if (message == endOfOutput) {
                        return;
                    }
                    writer.write(message);
                    writer.write('\n');
                    writer.flush();
                }
            } catch (IOException e) {
                close();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private void close() {
            if (disconnected.compareAndSet(false, true)) {
                try {
                    socket.close();
                } catch (IOException ignored) { }
                if (sender != null) {
                    sender.interrupt();
                }
            }
        }
    }
}
