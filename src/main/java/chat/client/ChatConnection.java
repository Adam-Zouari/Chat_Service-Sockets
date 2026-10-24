package chat.client;

import chat.protocol.ChatProtocol;

import javax.swing.SwingUtilities;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Reconnectable desktop networking. No socket work runs on Swing's event thread. */
public final class ChatConnection implements AutoCloseable {
    public interface Listener {
        void onStatus(String text);
        void onReady();
        void onAuthenticated(String username);
        void onAuthenticationError(String error);
        void onMessage(String line);
        default void onChatMessage(ChatProtocol.ChatMessage message, boolean historical) { }
        default void onOnlineUsers(List<String> users) { }
        void onDisconnected(String reason);
    }

    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int RETRY_SECONDS = 3;
    private static final int LOGOUT_TIMEOUT_SECONDS = 2;
    private static final int MAX_EVENTS = 256;
    private static final int DISPLAY_BATCH_SIZE = 50;

    private final Object lock = new Object();
    private final String host;
    private final int port;
    private final Listener listener;
    private final ScheduledThreadPoolExecutor retries;
    private final ArrayDeque<UiEvent> events = new ArrayDeque<>();
    private ScheduledFuture<?> retry;
    private Session session;
    private long generation;
    private boolean started;
    private boolean closed;
    private boolean drainScheduled;
    // Resume credentials live only as long as this window/controller. Never retain a password.
    private String rememberedUsername;
    private String rememberedToken;
    private long rememberedExpiry;

    public ChatConnection(String host, int port, Listener listener) {
        if (host == null || host.isBlank() || port < 1 || port > 65535) {
            throw new IllegalArgumentException("A host and a port between 1 and 65535 are required.");
        }
        this.host = host;
        this.port = port;
        this.listener = Objects.requireNonNull(listener, "listener");
        retries = new ScheduledThreadPoolExecutor(1, runnable -> daemon(runnable, "chat-reconnect"));
        retries.setRemoveOnCancelPolicy(true);
    }

    public void start() {
        synchronized (lock) {
            if (closed || started) {
                return;
            }
            started = true;
            generation++;
            connectLocked();
        }
    }

    /** Replaces even a connecting or stalled connection immediately. */
    public void refresh() {
        reconnect("Refreshing the connection.");
    }

    /** Whether this open window can restore its signed-in identity without a password. */
    public boolean canResumeSession() {
        synchronized (lock) {
            return !closed && rememberedToken != null && rememberedExpiry > System.currentTimeMillis();
        }
    }

    /** Signs out, then opens a fresh connection for the login/signup forms. */
    public void logout() {
        synchronized (lock) {
            if (closed) {
                return;
            }
            forgetSessionLocked();
            if (session != null && session.logoutPending) {
                return;
            }
            if (session != null && session.authenticated) {
                Session signingOut = session;
                signingOut.authenticated = false;
                signingOut.logoutPending = true;
                signingOut.outgoing.clear();
                signingOut.outgoing.offer(ChatProtocol.LOGOUT);
                events.clear();
                lock.notifyAll();
                postLocked(() -> listener.onDisconnected("Signed out."));
                postLocked(() -> listener.onStatus("Signing out..."));
                // The server persists revocation before acknowledging. A stalled server
                // must still leave the local login form usable within a bounded time.
                signingOut.logoutTimeout = retries.schedule(() -> {
                    synchronized (lock) {
                        if (isCurrentLocked(signingOut)) {
                            reconnect("Signed out.");
                        }
                    }
                }, LOGOUT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } else {
                reconnect("Signed out.");
            }
        }
    }

    private void reconnect(String reason) {
        synchronized (lock) {
            if (closed) {
                return;
            }
            started = true;
            cancelRetryLocked();
            closeSessionLocked();
            generation++;
            events.clear();
            lock.notifyAll();
            postLocked(() -> listener.onDisconnected(reason));
            connectLocked();
        }
    }

    /** Consumes the password synchronously; callers may clear their char[] immediately. */
    public boolean authenticate(boolean signup, String username, char[] password) {
        synchronized (lock) {
            if (closed || session == null || !session.ready || session.authenticated
                    || session.authenticationPending || session.logoutPending) {
                return false;
            }
            if (!ChatProtocol.validUsername(username)) {
                postLocked(() -> listener.onAuthenticationError(
                        "Use 1-32 letters, numbers or _, starting with a letter; login/signup are reserved."));
                return false;
            }
            if (password == null || password.length == 0
                    || password.length > ChatProtocol.MAX_PASSWORD_LENGTH) {
                postLocked(() -> listener.onAuthenticationError("Enter a password of at most 128 characters."));
                return false;
            }
            String secret = new String(password);
            if (!ChatProtocol.isAllowedText(secret) || (signup && !ChatProtocol.validPassword(secret))) {
                postLocked(() -> listener.onAuthenticationError(
                        "Password needs 8-128 characters, an uppercase letter, a digit and a special character."));
                return false;
            }
            String frame = ChatProtocol.FORM_AUTH_PREFIX + (signup ? "signup" : "login") + "\t"
                    + ChatProtocol.encodeField(username) + "\t" + ChatProtocol.encodeField(secret);
            if (!session.outgoing.offer(frame)) {
                postLocked(() -> listener.onAuthenticationError("Connection is busy. Please try again."));
                return false;
            }
            session.authenticationPending = true;
            session.authenticationUsername = username;
            return true;
        }
    }

    public boolean sendMessage(String text) {
        synchronized (lock) {
            if (closed || session == null || !session.authenticated || text == null) {
                return false;
            }
            if (text.isBlank() || text.length() > ChatProtocol.MAX_MESSAGE_LENGTH
                    || !ChatProtocol.isAllowedText(text)) {
                postLocked(() -> listener.onStatus("Messages need 1-2000 characters without control characters."));
                return false;
            }
            if (!session.outgoing.offer(ChatProtocol.SEND_PREFIX + text)) {
                postLocked(() -> listener.onStatus("Messages are sending slowly. Please try again shortly."));
                return false;
            }
            return true;
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            forgetSessionLocked();
            generation++;
            cancelRetryLocked();
            closeSessionLocked();
            events.clear();
            lock.notifyAll();
            retries.shutdownNow();
        }
    }

    /** The caller holds lock. Each attempt gets its own socket, queue and workers. */
    private void connectLocked() {
        if (closed) {
            return;
        }
        Session next = new Session(generation);
        session = next;
        postLocked(() -> listener.onStatus("Connecting to " + host + ":" + port + "..."));
        next.reader = daemon(() -> readConnection(next), "chat-reader-" + generation);
        next.reader.start();
    }

    private void readConnection(Session current) {
        try {
            current.socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            current.socket.setTcpNoDelay(true);
            BufferedReader input = ChatProtocol.newUtf8Reader(current.socket.getInputStream());
            BufferedWriter output = ChatProtocol.newUtf8Writer(current.socket.getOutputStream());
            synchronized (lock) {
                if (!isCurrentLocked(current)) {
                    return;
                }
                current.outgoing.add(ChatProtocol.FORM_SESSION_HELLO);
                current.writer = daemon(() -> writeConnection(current, output), "chat-writer-" + current.generation);
                current.writer.start();
            }
            String line;
            while ((line = ChatProtocol.readLine(input)) != null) {
                synchronized (lock) {
                    if (!isCurrentLocked(current)) {
                        return;
                    }
                    handleLineLocked(current, line);
                }
            }
            disconnected(current, "The server closed the connection.");
        } catch (IOException e) {
            disconnected(current, "Cannot reach the server or the connection was lost.");
        } finally {
            closeSocket(current.socket);
        }
    }

    private void writeConnection(Session current, BufferedWriter output) {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                String line = current.outgoing.take();
                output.write(line);
                output.newLine();
                output.flush();
                line = null; // Do not retain a sent authentication frame while take() waits.
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            disconnected(current, "The connection was lost while sending.");
        }
    }

    private void handleLineLocked(Session current, String line) throws IOException {
        if (current.logoutPending) {
            if (line.equals("Goodbye.")) {
                reconnect("Signed out.");
            }
            return;
        }
        if (line.startsWith(ChatProtocol.FORM_SESSION_PREFIX)) {
            if (!current.authenticationPending || current.provisionalToken != null) {
                throw new IOException("Unexpected session response.");
            }
            String[] fields = line.substring(ChatProtocol.FORM_SESSION_PREFIX.length()).split("\t", -1);
            long expiry;
            try {
                if (fields.length != 2 || !fields[0].matches("[A-Za-z0-9_-]{43}")) {
                    throw new NumberFormatException();
                }
                expiry = Long.parseLong(fields[1]);
                if (expiry <= 0 || (current.resumePending
                        && (!fields[0].equals(rememberedToken) || expiry != rememberedExpiry))) {
                    throw new NumberFormatException();
                }
            } catch (NumberFormatException malformed) {
                throw new IOException("Invalid session response.");
            }
            current.provisionalToken = fields[0];
            current.provisionalExpiry = expiry;
            return;
        }
        // Waiting here applies bounded backpressure to history and live traffic, while
        // leaving the EDT free to drain events or replace/close this connection.
        while (events.size() >= MAX_EVENTS - 4 && isCurrentLocked(current)) {
            try {
                lock.wait();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        if (!isCurrentLocked(current)) {
            return;
        }
        if (handleChatFrameLocked(current, line)) {
            return;
        }
        String content = line;
        if (line.startsWith(ChatProtocol.FORM_OK_PREFIX)) {
            content = line.substring(ChatProtocol.FORM_OK_PREFIX.length());
        } else if (line.startsWith(ChatProtocol.FORM_ERROR_PREFIX)) {
            content = line.substring(ChatProtocol.FORM_ERROR_PREFIX.length());
        }
        if (!ChatProtocol.isAllowedText(content)) {
            throw new IOException("The server sent invalid text.");
        }
        if (line.equals(ChatProtocol.FORM_READY) && !current.ready) {
            current.ready = true;
            if (rememberedToken != null && rememberedExpiry > System.currentTimeMillis()) {
                current.outgoing.add(ChatProtocol.FORM_RESUME_PREFIX + rememberedUsername + "\t" + rememberedToken);
                current.authenticationPending = true;
                current.resumePending = true;
                current.authenticationUsername = rememberedUsername;
                postLocked(() -> listener.onStatus("Connected. Restoring your session..."));
            } else {
                forgetSessionLocked();
                postLocked(() -> listener.onStatus("Connected. Log in or create an account."));
                postLocked(listener::onReady);
            }
        } else if (line.startsWith(ChatProtocol.FORM_OK_PREFIX)) {
            String username = line.substring(ChatProtocol.FORM_OK_PREFIX.length());
            if (!current.ready || !current.authenticationPending || !ChatProtocol.validUsername(username)
                    || !username.equals(current.authenticationUsername)
                    || (current.resumePending && current.provisionalToken == null)) {
                throw new IOException("Unexpected authentication response.");
            }
            forgetSessionLocked();
            if (current.provisionalToken != null) {
                rememberedUsername = username;
                rememberedToken = current.provisionalToken;
                rememberedExpiry = current.provisionalExpiry;
            }
            current.provisionalToken = null;
            current.authenticationUsername = null;
            current.authenticationPending = false;
            current.resumePending = false;
            current.authenticated = true;
            current.historical = true;
            postLocked(() -> listener.onStatus("Connected as " + username + "."));
            postLocked(() -> listener.onAuthenticated(username));
        } else if (line.startsWith(ChatProtocol.FORM_ERROR_PREFIX)) {
            if (!current.authenticationPending) {
                throw new IOException("Unexpected authentication error.");
            }
            String error = line.substring(ChatProtocol.FORM_ERROR_PREFIX.length());
            current.authenticationPending = false;
            current.provisionalToken = null;
            current.authenticationUsername = null;
            if (current.resumePending) {
                current.resumePending = false;
                forgetSessionLocked();
                postLocked(listener::onReady);
            }
            postLocked(() -> listener.onAuthenticationError(error));
        } else if (current.authenticated) {
            postLocked(() -> listener.onMessage(line));
        }
        // Legacy welcome and choice prompts precede the forms handshake and are ignored.
    }

    private boolean handleChatFrameLocked(Session current, String line) throws IOException {
        if (line.startsWith("@message")) {
            if (!current.authenticated || (current.historical && !current.historyStarted)) {
                throw new IOException("Unexpected chat message.");
            }
            ChatProtocol.ChatMessage message = ChatProtocol.parseMessage(line);
            boolean historical = current.historical;
            if (historical && ++current.historyCount > 500) {
                throw new IOException("The server sent too much history.");
            }
            postLocked(() -> listener.onChatMessage(message, historical));
            return true;
        }
        if (line.startsWith(ChatProtocol.HISTORY_START)) {
            if (!line.equals(ChatProtocol.HISTORY_START) || !current.authenticated
                    || !current.historical || current.historyStarted) {
                throw new IOException("Unexpected history start.");
            }
            current.historyStarted = true;
            return true;
        }
        if (line.startsWith(ChatProtocol.HISTORY_END)) {
            if (!line.equals(ChatProtocol.HISTORY_END) || !current.authenticated
                    || !current.historical || !current.historyStarted) {
                throw new IOException("Unexpected history end.");
            }
            current.historical = false;
            return true;
        }
        if (line.startsWith("@users")) {
            if (!current.authenticated || current.historical
                    || (!line.equals("@users") && !line.startsWith(ChatProtocol.USERS_PREFIX))) {
                throw new IOException("Unexpected online-user list.");
            }
            List<String> users = new ArrayList<>();
            Set<String> unique = new HashSet<>();
            if (!line.equals("@users")) {
                String[] names = line.substring(ChatProtocol.USERS_PREFIX.length()).split("\t", -1);
                if (names.length > 64) {
                    throw new IOException("Too many online users.");
                }
                for (String username : names) {
                    if (!ChatProtocol.validUsername(username) || !unique.add(username)) {
                        throw new IOException("Invalid online-user list.");
                    }
                    users.add(username);
                }
            }
            List<String> snapshot = List.copyOf(users);
            postLocked(() -> listener.onOnlineUsers(snapshot));
            return true;
        }
        return false;
    }

    private void disconnected(Session current, String reason) {
        synchronized (lock) {
            if (!isCurrentLocked(current)) {
                return;
            }
            if (current.logoutPending) {
                reconnect("Signed out.");
                return;
            }
            closeSessionLocked();
            generation++;
            events.clear();
            lock.notifyAll();
            postLocked(() -> listener.onDisconnected(reason));
            postLocked(() -> listener.onStatus(reason + " Retrying in " + RETRY_SECONDS + " seconds..."));
            long retryGeneration = generation;
            retry = retries.schedule(() -> {
                synchronized (lock) {
                    if (!closed && generation == retryGeneration) {
                        retry = null;
                        connectLocked();
                    }
                }
            }, RETRY_SECONDS, TimeUnit.SECONDS);
        }
    }

    private boolean isCurrentLocked(Session current) {
        return !closed && session == current && generation == current.generation;
    }

    private void closeSessionLocked() {
        Session previous = session;
        session = null;
        if (previous != null) {
            if (previous.logoutTimeout != null) {
                previous.logoutTimeout.cancel(false);
            }
            previous.outgoing.clear();
            previous.provisionalToken = null;
            closeSocket(previous.socket);
            if (previous.writer != null) {
                previous.writer.interrupt();
            }
            if (previous.reader != null) {
                previous.reader.interrupt();
            }
        }
    }

    private void cancelRetryLocked() {
        if (retry != null) {
            retry.cancel(false);
            retry = null;
        }
    }

    private void forgetSessionLocked() {
        rememberedUsername = null;
        rememberedToken = null;
        rememberedExpiry = 0;
    }

    private void postLocked(Runnable action) {
        // Normal incoming traffic leaves room for control events. Repeated local
        // status updates may be coalesced if a caller temporarily outruns Swing.
        if (events.size() == MAX_EVENTS) {
            return;
        }
        events.addLast(new UiEvent(generation, action));
        if (!drainScheduled) {
            drainScheduled = true;
            SwingUtilities.invokeLater(this::drainEvents);
        }
    }

    private void drainEvents() {
        try {
            for (int i = 0; i < DISPLAY_BATCH_SIZE; i++) {
                synchronized (lock) {
                    UiEvent event = events.pollFirst();
                    lock.notifyAll();
                    if (event == null) {
                        return;
                    }
                    if (!closed && event.generation == generation) {
                        // Holding lock through the callback prevents a background
                        // refresh/close from delivering an event from an old session.
                        event.action.run();
                    }
                }
            }
        } finally {
            synchronized (lock) {
                drainScheduled = false;
                if (!events.isEmpty() && !closed) {
                    drainScheduled = true;
                    SwingUtilities.invokeLater(this::drainEvents);
                }
            }
        }
    }

    private static void closeSocket(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Closing a replaced or failed socket is best effort.
        }
    }

    private static Thread daemon(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }

    private static final class Session {
        final long generation;
        final Socket socket = new Socket();
        final BlockingQueue<String> outgoing = new ArrayBlockingQueue<>(32);
        Thread reader;
        Thread writer;
        boolean ready;
        boolean authenticated;
        boolean authenticationPending;
        boolean resumePending;
        boolean logoutPending;
        boolean historical;
        boolean historyStarted;
        int historyCount;
        String authenticationUsername;
        String provisionalToken;
        long provisionalExpiry;
        ScheduledFuture<?> logoutTimeout;

        Session(long generation) {
            this.generation = generation;
        }
    }

    private static final class UiEvent {
        final long generation;
        final Runnable action;

        UiEvent(long generation, Runnable action) {
            this.generation = generation;
            this.action = action;
        }
    }
}
