package chat.client;

import chat.protocol.ChatProtocol;
import chat.server.ChatServer;

import javax.swing.SwingUtilities;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/** Headless integration tests for the actual desktop networking controller. */
public final class ChatConnectionTest {
    private static final String PASSWORD = "SecurePassword9!";
    private static final String TOKEN = "abcdefghijklmnopqrstuvwxyz0123456789_-ABCDE";
    private static int passed;

    private ChatConnectionTest() { }

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        run("forms, rejected credentials, UTF-8 and literal command text", ChatConnectionTest::authentication);
        run("logout returns to forms and permits signup and login", ChatConnectionTest::quitAndLogin);
        run("refresh discards old session events and credentials", ChatConnectionTest::refresh);
        run("server restart reconnects automatically without replaying credentials", ChatConnectionTest::restart);
        run("session resumes after disconnect and Refresh without a password", ChatConnectionTest::resume);
        run("rejected and expired sessions restore usable login forms", ChatConnectionTest::resumeFallback);
        run("logout revokes the session before reconnecting and never resumes", ChatConnectionTest::sessionLogout);
        run("logout timeout and offline logout keep the forms usable", ChatConnectionTest::logoutRecovery);
        run("session frames stay secret and malformed frames are rejected", ChatConnectionTest::sessionFrames);
        run("initial failure retries and closing cancels retries", ChatConnectionTest::initialFailureAndClose);
        run("large history drains in order without flooding Swing", ChatConnectionTest::history);
        run("structured history and live messages retain timestamps and update presence", ChatConnectionTest::structuredMessages);
        run("malformed message, history and presence frames never reach Swing", ChatConnectionTest::malformedChatFrames);
        run("malformed server text never reaches Swing", ChatConnectionTest::malformedText);
        run("real server chat, presence, logout and reauthentication", ChatConnectionTest::realServer);
        run("a real process crash restores the authenticated session from persistent storage", ChatConnectionTest::crashRecovery);
        System.out.println("Passed " + passed + " connection tests.");
    }

    private static void run(String name, CheckedRunnable test) throws Exception {
        test.run();
        passed++;
        System.out.println("PASS " + name);
    }

    @SuppressWarnings("try") // Explicit early close is the behavior being tested.
    private static void authentication() throws Exception {
        Events events = new Events();
        try (StubServer server = new StubServer(0);
             ChatConnection connection = new ChatConnection("127.0.0.1", server.port(), events)) {
            connection.start();
            Peer peer = server.accept();
            peer.handshake();
            events.await("ready");
            check(!connection.authenticate(true, "not allowed", PASSWORD.toCharArray()), "Invalid username accepted");
            events.awaitPrefix("error:");
            check(connection.authenticate(false, "Alice", PASSWORD.toCharArray()), "Login was not queued");
            checkAuth(peer.read(), false, "Alice", PASSWORD);
            check(!connection.authenticate(false, "Alice", PASSWORD.toCharArray()), "Duplicate pending auth was accepted");
            peer.send(ChatProtocol.FORM_ERROR_PREFIX + "Wrong password. Try again.");
            events.await("error:Wrong password. Try again.");
            char[] password = PASSWORD.toCharArray();
            check(connection.authenticate(false, "Alice", password), "Auth error left connection unusable");
            Arrays.fill(password, '\0');
            checkAuth(peer.read(), false, "Alice", PASSWORD);
            peer.authenticated("Alice");
            events.await("authenticated:Alice");
            for (String literal : Arrays.asList("/help", "/users", "/quit", "@logout", "@who", "@send text")) {
                check(connection.sendMessage(literal), "Literal message could not be sent: " + literal);
                check((ChatProtocol.SEND_PREFIX + literal).equals(peer.read()), "Chat text became a command");
            }
            check(!connection.sendMessage("hello\t@logout"), "A tab injected a control frame");
            check(!connection.sendMessage("hello\n@logout"), "A newline injected a control frame");
            peer.noInput();
            String unicode = "caf\u00e9 \ud83d\udc4b";
            check(connection.sendMessage(unicode), "UTF-8 message was rejected");
            check((ChatProtocol.SEND_PREFIX + unicode).equals(peer.read()), "UTF-8 message was corrupted");
            connection.close();
            check(peer.read() == null, "Closing client did not close its socket");
            events.checkThread();
        }
    }

    private static void quitAndLogin() throws Exception {
        Events events = new Events();
        try (StubServer server = new StubServer(0);
             ChatConnection connection = new ChatConnection("127.0.0.1", server.port(), events)) {
            connection.start();
            check(!connection.canResumeSession(), "Unauthenticated client claims it can resume");
            Peer first = server.accept();
            login(connection, first, events, false, "Alice");
            connection.logout();
            check(ChatProtocol.LOGOUT.equals(first.read()), "Logout did not ask the server to sign out");
            first.send("Goodbye.");
            check(first.read() == null, "Quit kept old session open");
            Peer second = server.accept();
            second.handshake();
            events.await("ready");
            second.noInput();
            check(connection.authenticate(true, "Bob", PASSWORD.toCharArray()), "Signup unavailable after quit");
            checkAuth(second.read(), true, "Bob", PASSWORD);
            second.authenticated("Bob");
            events.await("authenticated:Bob");
            connection.logout();
            check(ChatProtocol.LOGOUT.equals(second.read()), "Logout did not ask the server to sign out");
            second.send("Goodbye.");
            check(second.read() == null, "Logout kept old session open");
            Peer third = server.accept();
            login(connection, third, events, false, "Alice");
            events.checkThread();
        }
    }

    private static void refresh() throws Exception {
        Events events = new Events();
        CountDownLatch releaseEdt = new CountDownLatch(1);
        try (StubServer server = new StubServer(0);
             ChatConnection connection = new ChatConnection("127.0.0.1", server.port(), events)) {
            connection.start();
            Peer first = server.accept();
            login(connection, first, events, false, "Alice");
            CountDownLatch blockedEdt = new CountDownLatch(1);
            SwingUtilities.invokeLater(() -> {
                blockedEdt.countDown();
                try {
                    releaseEdt.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            check(blockedEdt.await(5, TimeUnit.SECONDS), "Could not pause EDT");
            first.send("stale old session message");
            connection.refresh();
            check(first.read() == null, "Refresh kept old session open");
            Peer second = server.accept();
            second.handshake();
            releaseEdt.countDown();
            events.await("ready");
            second.noInput();
            SwingUtilities.invokeAndWait(() -> { });
            check(!events.recorded.contains("message:stale old session message"), "Old message crossed a refresh");
            check(connection.authenticate(false, "Alice", PASSWORD.toCharArray()), "Refresh did not restore login");
            checkAuth(second.read(), false, "Alice", PASSWORD);
            second.authenticated("Alice");
            events.await("authenticated:Alice");
            events.checkThread();
        } finally {
            releaseEdt.countDown();
        }
    }

    @SuppressWarnings("try") // Stop the server while the surrounding client remains open.
    private static void restart() throws Exception {
        Events events = new Events();
        int port;
        try (StubServer initial = new StubServer(0)) {
            port = initial.port();
            try (ChatConnection connection = new ChatConnection("127.0.0.1", port, events)) {
                connection.start();
                Peer first = initial.accept();
                login(connection, first, events, false, "Alice");
                initial.close();
                events.awaitPrefix("disconnected:");
                try (StubServer restarted = new StubServer(port)) {
                    Peer second = restarted.accept();
                    second.handshake();
                    events.await("ready");
                    second.noInput();
                    check(connection.authenticate(false, "Alice", PASSWORD.toCharArray()), "Restart left client stuck");
                    checkAuth(second.read(), false, "Alice", PASSWORD);
                    second.authenticated("Alice");
                    events.await("authenticated:Alice");
                    events.checkThread();
                }
            }
        }
    }

    private static void initialFailureAndClose() throws Exception {
        int port;
        try (StubServer reservation = new StubServer(0)) {
            port = reservation.port();
        }
        Events events = new Events();
        ChatConnection connection = new ChatConnection("127.0.0.1", port, events);
        try {
            connection.start();
            events.awaitPrefix("disconnected:");
            try (StubServer server = new StubServer(port)) {
                Peer peer = server.accept();
                peer.handshake();
                events.await("ready");
                peer.close();
                events.awaitPrefix("disconnected:");
                connection.close();
                SwingUtilities.invokeAndWait(() -> { });
                int callbacksAtClose = events.recorded.size();
                server.noConnection();
                check(events.recorded.size() == callbacksAtClose, "Closed client still delivered callbacks");
            }
            events.checkThread();
        } finally {
            connection.close();
        }
    }

    @SuppressWarnings("try") // Closing the window must forget its in-memory session immediately.
    private static void resume() throws Exception {
        Events events = new Events();
        long expiry = System.currentTimeMillis() + TimeUnit.DAYS.toMillis(7);
        try (StubServer server = new StubServer(0);
             ChatConnection connection = new ChatConnection("127.0.0.1", server.port(), events)) {
            connection.start();
            Peer first = server.accept();
            loginWithSession(connection, first, events, expiry);
            check(connection.canResumeSession(), "Successful login did not remember its session");
            first.close();
            events.awaitPrefix("disconnected:");
            check(connection.canResumeSession(), "Disconnect forgot a still valid session");
            Peer second = server.accept();
            resumeSession(second, events, expiry);
            second.send("session restored");
            events.await("message:session restored");
            check(connection.sendMessage("back online"), "Resumed session cannot send");
            check((ChatProtocol.SEND_PREFIX + "back online").equals(second.read()), "Resumed message was lost");
            connection.refresh();
            check(connection.canResumeSession(), "Refresh forgot a still valid session");
            check(second.read() == null, "Refresh kept the old socket open");
            Peer third = server.accept();
            resumeSession(third, events, expiry);
            third.noInput();
            check(events.recorded.stream().filter(value -> value.equals("ready")).count() == 1,
                    "Login forms were enabled during automatic resume");
            check(events.recorded.stream().noneMatch(value -> value.contains(TOKEN)), "Session token leaked to the UI");
            events.checkThread();
            connection.close();
            check(!connection.canResumeSession(), "Closing the window retained a resumable session");
        }
    }

    private static void resumeFallback() throws Exception {
        Events events = new Events();
        long expiry = System.currentTimeMillis() + TimeUnit.DAYS.toMillis(7);
        try (StubServer server = new StubServer(0);
             ChatConnection connection = new ChatConnection("127.0.0.1", server.port(), events)) {
            connection.start();
            Peer first = server.accept();
            loginWithSession(connection, first, events, expiry);
            connection.refresh();
            Peer second = server.accept();
            second.handshake();
            checkResume(second.read());
            second.send(ChatProtocol.FORM_ERROR_PREFIX + "Your session expired. Please log in again.");
            events.await("ready");
            events.await("error:Your session expired. Please log in again.");
            check(!connection.canResumeSession(), "Rejected session was still resumable");
            check(connection.authenticate(false, "Alice", PASSWORD.toCharArray()), "Rejected resume left forms unusable");
            checkAuth(second.read(), false, "Alice", PASSWORD);
            // An already expired credential is never sent back to the server.
            second.send(ChatProtocol.FORM_SESSION_PREFIX + TOKEN + "\t1");
            second.authenticated("Alice");
            events.await("authenticated:Alice");
            check(!connection.canResumeSession(), "Already expired session was still resumable");
            connection.refresh();
            Peer third = server.accept();
            third.handshake();
            events.await("ready");
            third.noInput();
            check(connection.authenticate(false, "Alice", PASSWORD.toCharArray()), "Expired session left forms unusable");
            checkAuth(third.read(), false, "Alice", PASSWORD);
            third.send(ChatProtocol.FORM_ERROR_PREFIX + "Wrong password.");
            events.await("error:Wrong password.");
            connection.refresh();
            Peer fourth = server.accept();
            fourth.handshake();
            events.await("ready");
            fourth.noInput();
        }
    }

    private static void sessionLogout() throws Exception {
        Events events = new Events();
        try (StubServer server = new StubServer(0);
             ChatConnection connection = new ChatConnection("127.0.0.1", server.port(), events)) {
            connection.start();
            Peer first = server.accept();
            loginWithSession(connection, first, events, System.currentTimeMillis() + 60_000);
            connection.logout();
            check(!connection.canResumeSession(), "Logout retained the remembered session");
            events.await("disconnected:Signed out.");
            check(ChatProtocol.LOGOUT.equals(first.read()), "Logout did not request token revocation");
            first.noInput(); // Keep the socket alive until the revocation acknowledgement.
            first.send("Goodbye.");
            Peer second = server.accept();
            second.handshake();
            events.await("ready");
            second.noInput();
            check(connection.authenticate(false, "Alice", PASSWORD.toCharArray()), "Logout did not restore login");
            checkAuth(second.read(), false, "Alice", PASSWORD);
            second.authenticated("Alice");
            events.await("authenticated:Alice");
        }
    }

    private static void logoutRecovery() throws Exception {
        Events events = new Events();
        try (StubServer server = new StubServer(0);
             ChatConnection connection = new ChatConnection("127.0.0.1", server.port(), events)) {
            connection.start();
            Peer first = server.accept();
            loginWithSession(connection, first, events, System.currentTimeMillis() + 60_000);
            connection.logout();
            check(ChatProtocol.LOGOUT.equals(first.read()), "Logout request was lost");
            Peer second = server.accept(); // No goodbye: bounded timeout must reconnect.
            second.handshake();
            events.await("ready");
            second.noInput();
            check(first.read() == null, "Logout timeout kept old socket open");
            check(connection.authenticate(false, "Alice", PASSWORD.toCharArray()), "Could not authenticate again");
            checkAuth(second.read(), false, "Alice", PASSWORD);
            second.send(ChatProtocol.FORM_SESSION_PREFIX + TOKEN + "\t" + (System.currentTimeMillis() + 60_000));
            second.authenticated("Alice");
            events.await("authenticated:Alice");
            second.close();
            events.awaitPrefix("disconnected:");
            connection.logout();
            Peer third = server.accept();
            third.handshake();
            events.await("ready");
            third.noInput();
        }
    }

    private static void sessionFrames() throws Exception {
        for (String malformed : Arrays.asList("short\t12345", TOKEN + "\t0", TOKEN + "\tbad",
                TOKEN + "\t123\textra", "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!\t123")) {
            Events events = new Events();
            try (StubServer server = new StubServer(0);
                 ChatConnection connection = new ChatConnection("127.0.0.1", server.port(), events)) {
                connection.start();
                Peer peer = server.accept();
                peer.handshake();
                events.await("ready");
                check(connection.authenticate(false, "Alice", PASSWORD.toCharArray()), "Cannot begin authentication");
                peer.read();
                peer.send(ChatProtocol.FORM_SESSION_PREFIX + malformed);
                events.awaitPrefix("disconnected:");
                check(events.recorded.stream().noneMatch(value -> value.contains(TOKEN) || value.contains("@session")),
                        "Malformed session response leaked to the UI");
            }
        }
        Events events = new Events();
        try (StubServer server = new StubServer(0);
             ChatConnection connection = new ChatConnection("127.0.0.1", server.port(), events)) {
            connection.start();
            Peer first = server.accept();
            loginWithSession(connection, first, events, System.currentTimeMillis() + 60_000);
            connection.refresh();
            events.await("disconnected:Refreshing the connection.");
            Peer second = server.accept();
            second.handshake();
            checkResume(second.read());
            second.send(ChatProtocol.FORM_OK_PREFIX + "Alice");
            events.awaitPrefix("disconnected:");
            check(events.recorded.stream().filter(value -> value.equals("authenticated:Alice")).count() == 1,
                    "Resume accepted success without its matching session response");
            check(connection.canResumeSession(), "An invalid server response erased the valid local session");
        }
    }

    private static void loginWithSession(ChatConnection connection, Peer peer, Events events, long expiry) throws Exception {
        peer.handshake();
        events.await("ready");
        check(connection.authenticate(false, "Alice", PASSWORD.toCharArray()), "Login was not queued");
        checkAuth(peer.read(), false, "Alice", PASSWORD);
        peer.send(ChatProtocol.FORM_SESSION_PREFIX + TOKEN + "\t" + expiry);
        peer.authenticated("Alice");
        events.await("authenticated:Alice");
    }

    private static void resumeSession(Peer peer, Events events, long expiry) throws Exception {
        peer.handshake();
        checkResume(peer.read());
        peer.send(ChatProtocol.FORM_SESSION_PREFIX + TOKEN + "\t" + expiry);
        peer.authenticated("Alice");
        events.await("authenticated:Alice");
    }

    private static void checkResume(String line) {
        check((ChatProtocol.FORM_RESUME_PREFIX + "Alice\t" + TOKEN).equals(line),
                "Reconnect did not send the expected resume token");
        check(!line.contains(PASSWORD) && !line.startsWith(ChatProtocol.FORM_AUTH_PREFIX),
                "Reconnect replayed a password");
    }

    private static void history() throws Exception {
        Events events = new Events();
        try (StubServer server = new StubServer(0);
             ChatConnection connection = new ChatConnection("127.0.0.1", server.port(), events)) {
            connection.start();
            Peer peer = server.accept();
            peer.handshake();
            events.await("ready");
            check(connection.authenticate(false, "Alice", PASSWORD.toCharArray()), "Cannot start history login");
            checkAuth(peer.read(), false, "Alice", PASSWORD);
            peer.send(ChatProtocol.FORM_OK_PREFIX + "Alice");
            peer.send(ChatProtocol.HISTORY_START);
            events.await("authenticated:Alice");
            for (int i = 0; i < 500; i++) {
                peer.send(ChatProtocol.encodeMessage(new ChatProtocol.ChatMessage(1_700_000_000_000L + i,
                        "Alice", "history " + i)));
            }
            peer.send(ChatProtocol.HISTORY_END);
            peer.send(ChatProtocol.USERS_PREFIX + "Alice");
            for (int i = 0; i < 500; i++) {
                events.await("chat:history:Alice: history " + i);
                check(events.chatMessages.get(i).timestampMillis() == 1_700_000_000_000L + i,
                        "History timestamp changed");
            }
            events.await("users:Alice");
            events.checkThread();
        }
    }

    private static void structuredMessages() throws Exception {
        Events events = new Events();
        try (StubServer server = new StubServer(0);
             ChatConnection connection = new ChatConnection("127.0.0.1", server.port(), events)) {
            connection.start();
            Peer peer = server.accept();
            peer.handshake();
            events.await("ready");
            check(connection.authenticate(false, "Alice", PASSWORD.toCharArray()), "Cannot start login");
            checkAuth(peer.read(), false, "Alice", PASSWORD);
            peer.send(ChatProtocol.FORM_OK_PREFIX + "Alice");
            peer.send(ChatProtocol.HISTORY_START);
            peer.send(ChatProtocol.encodeMessage(new ChatProtocol.ChatMessage(1_700_000_000_123L, "Bob", "earlier")));
            peer.send(ChatProtocol.HISTORY_END);
            peer.send(ChatProtocol.USERS_PREFIX + "Alice\tBob");
            peer.send(ChatProtocol.encodeMessage(new ChatProtocol.ChatMessage(1_700_000_001_456L, "Bob", "new message")));
            peer.send(ChatProtocol.USERS_PREFIX + "Alice");
            peer.send("@users");
            events.await("authenticated:Alice");
            events.await("chat:history:Bob: earlier");
            events.await("users:Alice,Bob");
            events.await("chat:live:Bob: new message");
            events.await("users:Alice");
            events.await("users:");
            check(events.chatMessages.get(0).timestampMillis() == 1_700_000_000_123L
                    && events.chatMessages.get(1).timestampMillis() == 1_700_000_001_456L,
                    "Server timestamps were replaced with receive times");
            check(events.historicalFlags.equals(Arrays.asList(true, false)), "History and live events were mixed");
            check(events.userSnapshots.get(0).equals(Arrays.asList("Alice", "Bob")), "Old presence snapshot changed");
            try {
                events.userSnapshots.get(0).add("Mallory");
                throw new AssertionError("Presence snapshot was mutable");
            } catch (UnsupportedOperationException expected) {
                // A view cannot change membership seen by other callbacks.
            }
            check(events.recorded.stream().noneMatch(value -> value.startsWith("message:@")),
                    "Protocol frames leaked as chat text");
            events.checkThread();
        }
    }

    private static void malformedChatFrames() throws Exception {
        List<String> frames = new ArrayList<>(Arrays.asList(
                "@message", "@message\tbad\tAlice\ttext", "@message\t-1\tAlice\ttext",
                "@message\t1\tbad user\ttext", "@message\t1\tAlice\t",
                "@message\t1\tAlice\ttext\textra", "@history", "@history extra", "@live",
                "@users\tAlice\tAlice", "@users\tAlice\tbad user", "@users\t", "@users extra"));
        List<String> excessive = new ArrayList<>();
        for (int i = 0; i < 65; i++) {
            excessive.add("User" + i);
        }
        frames.add(ChatProtocol.USERS_PREFIX + String.join("\t", excessive));
        for (String frame : frames) {
            Events events = new Events();
            try (StubServer server = new StubServer(0);
                 ChatConnection connection = new ChatConnection("127.0.0.1", server.port(), events)) {
                connection.start();
                Peer peer = server.accept();
                login(connection, peer, events, false, "Alice");
                peer.send(frame);
                events.awaitPrefix("disconnected:");
                check(events.chatMessages.isEmpty() && events.userSnapshots.isEmpty(),
                        "Malformed frame reached the UI: " + frame);
            }
        }
        for (String frame : Arrays.asList("@message\t1\tAlice\tsecret", "@users\tAlice", "@history", "@live")) {
            Events events = new Events();
            try (StubServer server = new StubServer(0);
                 ChatConnection connection = new ChatConnection("127.0.0.1", server.port(), events)) {
                connection.start();
                Peer peer = server.accept();
                peer.handshake();
                events.await("ready");
                peer.send(frame);
                events.awaitPrefix("disconnected:");
                check(events.chatMessages.isEmpty() && events.userSnapshots.isEmpty(),
                        "Preauthentication frame exposed room data");
            }
        }
    }

    private static void malformedText() throws Exception {
        for (String malformed : Arrays.asList("invalid\u0000message", "unexpected\tframe",
                ChatProtocol.FORM_ERROR_PREFIX + "unexpected authentication error")) {
            Events events = new Events();
            try (StubServer server = new StubServer(0);
                 ChatConnection connection = new ChatConnection("127.0.0.1", server.port(), events)) {
                connection.start();
                Peer peer = server.accept();
                login(connection, peer, events, false, "Alice");
                peer.send(malformed);
                events.awaitPrefix("disconnected:");
                check(events.recorded.stream().noneMatch(value -> value.equals("message:" + malformed)),
                        "Invalid server text reached the message area");
            }
        }
        Events events = new Events();
        try (StubServer server = new StubServer(0);
             ChatConnection connection = new ChatConnection("127.0.0.1", server.port(), events)) {
            connection.start();
            Peer peer = server.accept();
            peer.handshake();
            events.await("ready");
            check(connection.authenticate(false, "Alice", PASSWORD.toCharArray()), "Could not start login");
            peer.read();
            peer.send(ChatProtocol.FORM_ERROR_PREFIX + "invalid\u001bmessage");
            events.awaitPrefix("disconnected:");
            check(events.recorded.stream().noneMatch(value -> value.startsWith("error:")),
                    "Malformed authentication error reached the form");
        }
    }

    private static void realServer() throws Exception {
        Events events = new Events();
        AtomicReference<Throwable> serverFailure = new AtomicReference<>();
        try (ChatServer server = new ChatServer(InetAddress.getByName("127.0.0.1"), 0)) {
            Thread worker = new Thread(() -> {
                try {
                    server.start();
                } catch (Throwable failure) {
                    serverFailure.set(failure);
                }
            }, "connection-test-real-server");
            worker.setDaemon(true);
            worker.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (server.getPort() <= 0 && serverFailure.get() == null && System.nanoTime() < deadline) {
                Thread.sleep(1);
            }
            check(server.getPort() > 0 && serverFailure.get() == null, "Real server did not start");
            try (ChatConnection connection = new ChatConnection("127.0.0.1", server.getPort(), events)) {
                connection.start();
                events.await("ready");
                check(connection.authenticate(true, "ConnectionTester", PASSWORD.toCharArray()), "Signup unavailable");
                events.await("authenticated:ConnectionTester");
                events.await("users:ConnectionTester");
                check(connection.sendMessage("/help"), "Literal help text unavailable");
                events.await("chat:live:ConnectionTester: /help");
                check(connection.sendMessage("real server test"), "Message unavailable");
                events.await("chat:live:ConnectionTester: real server test");
                check(connection.sendMessage("/quit"), "Literal quit text unavailable");
                events.await("chat:live:ConnectionTester: /quit");
                check(connection.sendMessage("@logout"), "Literal control text unavailable");
                events.await("chat:live:ConnectionTester: @logout");
                connection.logout();
                events.await("ready");
                check(connection.authenticate(false, "ConnectionTester", PASSWORD.toCharArray()), "Login unavailable after quit");
                events.await("authenticated:ConnectionTester");
                events.await("chat:history:ConnectionTester: /help");
                events.await("chat:history:ConnectionTester: real server test");
                events.await("chat:history:ConnectionTester: /quit");
                events.await("chat:history:ConnectionTester: @logout");
                events.await("users:ConnectionTester");
                events.checkThread();
            }
        }
        check(serverFailure.get() == null, "Real server failed: " + serverFailure.get());
    }

    private static void crashRecovery() throws Exception {
        Path directory = Files.createTempDirectory("chat-client-crash-");
        Events events = new Events();
        int port;
        try (StubServer reservation = new StubServer(0)) {
            port = reservation.port();
        }
        Process server = null;
        try {
            server = startServerProcess(port, directory);
            try (ChatConnection connection = new ChatConnection("127.0.0.1", port, events)) {
                connection.start();
                events.await("ready");
                check(connection.authenticate(true, "CrashTester", PASSWORD.toCharArray()), "Crash-test signup unavailable");
                events.await("authenticated:CrashTester");
                check(connection.sendMessage("saved before crash"), "Initial message failed");
                events.await("users:CrashTester");
                events.await("chat:live:CrashTester: saved before crash");
                long originalTimestamp = events.chatMessages.get(0).timestampMillis();
                server.destroyForcibly(); // No orderly ChatServer.close or shutdown-hook save.
                check(server.waitFor(5, TimeUnit.SECONDS), "Crashed server process did not exit");
                events.awaitPrefix("disconnected:");
                server = startServerProcess(port, directory);
                events.await("authenticated:CrashTester");
                events.await("chat:history:CrashTester: saved before crash");
                events.await("users:CrashTester");
                check(originalTimestamp > 0 && events.chatMessages.get(1).timestampMillis() == originalTimestamp,
                        "Crash recovery changed the saved message timestamp");
                check(connection.sendMessage("restored after crash"), "Automatically restored session cannot send");
                events.await("chat:live:CrashTester: restored after crash");
                check(events.recorded.stream().filter(value -> value.equals("ready")).count() == 1,
                        "Restart asked the already authenticated user to log in again");
                check(events.recorded.stream().noneMatch(value -> value.contains("@session") || value.contains("@resume")),
                        "Private session frames reached the UI");
                events.checkThread();
            }
        } finally {
            if (server != null && server.isAlive()) {
                server.destroyForcibly();
                check(server.waitFor(5, TimeUnit.SECONDS), "Test server did not stop");
            }
            try (Stream<Path> paths = Files.walk(directory)) {
                for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) {
                    Files.delete(path);
                }
            }
        }
    }

    private static Process startServerProcess(int port, Path directory) throws Exception {
        Path output = directory.resolve("process.log");
        Process process = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), ChatServer.class.getName(), Integer.toString(port),
                "127.0.0.1", directory.toString())
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (process.isAlive() && System.nanoTime() < deadline) {
            if (Files.readString(output).contains("Chat server listening on")) {
                return process;
            }
            Thread.sleep(10);
        }
        process.destroyForcibly();
        process.waitFor(5, TimeUnit.SECONDS);
        throw new AssertionError("Server process did not start: " + Files.readString(output));
    }

    private static void login(ChatConnection connection, Peer peer, Events events,
                              boolean signup, String username) throws Exception {
        peer.handshake();
        events.await("ready");
        check(connection.authenticate(signup, username, PASSWORD.toCharArray()), "Auth was not accepted");
        checkAuth(peer.read(), signup, username, PASSWORD);
        peer.authenticated(username);
        events.await("authenticated:" + username);
    }

    private static void checkAuth(String line, boolean signup, String username, String password) throws IOException {
        check(line != null, "Connection closed before authentication");
        String[] fields = line.split("\t", -1);
        check(fields.length == 4 && fields[0].equals("@auth")
                && fields[1].equals(signup ? "signup" : "login"), "Malformed auth frame");
        check(username.equals(ChatProtocol.decodeField(fields[2])), "Username changed in transit");
        check(password.equals(ChatProtocol.decodeField(fields[3])), "Password changed or caller buffer was retained");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    @FunctionalInterface
    private interface CheckedRunnable {
        void run() throws Exception;
    }

    private static final class Events implements ChatConnection.Listener {
        final BlockingQueue<String> pending = new LinkedBlockingQueue<>();
        final List<String> recorded = new CopyOnWriteArrayList<>();
        final List<ChatProtocol.ChatMessage> chatMessages = new CopyOnWriteArrayList<>();
        final List<Boolean> historicalFlags = new CopyOnWriteArrayList<>();
        final List<List<String>> userSnapshots = new CopyOnWriteArrayList<>();
        final AtomicReference<String> threadError = new AtomicReference<>();

        private void add(String event) {
            if (!SwingUtilities.isEventDispatchThread()) {
                threadError.compareAndSet(null, "Callback did not run on EDT: " + event);
            }
            recorded.add(event);
            pending.add(event);
        }

        public void onStatus(String text) { add("status:" + text); }
        public void onReady() { add("ready"); }
        public void onAuthenticated(String username) { add("authenticated:" + username); }
        public void onAuthenticationError(String error) { add("error:" + error); }
        public void onMessage(String line) { add("message:" + line); }
        public void onChatMessage(ChatProtocol.ChatMessage message, boolean historical) {
            chatMessages.add(message);
            historicalFlags.add(historical);
            add("chat:" + (historical ? "history:" : "live:") + message.username() + ": " + message.text());
        }
        public void onOnlineUsers(List<String> users) {
            userSnapshots.add(users);
            add("users:" + String.join(",", users));
        }
        public void onDisconnected(String reason) { add("disconnected:" + reason); }

        void await(String expected) throws Exception {
            awaitMatching(expected, false);
        }

        void awaitPrefix(String expected) throws Exception {
            awaitMatching(expected, true);
        }

        private void awaitMatching(String expected, boolean prefix) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (true) {
                String next = pending.poll(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                check(next != null, "Timed out waiting for " + expected + "; received " + recorded);
                if (prefix ? next.startsWith(expected) : next.equals(expected)) {
                    return;
                }
                check(!next.startsWith("message:") && !next.startsWith("chat:"),
                        "Unexpected or out-of-order message: " + next);
            }
        }

        void checkThread() {
            check(threadError.get() == null, threadError.get());
        }
    }

    private static final class StubServer implements Closeable {
        private final ServerSocket socket = new ServerSocket();
        private final List<Peer> peers = new ArrayList<>();

        StubServer(int port) throws IOException {
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port));
            socket.setSoTimeout(10_000);
        }

        int port() { return socket.getLocalPort(); }

        Peer accept() throws IOException {
            Peer peer = new Peer(socket.accept());
            peers.add(peer);
            return peer;
        }

        void noConnection() throws IOException {
            socket.setSoTimeout(4_000);
            try (Socket unexpected = socket.accept()) {
                throw new AssertionError("Closed client retried its connection to " + unexpected.getRemoteSocketAddress());
            } catch (SocketTimeoutException expected) {
                // The retry deadline passed with no connection.
            } finally {
                socket.setSoTimeout(10_000);
            }
        }

        @Override
        public void close() throws IOException {
            for (Peer peer : peers) {
                peer.close();
            }
            socket.close();
        }
    }

    private static final class Peer implements Closeable {
        private final Socket socket;
        private final BufferedReader input;
        private final BufferedWriter output;

        Peer(Socket socket) throws IOException {
            this.socket = socket;
            socket.setSoTimeout(5_000);
            input = ChatProtocol.newUtf8Reader(socket.getInputStream());
            output = ChatProtocol.newUtf8Writer(socket.getOutputStream());
        }

        void handshake() throws IOException {
            send("Welcome to Socket Chat!");
            send(ChatProtocol.CHOICE_PROMPT);
            check(ChatProtocol.FORM_SESSION_HELLO.equals(read()), "Client did not request session-aware forms protocol");
            send(ChatProtocol.FORM_READY);
        }

        void authenticated(String username) throws IOException {
            send(ChatProtocol.FORM_OK_PREFIX + username);
            send(ChatProtocol.HISTORY_START);
            send(ChatProtocol.HISTORY_END);
        }

        String read() throws IOException { return ChatProtocol.readLine(input); }

        void send(String line) throws IOException {
            output.write(line);
            output.newLine();
            output.flush();
        }

        void noInput() throws IOException {
            socket.setSoTimeout(350);
            try {
                String unexpected = read();
                throw new AssertionError("Credentials were replayed or connection closed: " + unexpected);
            } catch (SocketTimeoutException expected) {
                // No credentials, messages, or unexpected retries were sent.
            } finally {
                socket.setSoTimeout(5_000);
            }
        }

        @Override
        public void close() throws IOException { socket.close(); }
    }
}
