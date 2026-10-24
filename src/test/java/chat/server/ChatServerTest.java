package chat.server;

import chat.protocol.ChatProtocol;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.stream.Stream;

/** Dependency-free integration tests. Run with assertions enabled or disabled. */
public final class ChatServerTest {
    private static final String PASSWORD = "SecurePassword9!";
    private static final String USERNAME_PROMPT = "Enter your username:";
    private static final String PASSWORD_PROMPT = "Enter your password:";
    private static final String USERS_PREFIX = "Online users:";
    private static final long TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(10);
    private static int passed;

    private ChatServerTest() { }

    public static void main(String[] args) throws Exception {
        run("bounded line framing and strict UTF-8 decoding", ChatServerTest::protocolFraming);
        run("UTF-8 chat, literal slash messages, and reconnect", ChatServerTest::chatAndCommands);
        run("timestamp framing and legacy stored history preserve message bodies", ChatServerTest::messageFraming);
        run("timestamps survive restart and legacy history stays readable", ChatServerTest::timestampPersistence);
        run("live presence is sorted, private before login, and updates without polling", ChatServerTest::livePresence);
        run("structured chat cannot inject logout or presence controls", ChatServerTest::literalControlMessages);
        run("username validation on every retry", ChatServerTest::usernameValidation);
        run("password validation and abandoned registrations", ChatServerTest::passwordValidation);
        run("EOF during every authentication phase", ChatServerTest::authenticationEof);
        run("authentication mode switches do not reserve accounts", ChatServerTest::modeSwitches);
        run("password retries are bounded per connection", ChatServerTest::passwordRetries);
        run("failed login cannot disconnect the account owner", ChatServerTest::ownershipCleanup);
        run("simultaneous signup is atomic", ChatServerTest::signupRace);
        run("simultaneous login is atomic", ChatServerTest::loginRace);
        run("history and live messages arrive once, in order", ChatServerTest::historyOrdering);
        run("history retains exactly the latest 500 chat messages", ChatServerTest::boundedHistory);
        run("blank, control, and oversized input cannot pollute chat", ChatServerTest::invalidChatInput);
        run("connection capacity is bounded and released", ChatServerTest::connectionCapacity);
        run("a stalled reader cannot block healthy chat clients", ChatServerTest::slowReaderIsolation);
        run("forms support validation errors and complete authentication retries", ChatServerTest::formAuthentication);
        run("accounts and history survive a complete server restart", ChatServerTest::persistentRestart);
        run("storage failures never publish unconfirmed accounts or messages", ChatServerTest::storageFailure);
        run("server shutdown closes connections and its accept loop", ChatServerTest::shutdown);
        System.out.println("Passed " + passed + " integration tests.");
    }

    private static void run(String name, CheckedRunnable test) throws Exception {
        try {
            test.run();
            passed++;
            System.out.println("PASS " + name);
        } catch (Throwable failure) {
            System.err.println("FAIL " + name);
            throw failure;
        }
    }

    private static Peer formPeer(Fixture fixture) throws Exception {
        Peer peer = fixture.peer();
        peer.send(ChatProtocol.FORM_HELLO);
        peer.expect(ChatProtocol.FORM_READY);
        return peer;
    }

    private static void submitForm(Peer peer, String mode, String name, String password) throws IOException {
        peer.send(ChatProtocol.FORM_AUTH_PREFIX + mode + "\t" + ChatProtocol.encodeField(name)
                + "\t" + ChatProtocol.encodeField(password));
    }

    private static void formAuthentication() throws Exception {
        try (Fixture fixture = new Fixture(); Peer form = formPeer(fixture)) {
            form.send("@auth\tlogin\t%%%\t%%%");
            form.until(line -> line.startsWith(ChatProtocol.FORM_ERROR_PREFIX));
            submitForm(form, "signup", "bad:name", PASSWORD);
            form.until(line -> line.startsWith(ChatProtocol.FORM_ERROR_PREFIX));
            submitForm(form, "signup", "FormsUser", "weak");
            form.until(line -> line.startsWith(ChatProtocol.FORM_ERROR_PREFIX));
            submitForm(form, "signup", "FormsUser", PASSWORD);
            form.expect(ChatProtocol.FORM_OK_PREFIX + "FormsUser");
            form.send("a form user can chat");
            form.expect("FormsUser: a form user can chat");
            form.send(ChatProtocol.LOGOUT);
            form.awaitClosed();
            try (Peer login = formPeer(fixture)) {
                submitForm(login, "login", "FormsUser", "WrongPassword9!");
                login.until(line -> line.startsWith(ChatProtocol.FORM_ERROR_PREFIX));
                submitForm(login, "login", "FormsUser", PASSWORD);
                login.expect(ChatProtocol.FORM_OK_PREFIX + "FormsUser");
                login.send("/help");
                login.expect("FormsUser: /help");
            }
        }
    }

    private static void persistentRestart() throws Exception {
        Path directory = Files.createTempDirectory("chat-restart-");
        String message = "PersistentUser: saved across restart, مرحبا 👋";
        try {
            try (Fixture first = new Fixture(0, directory); Peer user = formPeer(first)) {
                submitForm(user, "signup", "PersistentUser", PASSWORD);
                user.expect(ChatProtocol.FORM_OK_PREFIX + "PersistentUser");
                user.send(message.substring("PersistentUser: ".length()));
                user.expect(message);
            }
            try (Fixture restarted = new Fixture(0, directory); Peer user = formPeer(restarted)) {
                submitForm(user, "signup", "PersistentUser", PASSWORD);
                user.until(line -> line.startsWith(ChatProtocol.FORM_ERROR_PREFIX));
                submitForm(user, "login", "PersistentUser", PASSWORD);
                user.expect(ChatProtocol.FORM_OK_PREFIX + "PersistentUser");
                user.expect(message);
                user.send("new message after restart");
                user.expect("PersistentUser: new message after restart");
            }
            try (Fixture restartedAgain = new Fixture(0, directory); Peer user = restartedAgain.login("PersistentUser", PASSWORD)) {
                user.expect(message);
                user.expect("PersistentUser: new message after restart");
                user.send(ChatProtocol.USERS_REQUEST);
                user.until(line -> line.startsWith(USERS_PREFIX));
            }
            byte[] stored = Files.readAllBytes(directory.resolve("accounts.bin"));
            check(!new String(stored, StandardCharsets.ISO_8859_1).contains(PASSWORD),
                    "Password was stored as plaintext");
        } finally {
            deleteDirectory(directory);
        }
    }

    private static void storageFailure() throws Exception {
        Path directory = Files.createTempDirectory("chat-storage-failure-");
        try {
            try (Fixture fixture = new Fixture(0, directory); Peer owner = fixture.signup("Owner")) {
                owner.send("already committed");
                owner.expect("Owner: already committed");
                // Block account snapshot replacement without changing committed files.
                Files.createDirectory(directory.resolve("accounts.bin.tmp"));
                try (Peer signup = formPeer(fixture)) {
                    submitForm(signup, "signup", "Unconfirmed", PASSWORD);
                    signup.until(line -> line.startsWith(ChatProtocol.FORM_ERROR_PREFIX)
                            && line.contains("storage is unavailable"));
                }
                owner.send("unconfirmed message");
                owner.until(line -> line.contains("storage is unavailable"));
                owner.send(ChatProtocol.USERS_REQUEST);
                String names = owner.until(line -> line.startsWith(USERS_PREFIX)).last();
                check(!names.contains("Unconfirmed"), "Unconfirmed signup became a session");
            }
            Files.delete(directory.resolve("accounts.bin.tmp"));
            try (Fixture restored = new Fixture(0, directory); Peer owner = restored.login("Owner", PASSWORD)) {
                owner.send(ChatProtocol.USERS_REQUEST);
                List<String> replay = owner.until(line -> line.startsWith(USERS_PREFIX)).lines;
                check(replay.contains("Owner: already committed"), "Committed message was lost");
                check(!replay.contains("Owner: unconfirmed message"), "Failed message was broadcast/persisted");
                try (Peer newcomer = formPeer(restored)) {
                    submitForm(newcomer, "signup", "Unconfirmed", PASSWORD);
                    newcomer.expect(ChatProtocol.FORM_OK_PREFIX + "Unconfirmed");
                }
            }
        } finally {
            deleteDirectory(directory);
        }
    }

    private static void deleteDirectory(Path directory) throws IOException {
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) {
                Files.delete(path);
            }
        }
    }

    private static void protocolFraming() throws Exception {
        StringReader input = new StringReader("\nfirst\r\nsecond\nlast");
        check("".equals(ChatProtocol.readLine(input)), "Empty line was lost");
        check("first".equals(ChatProtocol.readLine(input)), "CRLF framing failed");
        check("second".equals(ChatProtocol.readLine(input)), "LF framing failed");
        check("last".equals(ChatProtocol.readLine(input)), "Final unterminated line was lost");
        check(ChatProtocol.readLine(input) == null, "Expected EOF after final line");
        String maximum = "x".repeat(ChatProtocol.MAX_LINE_LENGTH);
        check(maximum.equals(ChatProtocol.readLine(new StringReader(maximum + "\n"))),
                "Maximum-length framed line was rejected");
        check(maximum.equals(ChatProtocol.readLine(new StringReader(maximum))),
                "Maximum-length EOF line was rejected");
        expectIOException(() -> ChatProtocol.readLine(new StringReader(maximum + "x\n")));
        expectIOException(() -> ChatProtocol.readLine(new StringReader("invalid\rframing\n")));
        expectIOException(() -> ChatProtocol.readLine(new StringReader("invalid\r")));
        byte[] valid = "café \uD83D\uDC4B\n".getBytes(StandardCharsets.UTF_8);
        try (BufferedReader decoded = ChatProtocol.newUtf8Reader(new ByteArrayInputStream(valid))) {
            check("café \uD83D\uDC4B".equals(ChatProtocol.readLine(decoded)), "UTF-8 decoder corrupted text");
        }
        for (byte[] malformed : new byte[][] {
                {(byte) 0xc3, (byte) 0x28, '\n'},
                {(byte) 0xed, (byte) 0xa0, (byte) 0x80, '\n'},
                {(byte) 0xf0, (byte) 0x9f}}) {
            try (BufferedReader decoded = ChatProtocol.newUtf8Reader(new ByteArrayInputStream(malformed))) {
                expectIOException(() -> ChatProtocol.readLine(decoded));
            }
        }
        check(ChatProtocol.isAllowedText("café \uD83D\uDC4B"), "Valid Unicode text was rejected");
        for (String invalid : Arrays.asList("nul\u0000", "escape\u001b", "line\u2028separator",
                "paragraph\u2029separator", "\uD83D", "\uDC4B")) {
            check(!ChatProtocol.isAllowedText(invalid), "Unsafe text was accepted");
        }
    }

    private static void expectIOException(CheckedRunnable operation) throws Exception {
        try {
            operation.run();
        } catch (IOException expected) {
            return;
        }
        throw new AssertionError("Expected invalid input to throw IOException");
    }

    private static void messageFraming() throws Exception {
        String text = "  caf\u00e9: hello \uD83D\uDC4B : preserved spaces  ";
        ChatProtocol.ChatMessage original = new ChatProtocol.ChatMessage(1_789_000_123_456L, "Alice", text);
        for (ChatProtocol.ChatMessage decoded : Arrays.asList(
                ChatProtocol.parseMessage(ChatProtocol.encodeMessage(original)),
                ChatProtocol.parseStoredMessage(ChatProtocol.encodeStoredMessage(original)))) {
            check(decoded.timestampMillis() == original.timestampMillis(), "Timestamp changed in a round trip");
            check(decoded.username().equals("Alice") && decoded.text().equals(text),
                    "Message sender, Unicode, punctuation, or whitespace changed");
        }
        ChatProtocol.ChatMessage legacy = ChatProtocol.parseStoredMessage("Alice: " + text);
        check(legacy.timestampMillis() == 0 && legacy.text().equals(text), "Legacy history was not preserved");
        for (String invalid : Arrays.asList("@message\t-1\tAlice\tbody", "@message\t+1\tAlice\tbody",
                "@message\t9999999999999999999\tAlice\tbody", "@message\t1\tbad:name\tbody",
                "@message\t1\tAlice\t ", "@message\t1\tAlice\tbad\tcontrol",
                "@message\t1\tAlice\t" + "x".repeat(2001), "Alice: body")) {
            expectIOException(() -> ChatProtocol.parseMessage(invalid));
        }
        for (String invalid : Arrays.asList("malformed", "bad:name: body", "Alice: ",
                "@message -1 Alice body", "@message 1 Alice bad\tcontrol")) {
            expectIOException(() -> ChatProtocol.parseStoredMessage(invalid));
        }
    }

    private static void timestampPersistence() throws Exception {
        Path directory = Files.createTempDirectory("chat-timestamps-");
        String body = "Original timestamp: caf\u00e9 \uD83D\uDC4B  ";
        String frame;
        try {
            try (ChatStore legacy = new ChatStore(directory)) {
                legacy.appendMessage("Legacy: old body: still readable  ");
            }
            try (Fixture first = new Fixture(0, directory); Peer user = formPeer(first)) {
                submitForm(user, "signup", "TimeUser", PASSWORD);
                user.expect(ChatProtocol.FORM_OK_PREFIX + "TimeUser");
                Seen replay = user.untilFrame(ChatProtocol.HISTORY_END::equals);
                check(replay.lines.contains("@message\t0\tLegacy\told body: still readable  "),
                        "Legacy history did not retain an explicitly unknown timestamp");
                long before = System.currentTimeMillis();
                user.send(ChatProtocol.SEND_PREFIX + body);
                frame = user.untilFrame(line -> line.startsWith(ChatProtocol.MESSAGE_PREFIX)).last();
                ChatProtocol.ChatMessage message = ChatProtocol.parseMessage(frame);
                check(message.timestampMillis() >= before && message.timestampMillis() <= System.currentTimeMillis(),
                        "The server did not assign the send timestamp");
                check(message.text().equals(body), "Message body changed before storage");
            }
            try (Fixture restarted = new Fixture(0, directory); Peer user = formPeer(restarted)) {
                submitForm(user, "login", "TimeUser", PASSWORD);
                user.expect(ChatProtocol.FORM_OK_PREFIX + "TimeUser");
                Seen replay = user.untilFrame(ChatProtocol.HISTORY_END::equals);
                check(replay.lines.contains(frame), "The persisted timestamp changed during restart replay");
                check(replay.lines.contains("@message\t0\tLegacy\told body: still readable  "),
                        "Legacy history was lost after writing timestamped messages");
            }
        } finally {
            deleteDirectory(directory);
        }
    }

    private static void livePresence() throws Exception {
        try (Fixture fixture = new Fixture(); Peer alice = fixture.signup("Alice"); Peer unauthenticated = fixture.peer()) {
            alice.untilFrame((ChatProtocol.USERS_PREFIX + "Alice")::equals);
            try (Peer bob = fixture.signup("Bob")) {
                Seen snapshot = bob.untilFrame(line -> line.startsWith(ChatProtocol.USERS_PREFIX));
                check(snapshot.lines.indexOf(ChatProtocol.HISTORY_END) >= 0,
                        "Initial presence arrived before history replay finished");
                check(snapshot.last().equals(ChatProtocol.USERS_PREFIX + "Alice\tBob"),
                        "Initial presence was not sorted and complete");
                alice.untilFrame((ChatProtocol.USERS_PREFIX + "Alice\tBob")::equals);
                check(unauthenticated.inbox.poll(150, TimeUnit.MILLISECONDS) == null,
                        "An unauthenticated socket received private room presence");
                bob.send(ChatProtocol.LOGOUT);
                bob.awaitClosed();
                alice.untilFrame((ChatProtocol.USERS_PREFIX + "Alice")::equals);
            }
            Peer charlie = fixture.signup("Charlie");
            alice.untilFrame((ChatProtocol.USERS_PREFIX + "Alice\tCharlie")::equals);
            charlie.close();
            alice.untilFrame((ChatProtocol.USERS_PREFIX + "Alice")::equals);
        }
    }

    private static void literalControlMessages() throws Exception {
        try (Fixture fixture = new Fixture(); Peer sender = fixture.signup("Sender"); Peer observer = fixture.signup("Observer")) {
            for (String text : Arrays.asList("/help", "/users", "/quit", "@logout", "@who", "@history", "@users")) {
                sender.send(ChatProtocol.SEND_PREFIX + text);
                String frame = observer.untilFrame(line -> line.startsWith(ChatProtocol.MESSAGE_PREFIX)).last();
                ChatProtocol.ChatMessage message = ChatProtocol.parseMessage(frame);
                check(message.username().equals("Sender") && message.text().equals(text),
                        "Ordinary message text was interpreted as a command");
                sender.expect("Sender: " + text);
            }
            sender.send(ChatProtocol.SEND_PREFIX + "@users\tMallory");
            sender.until(line -> line.contains("no control characters"));
            sender.send(ChatProtocol.SEND_PREFIX + "still connected");
            Seen received = observer.until(line -> line.equals("Sender: still connected"));
            check(received.lines.stream().noneMatch(line -> line.contains("Mallory")),
                    "A malformed chat body injected a presence update");
        }
    }

    private static void chatAndCommands() throws Exception {
        try (Fixture fixture = new Fixture();
             Peer alice = fixture.signup("Alice");
             Peer bob = fixture.signup("Bob")) {
            String message = "Bonjour, مرحبا, café, 日本語, \uD83D\uDC4B!";
            alice.send(message);
            alice.expect("Alice: " + message);
            bob.expect("Alice: " + message);
            bob.send(ChatProtocol.USERS_REQUEST);
            String users = bob.until(line -> line.startsWith(USERS_PREFIX)).last();
            check(users.contains("Alice") && users.contains("Bob"), "Missing online users: " + users);
            bob.send("/help");
            bob.expect("Bob: /help");
            bob.send("/users");
            bob.expect("Bob: /users");
            bob.send("/quit");
            bob.expect("Bob: /quit");
            bob.send(ChatProtocol.LOGOUT);
            bob.awaitClosed();
            awaitOffline(alice, "Bob");
            try (Peer reconnected = fixture.login("Bob", PASSWORD)) {
                reconnected.send("back again");
                alice.expect("Bob: back again");
                reconnected.expect("Bob: back again");
                reconnected.socket.setSoLinger(true, 0);
                reconnected.socket.close();
            }
            awaitOffline(alice, "Bob");
            try (Peer afterReset = fixture.login("Bob", PASSWORD)) {
                afterReset.send("recovered after reset");
                alice.expect("Bob: recovered after reset");
            }
            try (Peer newcomer = fixture.signup("Carol")) {
                newcomer.send(ChatProtocol.USERS_REQUEST);
                List<String> history = newcomer.until(line -> line.startsWith(USERS_PREFIX)).lines;
                check(history.contains("Alice: " + message), "Newcomer missed UTF-8 history");
                check(history.contains("Bob: back again"), "Reconnect message missing from history");
                check(history.contains("Bob: /help") && history.contains("Bob: /users")
                        && history.contains("Bob: /quit"), "Literal slash messages were lost from history");
            }
            try (Peer boundary = fixture.peer()) {
                boundary.choose("signup");
                boundary.send("A".repeat(32));
                boundary.expect(PASSWORD_PROMPT);
                boundary.send(PASSWORD);
                boundary.connected("A".repeat(32));
            }
        }
    }

    private static void usernameValidation() throws Exception {
        try (Fixture fixture = new Fixture(); Peer existing = fixture.signup("Existing")) {
            for (String invalid : Arrays.asList("", "1start", "two words", "a-b", "Élodie",
                    "A".repeat(33), "signup")) {
                try (Peer candidate = fixture.peer()) {
                    candidate.choose("signup");
                    candidate.send(invalid);
                    candidate.expect(USERNAME_PROMPT);
                    candidate.send("Existing");
                    candidate.expect(USERNAME_PROMPT);
                    candidate.send("still-invalid!");
                    candidate.expect(USERNAME_PROMPT);
                    candidate.send("Valid_" + passed + "_" + Math.abs(invalid.hashCode()));
                    candidate.expect(PASSWORD_PROMPT);
                    // Closing here also exercises retries without creating an account.
                }
            }
            try (Peer candidate = fixture.peer()) {
                candidate.send("nonsense");
                candidate.until(line -> line.contains("(login/signup)"));
                candidate.choose("login");
                candidate.send("Missing");
                candidate.expect(USERNAME_PROMPT);
                candidate.send("not-valid!");
                candidate.expect(USERNAME_PROMPT);
            }
            existing.send("still present");
            existing.expect("Existing: still present");
        }
    }

    private static void passwordValidation() throws Exception {
        try (Fixture fixture = new Fixture()) {
            int index = 0;
            for (String invalid : Arrays.asList("Ab1!", "lowercase9!", "NoDigits!!", "NoSpecial99",
                    "Whitespace9 ", "Control9!\t", "Control9!\u0000", "A9!" + "x".repeat(126))) {
                String name = "Policy" + index++;
                try (Peer candidate = fixture.peer()) {
                    candidate.choose("signup");
                    candidate.send(name);
                    candidate.expect(PASSWORD_PROMPT);
                    candidate.send(invalid);
                    candidate.expect(PASSWORD_PROMPT);
                    candidate.send("login");
                    candidate.expect(USERNAME_PROMPT);
                    candidate.send("signup");
                    candidate.expect(USERNAME_PROMPT);
                    candidate.send(name);
                    candidate.expect(PASSWORD_PROMPT);
                    candidate.send(PASSWORD);
                    candidate.connected(name);
                }
            }
            try (Peer maximum = fixture.peer()) {
                maximum.choose("signup");
                maximum.send("MaximumPassword");
                maximum.expect(PASSWORD_PROMPT);
                maximum.send("A9!" + "x".repeat(125));
                maximum.connected("MaximumPassword");
            }
        }
    }

    private static void authenticationEof() throws Exception {
        try (Fixture fixture = new Fixture(); Peer watcher = fixture.signup("Watcher")) {
            try (Peer initial = fixture.peer()) {
                check(initial.socket.isConnected(), "Initial handshake did not connect");
                // Close without answering the initial choice.
            }
            for (String mode : Arrays.asList("login", "signup")) {
                try (Peer candidate = fixture.peer()) {
                    candidate.choose(mode);
                }
            }
            try (Peer candidate = fixture.peer()) {
                candidate.choose("signup");
                candidate.send("Abandoned");
                candidate.expect(PASSWORD_PROMPT);
            }
            try (Peer recovered = fixture.signup("Abandoned")) {
                recovered.send(ChatProtocol.LOGOUT);
                recovered.awaitClosed();
            }
            awaitOffline(watcher, "Abandoned");
            try (Peer candidate = fixture.peer()) {
                candidate.choose("login");
                candidate.send("Abandoned");
                candidate.expect(PASSWORD_PROMPT);
            }
            try (Peer recovered = fixture.login("Abandoned", PASSWORD)) {
                recovered.send("recovered");
                watcher.expect("Abandoned: recovered");
            }
            watcher.send("EOF did not stop the server");
            watcher.expect("Watcher: EOF did not stop the server");
        }
    }

    private static void modeSwitches() throws Exception {
        try (Fixture fixture = new Fixture(); Peer candidate = fixture.peer()) {
            candidate.choose("signup");
            candidate.send("Switchable");
            candidate.expect(PASSWORD_PROMPT);
            candidate.send("login");
            candidate.expect(USERNAME_PROMPT);
            candidate.send("signup");
            candidate.expect(USERNAME_PROMPT);
            candidate.send("Switchable");
            candidate.expect(PASSWORD_PROMPT);
            candidate.send(PASSWORD);
            candidate.connected("Switchable");
            candidate.send(ChatProtocol.LOGOUT);
            candidate.awaitClosed();
            try (Peer watcher = fixture.signup("Watcher")) {
                awaitOffline(watcher, "Switchable");
                try (Peer retry = fixture.peer()) {
                    retry.choose("login");
                    retry.send("Switchable");
                    retry.expect(PASSWORD_PROMPT);
                    retry.send("signup");
                    retry.expect(USERNAME_PROMPT);
                    retry.send("FreshAccount");
                    retry.expect(PASSWORD_PROMPT);
                    retry.send("login");
                    retry.expect(USERNAME_PROMPT);
                    retry.send("Switchable");
                    retry.expect(PASSWORD_PROMPT);
                    retry.send(PASSWORD);
                    retry.connected("Switchable");
                }
                try (Peer fresh = fixture.signup("FreshAccount")) {
                    fresh.send("created successfully");
                    watcher.expect("FreshAccount: created successfully");
                }
            }
        }
    }

    private static void ownershipCleanup() throws Exception {
        try (Fixture fixture = new Fixture(); Peer owner = fixture.signup("Owner")) {
            try (Peer duplicate = fixture.peer()) {
                duplicate.choose("login");
                duplicate.send("Owner");
                duplicate.expect(USERNAME_PROMPT);
            }
            owner.send("I am still connected");
            owner.expect("Owner: I am still connected");
            owner.send(ChatProtocol.USERS_REQUEST);
            check(owner.until(line -> line.startsWith(USERS_PREFIX)).last().contains("Owner"),
                    "Rejected login removed the real owner");
        }
    }

    private static void passwordRetries() throws Exception {
        try (Fixture fixture = new Fixture(); Peer watcher = fixture.signup("Watcher")) {
            try (Peer account = fixture.signup("Account")) {
                account.send(ChatProtocol.LOGOUT);
                account.awaitClosed();
            }
            awaitOffline(watcher, "Account");
            try (Peer candidate = fixture.peer()) {
                candidate.choose("login");
                candidate.send("Account");
                candidate.expect(PASSWORD_PROMPT);
                for (int attempt = 1; attempt <= 5; attempt++) {
                    candidate.send("WrongPassword9!");
                    if (attempt < 5) {
                        candidate.expect(PASSWORD_PROMPT);
                    }
                }
                candidate.awaitClosed();
            }
            try (Peer valid = fixture.login("Account", PASSWORD)) {
                valid.send("other connections can authenticate");
                watcher.expect("Account: other connections can authenticate");
            }
        }
    }

    private static void signupRace() throws Exception {
        try (Fixture fixture = new Fixture(); Peer first = fixture.peer(); Peer second = fixture.peer()) {
            for (Peer peer : Arrays.asList(first, second)) {
                peer.choose("signup");
                peer.send("Contended");
                peer.expect(PASSWORD_PROMPT);
            }
            simultaneously(() -> first.send("FirstPassword9!"), () -> second.send("SecondPassword9!"));
            boolean firstWon = authOutcome(first, "Contended");
            boolean secondWon = authOutcome(second, "Contended");
            check(firstWon != secondWon, "Exactly one signup must win");
            Peer winner = firstWon ? first : second;
            Peer loser = firstWon ? second : first;
            loser.send("OtherAccount");
            loser.expect(PASSWORD_PROMPT);
            loser.send(PASSWORD);
            loser.connected("OtherAccount");
            winner.send(ChatProtocol.LOGOUT);
            winner.awaitClosed();
            awaitOffline(loser, "Contended");
            try (Peer login = fixture.login("Contended", firstWon ? "FirstPassword9!" : "SecondPassword9!")) {
                login.send("winning password preserved");
                loser.expect("Contended: winning password preserved");
            }
        }
    }

    private static void loginRace() throws Exception {
        try (Fixture fixture = new Fixture(); Peer watcher = fixture.signup("Watcher")) {
            try (Peer registered = fixture.signup("Contended")) {
                registered.send(ChatProtocol.LOGOUT);
                registered.awaitClosed();
            }
            awaitOffline(watcher, "Contended");
            try (Peer first = fixture.peer(); Peer second = fixture.peer()) {
                for (Peer peer : Arrays.asList(first, second)) {
                    peer.choose("login");
                    peer.send("Contended");
                    peer.expect(PASSWORD_PROMPT);
                }
                simultaneously(() -> first.send(PASSWORD), () -> second.send(PASSWORD));
                boolean firstWon = authOutcome(first, "Contended");
                boolean secondWon = authOutcome(second, "Contended");
                check(firstWon != secondWon, "Exactly one login must win");
                Peer winner = firstWon ? first : second;
                Peer loser = firstWon ? second : first;
                loser.close();
                winner.send("owner survives the loser disconnecting");
                winner.expect("Contended: owner survives the loser disconnecting");
                watcher.expect("Contended: owner survives the loser disconnecting");
            }
        }
    }

    private static void historyOrdering() throws Exception {
        try (Fixture fixture = new Fixture(); Peer sender = fixture.signup("Sender"); Peer newcomer = fixture.peer()) {
            for (int index = 0; index < 30; index++) {
                sender.send(sequence(index));
            }
            sender.expect("Sender: " + sequence(29));
            newcomer.choose("signup");
            newcomer.send("Newcomer");
            newcomer.expect(PASSWORD_PROMPT);
            simultaneously(() -> newcomer.send(PASSWORD), () -> {
                for (int index = 30; index < 130; index++) {
                    sender.send(sequence(index));
                }
            });
            newcomer.connected("Newcomer");
            sender.expect("Sender: " + sequence(129));
            List<String> received = newcomer.until(line -> line.equals("Sender: " + sequence(129))).lines;
            List<String> messages = new ArrayList<>();
            for (String line : received) {
                if (line.startsWith("Sender: ")) {
                    messages.add(line);
                }
            }
            check(messages.size() == 130, "Expected 130 unique history/live messages, got " + messages.size());
            for (int index = 0; index < messages.size(); index++) {
                check(messages.get(index).equals("Sender: " + sequence(index)),
                        "History/live order mismatch at " + index + ": " + messages.get(index));
            }
        }
    }

    private static void boundedHistory() throws Exception {
        try (Fixture fixture = new Fixture(); Peer sender = fixture.signup("Sender")) {
            for (int index = 0; index < 520; index++) {
                sender.send(sequence(index));
            }
            sender.expect("Sender: " + sequence(519));
            try (Peer newcomer = fixture.signup("Newcomer")) {
                newcomer.send(ChatProtocol.USERS_REQUEST);
                List<String> received = newcomer.until(line -> line.startsWith(USERS_PREFIX)).lines;
                List<String> history = new ArrayList<>();
                for (String line : received) {
                    if (line.startsWith("Sender: ")) {
                        history.add(line);
                    }
                }
                check(history.size() == 500, "Expected 500 retained messages, got " + history.size());
                for (int index = 0; index < history.size(); index++) {
                    check(history.get(index).equals("Sender: " + sequence(index + 20)),
                            "Unexpected bounded history at " + index + ": " + history.get(index));
                }
            }
        }
    }

    private static void invalidChatInput() throws Exception {
        try (Fixture fixture = new Fixture(); Peer observer = fixture.signup("Observer"); Peer sender = fixture.signup("Sender")) {
            sender.send("");
            sender.send("   ");
            sender.send("injected\u0000control");
            sender.send("injected\u001b[31mcontrol");
            sender.send("x".repeat(2001));
            sender.send("x".repeat(2000));
            List<String> received = observer.until(line -> line.equals("Sender: " + "x".repeat(2000))).lines;
            sender.send("valid after rejection");
            received.addAll(observer.until(line -> line.equals("Sender: valid after rejection")).lines);
            for (String line : received) {
                check(!line.equals("Sender: ") && !line.equals("Sender:    ")
                        && !line.contains("injected") && !line.equals("Sender: " + "x".repeat(2001)),
                        "Invalid message was broadcast: " + line);
            }
            try (Peer oversized = fixture.peer()) {
                // The server may close as soon as the limit is reached, while this
                // oversized write is still in progress. Either write result is valid.
                oversized.writer.println("x".repeat(10000));
                oversized.awaitClosed();
            }
            try (Peer malformed = fixture.peer()) {
                malformed.socket.getOutputStream().write(new byte[] {(byte) 0xc3, (byte) 0x28, '\n'});
                malformed.socket.getOutputStream().flush();
                malformed.awaitClosed();
            }
            observer.send("server survived malformed input");
            observer.expect("Observer: server survived malformed input");
            try (Peer newcomer = fixture.signup("Newcomer")) {
                newcomer.send(ChatProtocol.USERS_REQUEST);
                List<String> history = newcomer.until(line -> line.startsWith(USERS_PREFIX)).lines;
                for (String line : history) {
                    check(!line.equals("Sender: ") && !line.equals("Sender:    ")
                            && !line.contains("injected") && !line.equals("Sender: " + "x".repeat(2001)),
                            "Invalid message entered history");
                }
            }
        }
    }

    private static void shutdown() throws Exception {
        Fixture fixture = new Fixture();
        try (Peer authenticated = fixture.signup("Connected"); Peer unauthenticated = fixture.peer()) {
            fixture.close();
            authenticated.awaitClosed();
            unauthenticated.awaitClosed();
            fixture.close();
        } finally {
            fixture.close();
        }
    }

    private static void connectionCapacity() throws Exception {
        try (Fixture fixture = new Fixture()) {
            List<Peer> held = new ArrayList<>();
            try {
                for (int index = 0; index < 64; index++) {
                    held.add(fixture.peer());
                }
                try (Socket rejected = new Socket(InetAddress.getLoopbackAddress(), fixture.server.getPort())) {
                    rejected.setSoTimeout(10000);
                    try {
                        check(rejected.getInputStream().read() == -1, "An excess connection was admitted");
                    } catch (java.net.SocketException expectedReset) {
                        // Closing an unaccepted connection may produce EOF or a reset.
                    }
                }
                held.remove(0).close();
                long deadline = System.nanoTime() + TIMEOUT_NANOS;
                while (System.nanoTime() < deadline) {
                    try (Socket replacement = new Socket(InetAddress.getLoopbackAddress(), fixture.server.getPort())) {
                        replacement.setSoTimeout(10000);
                        try {
                            BufferedReader input = new BufferedReader(new InputStreamReader(
                                    replacement.getInputStream(), StandardCharsets.UTF_8));
                            String welcome = input.readLine();
                            if (welcome != null) {
                                check(welcome.equals("Welcome to the chat server!"), "Unexpected replacement handshake");
                                check(input.readLine().contains("(login/signup)"), "Freed slot did not complete handshake");
                                return;
                            }
                        } catch (java.net.SocketException slotStillClosing) {
                            // Retry while the first handler releases its slot.
                        }
                    }
                }
                throw new AssertionError("A disconnected client's connection slot was not released");
            } finally {
                for (Peer peer : held) {
                    peer.close();
                }
            }
        }
    }

    private static void slowReaderIsolation() throws Exception {
        try (Fixture fixture = new Fixture(); Peer healthy = fixture.signup("Healthy"); Socket stalled = new Socket()) {
            stalled.setReceiveBufferSize(1024);
            stalled.setSoTimeout(10000);
            stalled.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), fixture.server.getPort()), 10000);
            BufferedReader input = new BufferedReader(new InputStreamReader(stalled.getInputStream(), StandardCharsets.UTF_8));
            PrintWriter output = new PrintWriter(new OutputStreamWriter(stalled.getOutputStream(), StandardCharsets.UTF_8), true);
            rawExpect(input, line -> line.contains("(login/signup)"));
            output.println("signup");
            rawExpect(input, USERNAME_PROMPT::equals);
            output.println("Stalled");
            rawExpect(input, PASSWORD_PROMPT::equals);
            output.println(PASSWORD);
            rawExpect(input, welcome("Stalled")::equals);
            // Stop consuming this socket. Its tiny receive window eventually blocks
            // its server writer, while the healthy client continues reading.
            boolean evicted = false;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            for (int index = 0; index < 2000 && System.nanoTime() < deadline; index++) {
                String body = "slow-probe-" + index + " " + "x".repeat(1970);
                healthy.send(body);
                healthy.expect("Healthy: " + body);
                if (index % 50 == 49) {
                    healthy.send(ChatProtocol.USERS_REQUEST);
                    String users = healthy.until(line -> line.startsWith(USERS_PREFIX)).last();
                    if (!users.contains("Stalled")) {
                        evicted = true;
                        break;
                    }
                }
            }
            check(evicted, "A stalled reader was not disconnected within the bounded traffic probe");
            healthy.send("healthy client remains responsive");
            healthy.expect("Healthy: healthy client remains responsive");
        }
    }

    private static void rawExpect(BufferedReader input, Predicate<String> expected) throws IOException {
        String line;
        while ((line = input.readLine()) != null) {
            if (expected.test(line)) {
                return;
            }
        }
        throw new AssertionError("Raw client disconnected before the expected handshake response");
    }

    private static boolean authOutcome(Peer peer, String username) throws Exception {
        String outcome = peer.until(line -> line.equals(welcome(username)) || line.equals(USERNAME_PROMPT)).last();
        return outcome.equals(welcome(username));
    }

    private static String welcome(String username) {
        return "Welcome, " + username + "! You are now connected.";
    }

    private static String sequence(int index) {
        return String.format("message-%03d", index);
    }

    private static void awaitOffline(Peer watcher, String username) throws Exception {
        long deadline = System.nanoTime() + TIMEOUT_NANOS;
        do {
            watcher.send(ChatProtocol.USERS_REQUEST);
            String users = watcher.until(line -> line.startsWith(USERS_PREFIX)).last();
            if (!Arrays.asList(users.substring(USERS_PREFIX.length()).trim().split("[, ]+")).contains(username)) {
                return;
            }
            Thread.yield();
        } while (System.nanoTime() < deadline);
        throw new AssertionError(username + " remained online after disconnect");
    }

    private static void simultaneously(CheckedRunnable first, CheckedRunnable second) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread a = concurrentAction(first, ready, start, failure);
        Thread b = concurrentAction(second, ready, start, failure);
        check(ready.await(10, TimeUnit.SECONDS), "Concurrent senders did not become ready");
        start.countDown();
        a.join(10000);
        b.join(10000);
        check(!a.isAlive() && !b.isAlive(), "Concurrent senders did not finish");
        if (failure.get() != null) {
            throw new AssertionError("Concurrent action failed", failure.get());
        }
    }

    private static Thread concurrentAction(CheckedRunnable action, CountDownLatch ready,
                                           CountDownLatch start, AtomicReference<Throwable> failure) {
        Thread thread = new Thread(() -> {
            ready.countDown();
            try {
                check(start.await(10, TimeUnit.SECONDS), "Timed out waiting for concurrent action");
                action.run();
            } catch (Throwable problem) {
                failure.compareAndSet(null, problem);
            }
        }, "test-concurrent-send");
        thread.setDaemon(true);
        thread.start();
        return thread;
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

    private static final class Fixture implements Closeable {
        final ChatServer server;
        final Thread thread;
        final AtomicReference<Throwable> failure = new AtomicReference<>();

        Fixture() throws Exception {
            this(0, null);
        }

        Fixture(int port, Path directory) throws Exception {
            server = directory == null ? new ChatServer(InetAddress.getLoopbackAddress(), port)
                    : new ChatServer(InetAddress.getLoopbackAddress(), port, directory);
            thread = new Thread(() -> {
                try {
                    server.start();
                } catch (Throwable problem) {
                    failure.set(problem);
                }
            }, "test-chat-server");
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

        Peer signup(String name) throws Exception {
            Peer peer = peer();
            try {
                peer.choose("signup");
                peer.send(name);
                peer.expect(PASSWORD_PROMPT);
                peer.send(PASSWORD);
                peer.connected(name);
                return peer;
            } catch (Throwable problem) {
                peer.close();
                throw problem;
            }
        }

        Peer login(String name, String password) throws Exception {
            Peer peer = peer();
            try {
                peer.choose("login");
                peer.send(name);
                peer.expect(PASSWORD_PROMPT);
                peer.send(password);
                peer.connected(name);
                return peer;
            } catch (Throwable problem) {
                peer.close();
                throw problem;
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
                try (BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = input.readLine()) != null) {
                        inbox.add(line);
                    }
                } catch (IOException problem) {
                    if (!socket.isClosed()) {
                        inbox.add(problem);
                    }
                } finally {
                    inbox.add(EOF);
                }
            }, "test-socket-reader");
            reader.setDaemon(true);
            reader.start();
            until(line -> line.contains("(login/signup)"));
        }

        void choose(String mode) throws Exception {
            send(mode);
            expect(USERNAME_PROMPT);
        }

        void connected(String username) throws Exception {
            expect(welcome(username));
        }

        void send(String line) throws IOException {
            writer.println(line);
            if (writer.checkError()) {
                throw new IOException("Could not send to chat server");
            }
        }

        Seen expect(String expected) throws Exception {
            return until(expected::equals);
        }

        Seen until(Predicate<String> match) throws Exception {
            return until(match, false);
        }

        Seen untilFrame(Predicate<String> match) throws Exception {
            return until(match, true);
        }

        private Seen until(Predicate<String> match, boolean raw) throws Exception {
            List<String> seen = new ArrayList<>();
            long deadline = System.nanoTime() + TIMEOUT_NANOS;
            while (true) {
                Object value = inbox.poll(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                if (value == null || value == EOF) {
                    throw new AssertionError((value == null ? "Timed out" : "Connection closed")
                            + " waiting for server response; received " + seen);
                }
                if (value instanceof IOException) {
                    throw (IOException) value;
                }
                String line = (String) value;
                if (!raw) {
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
