package chat.client;

import chat.protocol.ChatProtocol;

import javax.imageio.ImageIO;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

/** Exercises the real chat component headlessly, including filtering and bounded presentation. */
public final class ChatViewTest {
    private static final long TIME = Instant.parse("2026-10-24T12:34:00Z").toEpochMilli();
    private static ChatView scrolling;
    private static JScrollPane scrollingPane;
    private static int previousScroll;

    private ChatViewTest() { }

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        SwingUtilities.invokeAndWait(() -> {
            timestampsAndSearch();
            retention();
            unread();
            presence();
            selection();
            prepareScrollTest();
        });
        // Let queued Swing layout and initial scroll-follow work finish first.
        SwingUtilities.invokeAndWait(ChatViewTest::appendWhileReading);
        SwingUtilities.invokeAndWait(() -> check(scrollingPane.getVerticalScrollBar().getValue() == previousScroll,
                "Incoming messages must preserve an older reading position"));
        System.out.println("PASS chat timestamps, local timezone and explicit untimed legacy messages");
        System.out.println("PASS local username/body search, live filtering, empty results and resume filter retention");
        System.out.println("PASS bounded recent messages and characters, including hidden search results");
        System.out.println("PASS unread counts exclude own messages, replay and notices and reset on focus/logout");
        System.out.println("PASS live presence snapshots, counts and clearing disconnected users");
        System.out.println("PASS incoming messages preserve copy selections and older scroll positions");
        System.out.println("PASS headless chat rendering at the minimum window width");
    }

    private static ChatView view() {
        ChatView view = new ChatView(count -> { }, ZoneId.of("Africa/Tunis"));
        view.beginSession("Alice");
        return view;
    }

    private static void timestampsAndSearch() {
        ChatView view = view();
        JTextArea messages = named(view, "messages", JTextArea.class);
        JTextField search = named(view, "searchMessages", JTextField.class);
        JLabel results = named(view, "searchStatus", JLabel.class);
        view.addMessage(message("Alice", "Hello café"), true);
        view.addMessage(new ChatProtocol.ChatMessage(0, "Bob", "An older message"), true);
        check(messages.getText().contains("[2026-10-24 13:34] Alice: Hello café"),
                "Messages must use their server timestamp in the local timezone");
        check(messages.getText().contains("[Earlier] Bob: An older message"),
                "Untimed legacy messages must not invent a timestamp");
        search.setText("CAFÉ");
        check(messages.getText().contains("Hello café") && !messages.getText().contains("An older message"),
                "Search must match message bodies case-insensitively");
        search.setText("bOB");
        check(messages.getText().contains("An older message") && !messages.getText().contains("Hello café"),
                "Search must match usernames case-insensitively");
        view.addMessage(message("Alice", "Other conversation"), false);
        check(search.getText().equals("bOB") && !messages.getText().contains("Other conversation"),
                "New nonmatching messages must not clear the current filter");
        view.addMessage(message("Bob", "A matching arrival"), false);
        check(messages.getText().contains("A matching arrival") && results.getText().equals("Showing 2 of 4 messages"),
                "Matching live messages must appear and update the search count");
        view.addNotice("Bob joined the room.");
        check(messages.getText().contains("joined") && results.getText().equals("Showing 2 of 4 messages"),
                "Server notices must remain visible during search without changing the message count");
        search.setText("NoSuchText");
        check(!messages.getText().contains("Alice:") && !messages.getText().contains("Bob:")
                        && results.getText().equals("No matching messages"),
                "An empty search result needs clear feedback");
        named(view, "clearSearch", JButton.class).doClick();
        check(search.getText().isEmpty() && messages.getText().contains("Other conversation"),
                "Clear must restore all retained messages");
        search.setText("matching");
        view.beginSession("Alice");
        check(search.getText().equals("matching") && messages.getText().isEmpty(),
                "Resuming the same identity must preserve its filter while clearing history for replay");
        view.addMessage(message("Bob", "A matching arrival"), true);
        check(results.getText().equals("Showing 1 of 1 messages"), "Replayed search results must not duplicate");
        view.beginSession("Bob");
        check(search.getText().isEmpty(), "A different identity should start with an empty search");
        search.setText("x".repeat(ChatProtocol.MAX_MESSAGE_LENGTH));
        check(search.getText().length() == ChatProtocol.MAX_MESSAGE_LENGTH,
                "The search field should support searching an entire maximum-length message");
        search.setText("x".repeat(ChatProtocol.MAX_MESSAGE_LENGTH + 1));
        check(search.getText().length() <= ChatProtocol.MAX_MESSAGE_LENGTH,
                "Pasted search queries must be bounded");
    }

    private static void retention() {
        ChatView view = view();
        JTextArea messages = named(view, "messages", JTextArea.class);
        JTextField search = named(view, "searchMessages", JTextField.class);
        for (int i = 0; i <= ChatView.MAX_MESSAGES; i++) {
            view.addMessage(message("Bob", "entry-" + String.format("%04d", i)), true);
        }
        check(messages.getLineCount() - 1 == ChatView.MAX_MESSAGES, "Exactly 500 short messages should be retained");
        check(!messages.getText().contains("entry-0000") && messages.getText().contains("entry-0001")
                        && messages.getText().contains("entry-0500"),
                "Only the oldest message should be removed when the limit is exceeded");
        search.setText("missing");
        for (int i = 0; i < 100; i++) {
            view.addMessage(message("Bob", "long-" + i + " " + "x".repeat(1_990)), true);
        }
        named(view, "clearSearch", JButton.class).doClick();
        check(messages.getText().length() <= ChatView.MAX_CHARACTERS,
                "Hidden messages must still obey the character bound");
        check(!messages.getText().contains("entry-0500") && messages.getText().contains("long-99 "),
                "The character cap must evict old full messages while retaining the newest");
    }

    private static void unread() {
        AtomicInteger reported = new AtomicInteger();
        ChatView view = new ChatView(reported::set, ZoneId.of("UTC"));
        view.beginSession("Alice");
        view.setWindowFocused(false);
        view.addMessage(message("Bob", "History"), true);
        view.addMessage(message("Alice", "Own echo"), false);
        view.addNotice("Someone joined.");
        check(view.unreadCount() == 0 && reported.get() == 0,
                "Replayed history, own echoes and notices must not count as unread");
        named(view, "searchMessages", JTextField.class).setText("hidden");
        view.addMessage(message("Bob", "New message"), false);
        view.addMessage(message("Carol", "Another new message"), false);
        check(view.unreadCount() == 2 && reported.get() == 2,
                "New messages from others must count even when filtered out");
        view.setWindowFocused(true);
        check(view.unreadCount() == 0 && reported.get() == 0, "Focusing the window must clear unread messages");
        view.addMessage(message("Bob", "While focused"), false);
        check(view.unreadCount() == 0, "Focused windows must not accumulate unread messages");
        view.setWindowFocused(false);
        view.addMessage(message("Bob", "Before reconnect"), false);
        view.beginSession("Alice");
        check(view.unreadCount() == 0, "A restored session's history must start without stale unread counts");
        view.addMessage(message("Bob", "Before logout"), false);
        view.endSession();
        check(view.unreadCount() == 0 && named(view, "searchMessages", JTextField.class).getText().isEmpty(),
                "Logout must clear unread state and the filter");
    }

    private static void presence() {
        ChatView view = view();
        JList<?> users = named(view, "onlineUsers", JList.class);
        view.setOnlineUsers(Arrays.asList("Carol", "Bob", "Alice", "Bob"));
        check(users.getModel().getSize() == 3 && users.getModel().getElementAt(0).equals("Alice")
                        && users.getModel().getElementAt(2).equals("Carol"),
                "Presence snapshots must be sorted and include the current user once");
        check(named(view, "onlineCount", JLabel.class).getText().equals("Online (3)"),
                "Presence must show the current online count");
        view.setOnlineUsers(Arrays.asList("Alice", "Carol"));
        check(users.getModel().getSize() == 2, "Departed users must disappear from a replacement snapshot");
        view.setPresenceUnavailable();
        check(users.getModel().getSize() == 0
                        && named(view, "presenceStatus", JLabel.class).getText().equals("Disconnected"),
                "Disconnected clients must not show stale users as online");
    }

    private static void selection() {
        ChatView view = view();
        JTextArea messages = named(view, "messages", JTextArea.class);
        view.addMessage(message("Bob", "first"), true);
        view.addMessage(message("Carol", "Copy this message"), true);
        int start = messages.getText().indexOf("Copy this message");
        messages.select(start, start + "Copy this message".length());
        view.addMessage(message("Bob", "An arrival"), false);
        check(messages.getSelectedText().equals("Copy this message"),
                "Appending must preserve the original selection for copying");
        for (int i = 3; i < ChatView.MAX_MESSAGES; i++) {
            view.addMessage(message("Bob", "entry" + i), true);
        }
        view.addMessage(message("Bob", "Evicts first"), false);
        check(messages.getSelectedText().equals("Copy this message"),
                "Removing an earlier full message must adjust the selection without changing its text");
    }

    private static void prepareScrollTest() {
        scrolling = view();
        for (int i = 0; i < 80; i++) {
            scrolling.addMessage(message("Bob", "Message " + i + " with enough text for a normal conversation."), true);
        }
        scrolling.setOnlineUsers(Arrays.asList("Alice", "Bob"));
        try {
            render(scrolling, "chat-minimum.png", 604, 360);
            named(scrolling, "searchMessages", JTextField.class).setText("not found");
            render(scrolling, "chat-search-empty.png", 736, 400);
            named(scrolling, "clearSearch", JButton.class).doClick();
            render(scrolling, "chat-view.png", 736, 400);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        JTextArea messages = named(scrolling, "messages", JTextArea.class);
        scrollingPane = (JScrollPane) messages.getParent().getParent();
    }

    private static void appendWhileReading() {
        scrollingPane.getVerticalScrollBar().setValue(160);
        previousScroll = scrollingPane.getVerticalScrollBar().getValue();
        check(previousScroll > 0 && previousScroll < scrollingPane.getVerticalScrollBar().getMaximum() - 400,
                "The scroll test must start away from the bottom");
        scrolling.addMessage(message("Bob", "Arrives while the reader is looking at older messages"), false);
        layout(scrolling);
    }

    private static ChatProtocol.ChatMessage message(String user, String text) {
        return new ChatProtocol.ChatMessage(TIME, user, text);
    }

    private static <T extends Component> T named(Container parent, String name, Class<T> type) {
        for (Component component : parent.getComponents()) {
            if (name.equals(component.getName()) && type.isInstance(component)) {
                return type.cast(component);
            }
            if (component instanceof Container) {
                T nested = named((Container) component, name, type);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }

    private static void render(ChatView view, String filename, int width, int height) throws IOException {
        view.setSize(width, height);
        layout(view);
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            view.printAll(graphics);
        } finally {
            graphics.dispose();
        }
        Files.createDirectories(Paths.get("build"));
        ImageIO.write(image, "png", Paths.get("build", filename).toFile());
    }

    private static void layout(Container container) {
        container.doLayout();
        for (Component component : container.getComponents()) {
            if (component instanceof Container) {
                layout((Container) component);
            }
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
