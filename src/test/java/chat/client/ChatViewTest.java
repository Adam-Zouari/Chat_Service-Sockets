package chat.client;

import chat.protocol.ChatProtocol;

import javax.imageio.ImageIO;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JTextPane;
import javax.swing.SwingUtilities;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.geom.Rectangle2D;
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
            UiTheme.install();
            themeInsets();
            timestampsAndSearch();
            retention();
            unread();
            presence();
            selection();
            presentationAndRecovery();
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
        System.out.println("PASS structured selectable messages, literal content and responsive recovery presentation");
    }

    private static ChatView view() {
        ChatView view = new ChatView(count -> { }, ZoneId.of("Africa/Tunis"));
        view.beginSession("Alice");
        return view;
    }

    private static void themeInsets() {
        JTextField input = new JTextField();
        UiTheme.styleInput(input);
        check(input.getInsets().equals(new Insets(8, 12, 8, 12))
                        && input.getInsets().equals(input.getInsets(new Insets(0, 0, 0, 0))),
                "Input padding must be consistent across Swing's inset lookup methods");
        JPanel surface = UiTheme.surfacePanel(new java.awt.BorderLayout());
        check(surface.getInsets().equals(new Insets(1, 1, 1, 1))
                        && surface.getInsets().equals(surface.getInsets(new Insets(0, 0, 0, 0))),
                "Surface padding must be consistent across Swing's inset lookup methods");
    }

    private static void timestampsAndSearch() {
        ChatView view = view();
        JTextPane messages = named(view, "messages", JTextPane.class);
        JTextField search = named(view, "searchMessages", JTextField.class);
        JLabel results = named(view, "searchStatus", JLabel.class);
        view.addMessage(message("Alice", "Hello café"), true);
        view.addMessage(new ChatProtocol.ChatMessage(0, "Bob", "An older message"), true);
        check(text(messages).contains("Alice   2026-10-24 13:34\nHello café"),
                "Messages must use their server timestamp in the local timezone");
        check(text(messages).contains("Bob   Earlier\nAn older message"),
                "Untimed legacy messages must not invent a timestamp");
        search.setText("CAFÉ");
        check(text(messages).contains("Hello café") && !text(messages).contains("An older message"),
                "Search must match message bodies case-insensitively");
        search.setText("bOB");
        check(text(messages).contains("An older message") && !text(messages).contains("Hello café"),
                "Search must match usernames case-insensitively");
        view.addMessage(message("Alice", "Other conversation"), false);
        check(search.getText().equals("bOB") && !text(messages).contains("Other conversation"),
                "New nonmatching messages must not clear the current filter");
        view.addMessage(message("Bob", "A matching arrival"), false);
        check(text(messages).contains("A matching arrival") && results.getText().equals("Showing 2 of 4 messages"),
                "Matching live messages must appear and update the search count");
        view.addNotice("Bob joined the room.");
        check(text(messages).contains("joined") && results.getText().equals("Showing 2 of 4 messages"),
                "Server notices must remain visible during search without changing the message count");
        search.setText("NoSuchText");
        check(!text(messages).contains("Hello café") && !text(messages).contains("An older message")
                        && results.getText().equals("No matching messages"),
                "An empty search result needs clear feedback");
        named(view, "clearSearch", JButton.class).doClick();
        check(search.getText().isEmpty() && text(messages).contains("Other conversation"),
                "Clear must restore all retained messages");
        search.setText("matching");
        view.beginSession("Alice");
        check(search.getText().equals("matching") && text(messages).isEmpty(),
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
        JTextPane messages = named(view, "messages", JTextPane.class);
        JTextField search = named(view, "searchMessages", JTextField.class);
        for (int i = 0; i <= ChatView.MAX_MESSAGES; i++) {
            view.addMessage(message("Bob", "entry-" + String.format("%04d", i)), true);
        }
        check(occurrences(text(messages), "entry-") == ChatView.MAX_MESSAGES,
                "Exactly 500 short messages should be retained");
        check(!text(messages).contains("entry-0000") && text(messages).contains("entry-0001")
                        && text(messages).contains("entry-0500"),
                "Only the oldest message should be removed when the limit is exceeded");
        search.setText("missing");
        for (int i = 0; i < 100; i++) {
            view.addMessage(message("Bob", "long-" + i + " " + "x".repeat(1_990)), true);
        }
        named(view, "clearSearch", JButton.class).doClick();
        check(text(messages).length() <= ChatView.MAX_CHARACTERS,
                "Hidden messages must still obey the character bound");
        check(!text(messages).contains("entry-0500") && text(messages).contains("long-99 "),
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
        JTextPane messages = named(view, "messages", JTextPane.class);
        view.addMessage(message("Bob", "first"), true);
        view.addMessage(message("Carol", "Copy this message"), true);
        int start = text(messages).indexOf("Copy this message");
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

    private static void presentationAndRecovery() {
        ChatView view = view();
        JTextPane messages = named(view, "messages", JTextPane.class);
        String literal = "<html><b>This is literal text</b></html> & everyone can copy it.";
        view.addMessage(message("Alice", "The new layout is much easier to read."), true);
        view.addMessage(message("Bob", "Agreed. A simple shared conversation is all we need."), true);
        view.addMessage(message("Carol", literal), true);
        view.setOnlineUsers(Arrays.asList("Alice", "Bob", "Carol", "A_very_long_username_12345678901"));
        check(text(messages).contains(literal) && messages.getContentType().equals("text/plain"),
                "Message markup must stay literal plain text rather than becoming rendered HTML");
        check(!messages.isEditable() && messages.isFocusable(),
                "The conversation must be read-only while remaining available for keyboard selection and copy");
        StyledDocument document = messages.getStyledDocument();
        AttributeSet author = document.getCharacterElement(text(messages).indexOf("Alice")).getAttributes();
        AttributeSet timestamp = document.getCharacterElement(text(messages).indexOf("2026-10-24")).getAttributes();
        AttributeSet body = document.getCharacterElement(text(messages).indexOf("The new layout")).getAttributes();
        check(StyleConstants.isBold(author) && !StyleConstants.isBold(timestamp)
                        && !StyleConstants.getForeground(timestamp).equals(StyleConstants.getForeground(body))
                        && StyleConstants.getFontSize(timestamp) < StyleConstants.getFontSize(body),
                "Authors and muted timestamps must be distinguishable from the readable message body");

        try {
            render(view, "chat-connected.png", 904, 450);
            JList<?> users = named(view, "onlineUsers", JList.class);
            JScrollPane usersScroll = (JScrollPane) users.getParent().getParent();
            check(users.getWidth() <= usersScroll.getViewport().getExtentSize().width,
                    "Long usernames must fit the sidebar instead of pushing user rows beyond its viewport");
            String retained = text(messages);
            view.setPresenceUnavailable();
            view.setReconnecting(true);
            check(text(messages).equals(retained), "Reconnecting must keep the conversation available");
            JTextArea notice = named(view, "reconnectNotice", JTextArea.class);
            check(visibleIn(view, notice) && notice.getText().toLowerCase(java.util.Locale.ROOT).contains("reconnect"),
                    "An outage must provide a non-blocking reconnection notice");
            check(named(view, "searchMessages", JTextField.class).isEnabled(),
                    "Local history search must remain usable while reconnecting");
            render(view, "chat-reconnecting.png", 720, 400);
            JScrollPane offlineScroll = (JScrollPane) messages.getParent().getParent();
            check(offlineScroll.getViewport().getExtentSize().height >= 100,
                    "The reconnection notice must leave room to read the retained conversation");
            view.setReconnecting(false);
            check(!visibleIn(view, notice), "The reconnection notice must disappear when service is restored");

            ChatView wrapping = view();
            wrapping.addMessage(message("W".repeat(32), "x".repeat(2_000)), true);
            render(wrapping, "chat-long-message.png", 720, 400);
            JTextPane wrappedMessages = named(wrapping, "messages", JTextPane.class);
            JScrollPane scroll = (JScrollPane) wrappedMessages.getParent().getParent();
            Rectangle2D end = wrappedMessages.modelToView2D(wrappedMessages.getDocument().getLength() - 3);
            Rectangle2D authorEnd = wrappedMessages.modelToView2D(31);
            check(end != null && authorEnd != null
                            && end.getMaxX() <= scroll.getViewport().getExtentSize().width
                            && authorEnd.getMaxX() <= scroll.getViewport().getExtentSize().width,
                    "Maximum-length usernames and unbroken messages must wrap inside the conversation viewport");
            check(scroll.getVerticalScrollBar().isVisible()
                            && scroll.getVerticalScrollBar().getMaximum() > scroll.getVerticalScrollBar().getVisibleAmount(),
                    "A long wrapped message must remain reachable through vertical scrolling");
            scroll.getVerticalScrollBar().setValue(scroll.getVerticalScrollBar().getMaximum());
            check(end.getMaxY() <= scroll.getViewport().getViewPosition().y
                            + scroll.getViewport().getExtentSize().height,
                    "Scrolling to the bottom must reveal the end of a maximum-length message");
            render(wrapping, "chat-long-message-end.png", 720, 400);
        } catch (IOException | BadLocationException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static void prepareScrollTest() {
        scrolling = view();
        for (int i = 0; i < 80; i++) {
            scrolling.addMessage(message("Bob", "Message " + i + " with enough text for a normal conversation."), true);
        }
        scrolling.setOnlineUsers(Arrays.asList("Alice", "Bob"));
        try {
            render(scrolling, "chat-minimum.png", 720, 400);
            named(scrolling, "searchMessages", JTextField.class).setText("not found");
            render(scrolling, "chat-search-empty.png", 904, 450);
            named(scrolling, "clearSearch", JButton.class).doClick();
            render(scrolling, "chat-view.png", 904, 450);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        JTextPane messages = named(scrolling, "messages", JTextPane.class);
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

    private static int occurrences(String text, String needle) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    private static String text(JTextPane pane) {
        try {
            // JEditorPane#getText serializes platform line endings; document offsets do not.
            return pane.getDocument().getText(0, pane.getDocument().getLength());
        } catch (BadLocationException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static boolean visibleIn(Container root, Component component) {
        for (Component current = component; current != null; current = current.getParent()) {
            if (!current.isVisible()) {
                return false;
            }
            if (current == root) {
                return true;
            }
        }
        return false;
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
        // Assign width, calculate wrapped text height, then account for scrollbar width.
        // A displayed Swing hierarchy performs these validation passes asynchronously.
        for (int pass = 0; pass < 3; pass++) {
            layout(view);
        }
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
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
