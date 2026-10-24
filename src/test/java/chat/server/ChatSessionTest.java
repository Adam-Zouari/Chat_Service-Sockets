package chat.server;

import chat.protocol.ChatProtocol;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.stream.Stream;

/** Real-socket regressions for automatic reconnection without retaining a password. */
public final class ChatSessionTest {
    private static final String PASSWORD = "SessionPassword9!";
    private static final String HELLO = "@forms2";
    private static final String SESSION_PREFIX = "@session\t";
    private static final String RESUME_PREFIX = "@resume\t";
    private static final long TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(10);
    private static final long SESSION_LIFETIME_MILLIS = TimeUnit.DAYS.toMillis(7);
    private static int passed;

    private ChatSessionTest() { }

    public static void main(String[] args) throws Exception {
        if (args.length == 2 && "--crash-server".equals(args[0])) {
            crashServer(Paths.get(args[1]));
            return;
        }
        run("signup issues a bounded opaque token and stores only its hash", ChatSessionTest::tokenIssuance);
        run("unexpected disconnect resumes identity, history, and chat", ChatSessionTest::disconnectResume);
        run("an open session survives a complete server restart", ChatSessionTest::persistentRestart);
        run("acknowledged sessions survive an abrupt JVM crash", ChatSessionTest::processCrash);
        run("explicit logout revokes the token durably", ChatSessionTest::logoutRevocation);
        run("invalid resume requests leave both account owners connected", ChatSessionTest::invalidResume);
        run("expired sessions fall back to usable password login", ChatSessionTest::expiredResume);
        run("password login rotates the previous resume credential", ChatSessionTest::passwordRotation);
        run("resume takeover closes stale sockets without losing the new owner", ChatSessionTest::ownershipTakeover);
        run("legacy forms and interactive authentication remain compatible", ChatSessionTest::legacyCompatibility);
        System.out.println("Passed " + passed + " session integration tests.");
    }

    private static void run(String name, DirectoryTest test) throws Exception {
        Path directory = Files.createTempDirectory("chat-session-test-");
        try {
            test.run(directory);
            passed++;
            System.out.println("PASS " + name);
        } catch (Throwable failure) {
            System.err.println("FAIL " + name);
            throw failure;
        } finally {
            deleteDirectory(directory);
        }
    }

    private static void tokenIssuance(Path directory) throws Exception {
        try (Fixture fixture = new Fixture(directory); Peer user = fixture.formPeer()) {
            long before = System.currentTimeMillis();
            submit(user, "signup", "TokenUser", PASSWORD);
            Session session = authenticated(user, "TokenUser");
            check(!session.token.contains(PASSWORD), "Resume credential contains the password");
            check(session.expiresAt >= before + SESSION_LIFETIME_MILLIS - 1000,
                    "New session expires before its seven-day lifetime");
            check(session.expiresAt <= System.currentTimeMillis() + SESSION_LIFETIME_MILLIS + 1000,
                    "Session lifetime is not bounded to seven days");
            byte[] stored = Files.readAllBytes(directory.resolve("sessions.bin"));
            check(!new String(stored, StandardCharsets.ISO_8859_1).contains(session.token),
                    "Plaintext bearer credential was written to disk");
            check(!new String(stored, StandardCharsets.ISO_8859_1).contains(PASSWORD),
                    "Password was written to the session store");
            check(stored.length > 32, "Session snapshot was not persisted before acknowledgement");
        }
    }

    private static void disconnectResume(Path directory) throws Exception {
        try (Fixture fixture = new Fixture(directory)) {
            Session session;
            try (Peer original = fixture.formPeer()) {
                submit(original, "signup", "ReconnectUser", PASSWORD);
                session = authenticated(original, "ReconnectUser");
                original.send("before disconnect");
                original.expect("ReconnectUser: before disconnect");
            }
            try (Peer resumed = fixture.formPeer()) {
                resume(resumed, "ReconnectUser", session.token);
                Session continued = authenticated(resumed, "ReconnectUser");
                check(session.token.equals(continued.token), "Resume unexpectedly rotated the retained token");
                check(session.expiresAt == continued.expiresAt, "Resume extended the absolute expiry");
                resumed.expect("ReconnectUser: before disconnect");
                resumed.send("after reconnect");
                resumed.expect("ReconnectUser: after reconnect");
            }
        }
    }

    private static void persistentRestart(Path directory) throws Exception {
        Session session;
        Fixture first = new Fixture(directory);
        try (Peer original = first.formPeer()) {
            submit(original, "signup", "RestartUser", PASSWORD);
            session = authenticated(original, "RestartUser");
            original.send("before restart");
            original.expect("RestartUser: before restart");
            first.close();
            original.awaitClosed();
        } finally {
            first.close();
        }
        try (Fixture restarted = new Fixture(directory); Peer resumed = restarted.formPeer()) {
            resume(resumed, "RestartUser", session.token);
            Session continued = authenticated(resumed, "RestartUser");
            check(session.expiresAt == continued.expiresAt, "Restart changed session expiry");
            resumed.expect("RestartUser: before restart");
            resumed.send("after restart");
            resumed.expect("RestartUser: after restart");
        }
    }

    private static void processCrash(Path directory) throws Exception {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        Path java = Paths.get(System.getProperty("java.home"), "bin", executable);
        Path output = directory.resolve("crash-process.log");
        Process process = new ProcessBuilder(java.toString(), "-cp", System.getProperty("java.class.path"),
                ChatSessionTest.class.getName(), "--crash-server", directory.toString())
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        Session session;
        try {
            int port = awaitChildPort(process, output);
            try (Peer original = new Peer(port)) {
                original.forms(HELLO);
                submit(original, "signup", "CrashUser", PASSWORD);
                session = authenticated(original, "CrashUser");
                original.send("durable before crash");
                original.expect("CrashUser: durable before crash");
                process.getOutputStream().write("crash\n".getBytes(StandardCharsets.US_ASCII));
                process.getOutputStream().flush();
                check(process.waitFor(10, TimeUnit.SECONDS), "Crash helper did not halt");
                check(process.exitValue() == 0, "Crash helper failed: " + Files.readString(output));
                original.awaitClosed();
            }
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                check(process.waitFor(10, TimeUnit.SECONDS), "Crash helper could not be stopped");
            }
        }
        try (Fixture restarted = new Fixture(directory); Peer resumed = restarted.formPeer()) {
            resume(resumed, "CrashUser", session.token);
            authenticated(resumed, "CrashUser");
            resumed.expect("CrashUser: durable before crash");
            resumed.send("after crash");
            resumed.expect("CrashUser: after crash");
        }
    }

    private static int awaitChildPort(Process process, Path output) throws Exception {
        long deadline = System.nanoTime() + TIMEOUT_NANOS;
        while (System.nanoTime() < deadline) {
            if (Files.exists(output)) {
                for (String line : Files.readAllLines(output, StandardCharsets.UTF_8)) {
                    if (line.startsWith("PORT\t")) {
                        return Integer.parseInt(line.substring("PORT\t".length()));
                    }
                }
            }
            check(process.isAlive(), "Crash helper exited before startup: " + Files.readString(output));
            Thread.sleep(10);
        }
        throw new AssertionError("Crash helper did not publish its port: " + Files.readString(output));
    }

    private static void crashServer(Path directory) throws Exception {
        Fixture fixture = new Fixture(directory);
        System.out.println("PORT\t" + fixture.server.getPort());
        System.out.flush();
        try (BufferedReader commands = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.US_ASCII))) {
            check("crash".equals(commands.readLine()), "Missing crash command");
        }
        // Deliberately skip close(), shutdown hooks, and all ordinary cleanup.
        Runtime.getRuntime().halt(0);
    }

    private static void logoutRevocation(Path directory) throws Exception {
        Session session;
        try (Fixture first = new Fixture(directory); Peer original = first.formPeer()) {
            submit(original, "signup", "LogoutUser", PASSWORD);
            session = authenticated(original, "LogoutUser");
            original.send(ChatProtocol.LOGOUT);
            original.expect("Goodbye.");
            original.awaitClosed();
        }
        try (Fixture restarted = new Fixture(directory); Peer retry = restarted.formPeer()) {
            resume(retry, "LogoutUser", session.token);
            error(retry);
            submit(retry, "login", "LogoutUser", PASSWORD);
            Session replacement = authenticated(retry, "LogoutUser");
            check(!session.token.equals(replacement.token), "Logout token was reused");
            retry.send("password login after logout");
            retry.expect("LogoutUser: password login after logout");
        }
    }

    private static void invalidResume(Path directory) throws Exception {
        try (Fixture fixture = new Fixture(directory); Peer owner = fixture.formPeer();
             Peer other = fixture.formPeer(); Peer retry = fixture.formPeer()) {
            submit(owner, "signup", "TokenOwner", PASSWORD);
            Session original = authenticated(owner, "TokenOwner");
            submit(other, "signup", "OtherOwner", PASSWORD);
            authenticated(other, "OtherOwner");
            owner.until(line -> line.startsWith("Online users:")
                    && line.contains("TokenOwner") && line.contains("OtherOwner"));
            other.until(line -> line.startsWith("Online users:")
                    && line.contains("TokenOwner") && line.contains("OtherOwner"));
            List<String> invalid = Arrays.asList("@resume", RESUME_PREFIX + "TokenOwner",
                    RESUME_PREFIX + "bad:name\t" + original.token,
                    RESUME_PREFIX + "TokenOwner\tshort",
                    RESUME_PREFIX + "TokenOwner\t" + tamper(original.token),
                    RESUME_PREFIX + "OtherOwner\t" + original.token,
                    RESUME_PREFIX + "MissingOwner\t" + original.token,
                    RESUME_PREFIX + "TokenOwner\t" + original.token + "\textra");
            for (String request : invalid) {
                retry.send(request);
                error(retry);
                owner.send(ChatProtocol.USERS_REQUEST);
                String users = owner.until(line -> line.startsWith("Online users:")).last();
                check(users.contains("TokenOwner") && users.contains("OtherOwner"),
                        "Invalid resume disrupted a legitimate account owner");
                other.send(ChatProtocol.USERS_REQUEST);
                other.until(line -> line.startsWith("Online users:"));
            }
            resume(retry, "TokenOwner", original.token);
            authenticated(retry, "TokenOwner");
            owner.awaitClosed();
            retry.send("valid retry");
            retry.expect("TokenOwner: valid retry");
        }
    }

    private static void expiredResume(Path directory) throws Exception {
        Session session;
        try (Fixture first = new Fixture(directory); Peer original = first.formPeer()) {
            submit(original, "signup", "ExpiredUser", PASSWORD);
            session = authenticated(original, "ExpiredUser");
        }
        try (ChatStore store = new ChatStore(directory)) {
            store.saveSession("ExpiredUser", sha256(session.token), System.currentTimeMillis() - 1000);
        }
        try (Fixture restarted = new Fixture(directory); Peer retry = restarted.formPeer()) {
            resume(retry, "ExpiredUser", session.token);
            error(retry);
            submit(retry, "login", "ExpiredUser", PASSWORD);
            Session replacement = authenticated(retry, "ExpiredUser");
            check(!session.token.equals(replacement.token), "Password login reused an expired token");
            retry.send("recovered from expiry");
            retry.expect("ExpiredUser: recovered from expiry");
        }
    }

    private static void passwordRotation(Path directory) throws Exception {
        try (Fixture fixture = new Fixture(directory); Peer observer = fixture.formPeer()) {
            submit(observer, "signup", "RotationObserver", PASSWORD);
            authenticated(observer, "RotationObserver");
            Session old;
            try (Peer original = fixture.formPeer()) {
                submit(original, "signup", "RotateUser", PASSWORD);
                old = authenticated(original, "RotateUser");
            }
            awaitOffline(observer, "RotateUser");
            Session replacement;
            try (Peer login = fixture.formPeer()) {
                submit(login, "login", "RotateUser", PASSWORD);
                replacement = authenticated(login, "RotateUser");
                check(!old.token.equals(replacement.token), "Password login did not rotate the token");
            }
            awaitOffline(observer, "RotateUser");
            try (Peer retry = fixture.formPeer()) {
                resume(retry, "RotateUser", old.token);
                error(retry);
                resume(retry, "RotateUser", replacement.token);
                authenticated(retry, "RotateUser");
                retry.send("rotated token works");
                retry.expect("RotateUser: rotated token works");
            }
        }
    }

    private static void ownershipTakeover(Path directory) throws Exception {
        try (Fixture fixture = new Fixture(directory); Peer observer = fixture.formPeer()) {
            submit(observer, "signup", "TakeoverObserver", PASSWORD);
            authenticated(observer, "TakeoverObserver");
            Peer current = fixture.formPeer();
            try {
                submit(current, "signup", "TakeoverUser", PASSWORD);
                Session session = authenticated(current, "TakeoverUser");
                for (int attempt = 0; attempt < 5; attempt++) {
                    Peer next = fixture.formPeer();
                    try {
                        resume(next, "TakeoverUser", session.token);
                        authenticated(next, "TakeoverUser");
                        current.awaitClosed();
                        try {
                            current.send("stale message " + attempt);
                        } catch (IOException expected) {
                            // A closed transport may reject the stale write immediately.
                        }
                        next.send(ChatProtocol.USERS_REQUEST);
                        Seen received = next.until(line -> line.startsWith("Online users:"));
                        check(received.last().contains("TakeoverUser"), "Old cleanup removed the new owner");
                        check(received.lines.stream().noneMatch(line -> line.startsWith("TakeoverUser: stale message")),
                                "An old socket published a message after takeover");
                        String message = "TakeoverUser: after takeover " + attempt;
                        next.send("after takeover " + attempt);
                        next.expect(message);
                        observer.expect(message);
                    } catch (Throwable failure) {
                        next.close();
                        throw failure;
                    }
                    current.close();
                    current = next;
                }
            } finally {
                current.close();
            }
        }
    }

    private static void legacyCompatibility(Path directory) throws Exception {
        try (Fixture fixture = new Fixture(directory)) {
            Session session;
            try (Peer modern = fixture.formPeer()) {
                submit(modern, "signup", "LegacyUser", PASSWORD);
                session = authenticated(modern, "LegacyUser");
            }
            try (Peer legacy = fixture.peer()) {
                legacy.forms(ChatProtocol.FORM_HELLO);
                resume(legacy, "LegacyUser", session.token);
                error(legacy);
                submit(legacy, "login", "LegacyUser", PASSWORD);
                Seen authentication = legacy.expect(ChatProtocol.FORM_OK_PREFIX + "LegacyUser");
                check(authentication.lines.stream().noneMatch(line -> line.startsWith(SESSION_PREFIX)),
                        "Legacy forms received an unsupported session frame");
                legacy.send(ChatProtocol.LOGOUT);
                legacy.expect("Goodbye.");
                legacy.awaitClosed();
            }
            try (Peer interactive = fixture.peer()) {
                interactive.send("login");
                interactive.expect(ChatProtocol.USERNAME_PROMPT);
                interactive.send("LegacyUser");
                interactive.expect(ChatProtocol.PASSWORD_PROMPT);
                interactive.send(PASSWORD);
                Seen authentication = interactive.until(line -> line.contains("LegacyUser") && line.contains("Welcome"));
                check(authentication.lines.stream().noneMatch(line -> line.startsWith(SESSION_PREFIX)),
                        "Interactive clients received an unsupported session frame");
                interactive.send("legacy client can still chat");
                interactive.expect("LegacyUser: legacy client can still chat");
            }
        }
    }

    private static void submit(Peer peer, String mode, String username, String password) throws IOException {
        peer.send(ChatProtocol.FORM_AUTH_PREFIX + mode + "\t" + ChatProtocol.encodeField(username)
                + "\t" + ChatProtocol.encodeField(password));
    }

    private static void resume(Peer peer, String username, String token) throws IOException {
        peer.send(RESUME_PREFIX + username + "\t" + token);
    }

    private static Session authenticated(Peer peer, String username) throws Exception {
        String metadata = peer.next();
        check(metadata.startsWith(SESSION_PREFIX), "Expected a session before authentication success, got: " + metadata);
        String[] fields = metadata.split("\t", -1);
        check(fields.length == 3 && fields[1].matches("[A-Za-z0-9_-]{43}"), "Invalid opaque session format");
        long expiresAt = Long.parseLong(fields[2]);
        check(expiresAt > System.currentTimeMillis(), "Server acknowledged an expired session");
        check((ChatProtocol.FORM_OK_PREFIX + username).equals(peer.next()),
                "Authentication success was not sent directly after session metadata");
        return new Session(fields[1], expiresAt);
    }

    private static void error(Peer peer) throws Exception {
        String response = peer.next();
        check(response.startsWith(ChatProtocol.FORM_ERROR_PREFIX), "Expected a form error, got: " + response);
    }

    private static String tamper(String token) {
        return (token.charAt(0) == 'A' ? "B" : "A") + token.substring(1);
    }

    private static byte[] sha256(String token) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
    }

    private static void awaitOffline(Peer observer, String username) throws Exception {
        long deadline = System.nanoTime() + TIMEOUT_NANOS;
        do {
            observer.send(ChatProtocol.USERS_REQUEST);
            String users = observer.until(line -> line.startsWith("Online users:")).last();
            List<String> names = Arrays.asList(users.substring("Online users:".length()).trim().split("[, ]+"));
            if (!names.contains(username)) {
                return;
            }
            Thread.sleep(1);
        } while (System.nanoTime() < deadline);
        throw new AssertionError(username + " remained online after disconnect");
    }

    private static void deleteDirectory(Path directory) throws IOException {
        Path root = directory.toAbsolutePath().normalize();
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) {
                check(path.toAbsolutePath().normalize().startsWith(root), "Cleanup escaped the test directory");
                Files.delete(path);
            }
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    @FunctionalInterface
    private interface DirectoryTest {
        void run(Path directory) throws Exception;
    }

    private static final class Session {
        final String token;
        final long expiresAt;

        Session(String token, long expiresAt) {
            this.token = token;
            this.expiresAt = expiresAt;
        }
    }

    private static final class Fixture implements Closeable {
        final ChatServer server;
        final Thread thread;
        final AtomicReference<Throwable> failure = new AtomicReference<>();

        Fixture(Path directory) throws Exception {
            server = new ChatServer(InetAddress.getLoopbackAddress(), 0, directory);
            thread = new Thread(() -> {
                try {
                    server.start();
                } catch (Throwable problem) {
                    failure.set(problem);
                }
            }, "session-test-server");
            thread.setDaemon(true);
            thread.start();
            long deadline = System.nanoTime() + TIMEOUT_NANOS;
            while (server.getPort() <= 0 && failure.get() == null && System.nanoTime() < deadline) {
                Thread.sleep(1);
            }
            check(failure.get() == null, "Server startup failed: " + failure.get());
            check(server.getPort() > 0, "Server did not bind an ephemeral port");
        }

        Peer peer() throws Exception {
            return new Peer(server.getPort());
        }

        Peer formPeer() throws Exception {
            Peer peer = peer();
            try {
                peer.forms(HELLO);
                return peer;
            } catch (Throwable failure) {
                peer.close();
                throw failure;
            }
        }

        @Override
        public void close() throws IOException {
            server.close();
            try {
                thread.join(10000);
            } catch (InterruptedException problem) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while closing test server", problem);
            }
            check(!thread.isAlive(), "Server accept thread survived shutdown");
            check(failure.get() == null, "Server failed: " + failure.get());
        }
    }

    private static final class Seen {
        final List<String> lines;

        Seen(List<String> lines) {
            this.lines = lines;
        }

        String last() {
            return lines.get(lines.size() - 1);
        }
    }

    private static final class Peer implements Closeable {
        private static final Object EOF = new Object();
        final Socket socket;
        final PrintWriter writer;
        final BlockingQueue<Object> inbox = new LinkedBlockingQueue<>();
        final Thread reader;

        Peer(int port) throws Exception {
            socket = new Socket(InetAddress.getLoopbackAddress(), port);
            socket.setTcpNoDelay(true);
            writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
            reader = new Thread(() -> {
                try (BufferedReader input = ChatProtocol.newUtf8Reader(socket.getInputStream())) {
                    String line;
                    while ((line = ChatProtocol.readLine(input)) != null) {
                        inbox.add(line);
                    }
                } catch (IOException problem) {
                    if (!socket.isClosed()) {
                        inbox.add(problem);
                    }
                } finally {
                    inbox.add(EOF);
                }
            }, "session-test-reader");
            reader.setDaemon(true);
            reader.start();
            expect(ChatProtocol.CHOICE_PROMPT);
        }

        void forms(String hello) throws Exception {
            send(hello);
            expect(ChatProtocol.FORM_READY);
        }

        void send(String line) throws IOException {
            writer.println(line);
            if (writer.checkError()) {
                throw new IOException("Could not send to chat server");
            }
        }

        String next() throws Exception {
            Object value = inbox.poll(10, TimeUnit.SECONDS);
            check(value != null, "Timed out waiting for server response");
            check(value != EOF, "Connection closed while waiting for server response");
            if (value instanceof IOException) {
                throw (IOException) value;
            }
            return (String) value;
        }

        Seen expect(String expected) throws Exception {
            return until(expected::equals);
        }

        Seen until(Predicate<String> match) throws Exception {
            List<String> seen = new ArrayList<>();
            long deadline = System.nanoTime() + TIMEOUT_NANOS;
            while (true) {
                Object value = inbox.poll(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                check(value != null && value != EOF,
                        "Server response missing or connection closed; received " + seen);
                if (value instanceof IOException) {
                    throw (IOException) value;
                }
                String line = (String) value;
                if (line.equals(ChatProtocol.HISTORY_START) || line.equals(ChatProtocol.HISTORY_END)) {
                    continue;
                }
                if (line.startsWith(ChatProtocol.MESSAGE_PREFIX)) {
                    ChatProtocol.ChatMessage entry = ChatProtocol.parseMessage(line);
                    line = entry.username() + ": " + entry.text();
                } else if (line.equals("@users") || line.startsWith(ChatProtocol.USERS_PREFIX)) {
                    line = "Online users: " + (line.equals("@users") ? ""
                            : line.substring(ChatProtocol.USERS_PREFIX.length()).replace("\t", ", "));
                }
                seen.add(line);
                if (match.test(line)) {
                    return new Seen(seen);
                }
            }
        }

        void awaitClosed() throws Exception {
            long deadline = System.nanoTime() + TIMEOUT_NANOS;
            while (true) {
                Object value = inbox.poll(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                if (value == EOF) {
                    return;
                }
                check(value != null, "Connection did not close");
            }
        }

        @Override
        public void close() throws IOException {
            socket.close();
            try {
                reader.join(1000);
            } catch (InterruptedException problem) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while closing test peer", problem);
            }
        }
    }
}
