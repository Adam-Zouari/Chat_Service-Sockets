package chat.client;

import chat.protocol.ChatProtocol;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.AbstractDocument;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultCaret;
import javax.swing.text.DocumentFilter;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.geom.Rectangle2D;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.IntConsumer;

/** Bounded, locally searchable chat presentation, usable without a desktop window. */
public final class ChatView extends JPanel {
    private static final long serialVersionUID = 1L;
    static final int MAX_MESSAGES = 500;
    static final int MAX_CHARACTERS = 160_000;

    private final ArrayDeque<Entry> entries = new ArrayDeque<>();
    private final JTextArea messages = new JTextArea();
    private final JScrollPane messageScroll = new JScrollPane(messages);
    private final JTextField search = new JTextField();
    private final JLabel searchStatus = new JLabel("No messages yet");
    private final JButton clearSearch = new JButton("Clear");
    private final DefaultListModel<String> online = new DefaultListModel<>();
    private final JLabel onlineTitle = new JLabel("Online");
    private final JLabel presenceStatus = new JLabel("Disconnected");
    private final transient IntConsumer onUnreadChanged;
    private final transient DateTimeFormatter timestampFormat;
    private transient List<Entry> visible = Collections.emptyList();
    private String username;
    private boolean windowFocused = true;
    private int unread;
    private int characters;
    private long displayRevision;

    public ChatView(IntConsumer onUnreadChanged) {
        this(onUnreadChanged, ZoneId.systemDefault());
    }

    ChatView(IntConsumer onUnreadChanged, ZoneId timezone) {
        super(new BorderLayout(10, 0));
        this.onUnreadChanged = Objects.requireNonNull(onUnreadChanged, "onUnreadChanged");
        timestampFormat = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm", Locale.ROOT).withZone(timezone);
        setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 12));
        messages.setName("messages");
        messages.setEditable(false);
        messages.setLineWrap(true);
        messages.setWrapStyleWord(true);
        messages.setMargin(new Insets(8, 8, 8, 8));
        ((DefaultCaret) messages.getCaret()).setUpdatePolicy(DefaultCaret.NEVER_UPDATE);

        search.setName("searchMessages");
        search.setToolTipText("Search recent messages by username or message text. Search stays on this computer.");
        search.getAccessibleContext().setAccessibleName("Search recent messages");
        ((AbstractDocument) search.getDocument()).setDocumentFilter(new DocumentFilter() {
            @Override
            public void insertString(FilterBypass bypass, int offset, String text,
                                     AttributeSet attributes) throws BadLocationException {
                replace(bypass, offset, 0, text, attributes);
            }

            @Override
            public void replace(FilterBypass bypass, int offset, int length, String text,
                                AttributeSet attributes) throws BadLocationException {
                int replacementLength = text == null ? 0 : text.length();
                if (bypass.getDocument().getLength() - length + replacementLength
                        <= ChatProtocol.MAX_MESSAGE_LENGTH) {
                    super.replace(bypass, offset, length, text, attributes);
                }
            }
        });
        clearSearch.setName("clearSearch");
        clearSearch.setEnabled(false);
        clearSearch.addActionListener(event -> search.setText(""));
        searchStatus.setName("searchStatus");
        search.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) { filterChanged(); }
            @Override public void removeUpdate(DocumentEvent event) { filterChanged(); }
            @Override public void changedUpdate(DocumentEvent event) { filterChanged(); }
        });
        JPanel searchRow = new JPanel(new BorderLayout(6, 0));
        JLabel searchLabel = new JLabel("Search");
        searchLabel.setLabelFor(search);
        searchRow.add(searchLabel, BorderLayout.WEST);
        searchRow.add(search, BorderLayout.CENTER);
        searchRow.add(clearSearch, BorderLayout.EAST);
        JPanel searchPanel = new JPanel(new BorderLayout(0, 4));
        searchPanel.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));
        searchPanel.add(searchRow, BorderLayout.NORTH);
        searchPanel.add(searchStatus, BorderLayout.SOUTH);
        JPanel conversation = new JPanel(new BorderLayout());
        conversation.add(searchPanel, BorderLayout.NORTH);
        conversation.add(messageScroll, BorderLayout.CENTER);
        add(conversation, BorderLayout.CENTER);

        JList<String> onlineUsers = new JList<>(online);
        onlineUsers.setName("onlineUsers");
        onlineUsers.setFocusable(false);
        onlineUsers.setVisibleRowCount(8);
        onlineTitle.setName("onlineCount");
        presenceStatus.setName("presenceStatus");
        JPanel presenceHeader = new JPanel(new BorderLayout(0, 4));
        presenceHeader.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));
        presenceHeader.add(onlineTitle, BorderLayout.NORTH);
        presenceHeader.add(presenceStatus, BorderLayout.SOUTH);
        JPanel sidebar = new JPanel(new BorderLayout());
        sidebar.setPreferredSize(new Dimension(155, 0));
        sidebar.setMinimumSize(new Dimension(130, 0));
        sidebar.add(presenceHeader, BorderLayout.NORTH);
        sidebar.add(new JScrollPane(onlineUsers), BorderLayout.CENTER);
        add(sidebar, BorderLayout.EAST);
    }

    /** A restored session replays history; retain its filter, but never duplicate its messages. */
    void beginSession(String signedInUsername) {
        boolean sameUser = Objects.equals(username, signedInUsername);
        username = signedInUsername;
        entries.clear();
        characters = 0;
        clearUnread();
        if (!sameUser) {
            search.setText("");
        }
        render(true);
        setPresenceUnavailable();
    }

    void endSession() {
        username = null;
        entries.clear();
        characters = 0;
        search.setText("");
        clearUnread();
        render(true);
        setPresenceUnavailable();
    }

    void addMessage(ChatProtocol.ChatMessage message, boolean historical) {
        Objects.requireNonNull(message, "message");
        String timestamp = message.timestampMillis() == 0 ? "Earlier"
                : timestampFormat.format(Instant.ofEpochMilli(message.timestampMillis()));
        append(new Entry("[" + timestamp + "] " + message.username() + ": " + message.text() + "\n",
                message.username() + "\n" + message.text(), true));
        if (!historical && username != null && !message.username().equals(username) && !windowFocused) {
            if (unread < Integer.MAX_VALUE) {
                unread++;
            }
            onUnreadChanged.accept(unread);
        }
    }

    void addNotice(String notice) {
        append(new Entry(notice + "\n", "", false));
    }

    void setWindowFocused(boolean focused) {
        windowFocused = focused;
        if (focused) {
            clearUnread();
        }
    }

    void setOnlineUsers(List<String> usernames) {
        List<String> sorted = new ArrayList<>(usernames);
        sorted.sort(String.CASE_INSENSITIVE_ORDER.thenComparing(java.util.Comparator.naturalOrder()));
        online.clear();
        String previous = null;
        for (String name : sorted) {
            if (!name.equals(previous)) {
                online.addElement(name);
                previous = name;
            }
        }
        onlineTitle.setText("Online (" + online.size() + ")");
        presenceStatus.setText("Connected");
    }

    void setPresenceUnavailable() {
        online.clear();
        onlineTitle.setText("Online");
        presenceStatus.setText("Disconnected");
    }

    int unreadCount() { return unread; }

    private void clearUnread() {
        unread = 0;
        onUnreadChanged.accept(0);
    }

    private void append(Entry entry) {
        entries.addLast(entry);
        characters += entry.display.length();
        while (entries.size() > MAX_MESSAGES || characters > MAX_CHARACTERS) {
            characters -= entries.removeFirst().display.length();
        }
        render(false);
    }

    private void filterChanged() {
        clearSearch.setEnabled(!search.getText().isEmpty());
        render(true);
    }

    private void render(boolean filterChanged) {
        String query = search.getText().toLowerCase(Locale.ROOT);
        List<Entry> matching = new ArrayList<>();
        int totalMessages = 0;
        int matchedMessages = 0;
        for (Entry entry : entries) {
            if (entry.chat) {
                totalMessages++;
            }
            // Server notices can include a failed send or storage error: keep them visible
            // even while a message search is active.
            if (!entry.chat || query.isEmpty() || entry.searchable.contains(query)) {
                matching.add(entry);
                if (entry.chat) {
                    matchedMessages++;
                }
            }
        }
        searchStatus.setText(query.isEmpty() ? (totalMessages == 0 ? "No messages yet"
                : totalMessages + " recent message" + (totalMessages == 1 ? "" : "s"))
                : matchedMessages == 0 ? "No matching messages"
                : "Showing " + matchedMessages + " of " + totalMessages + " messages");
        if (!filterChanged && visible.equals(matching)) {
            return;
        }
        long revision = ++displayRevision;
        if (filterChanged) {
            StringBuilder text = new StringBuilder();
            for (Entry entry : matching) {
                text.append(entry.display);
            }
            messages.setText(text.toString());
            messages.setCaretPosition(0);
            visible = matching;
            SwingUtilities.invokeLater(() -> {
                if (displayRevision == revision) {
                    messageScroll.getVerticalScrollBar().setValue(0);
                }
            });
            return;
        }

        // Incoming messages only remove old entries at the start and append at the end.
        // Mutating that suffix/prefix preserves selected text and the user's reading position.
        int removedEntries = matching.isEmpty() ? visible.size() : visible.indexOf(matching.get(0));
        if (removedEntries < 0) {
            removedEntries = visible.size();
        }
        int removedCharacters = 0;
        for (int i = 0; i < removedEntries; i++) {
            removedCharacters += visible.get(i).display.length();
        }
        int overlap = visible.size() - removedEntries;
        JScrollBar scrollbar = messageScroll.getVerticalScrollBar();
        int scrollPosition = scrollbar.getValue();
        int dot = messages.getCaret().getDot();
        int mark = messages.getCaret().getMark();
        boolean follow = dot == mark && scrollPosition + scrollbar.getVisibleAmount() >= scrollbar.getMaximum() - 10;
        int removedHeight = 0;
        try {
            if (removedCharacters > 0) {
                Rectangle2D location = messages.modelToView2D(removedCharacters);
                removedHeight = location == null ? 0 : (int) location.getY() - messages.getInsets().top;
                messages.getDocument().remove(0, removedCharacters);
            }
            StringBuilder appended = new StringBuilder();
            for (int i = overlap; i < matching.size(); i++) {
                appended.append(matching.get(i).display);
            }
            messages.getDocument().insertString(messages.getDocument().getLength(), appended.toString(), null);
            int length = messages.getDocument().getLength();
            messages.setCaretPosition(Math.max(0, Math.min(length, mark - removedCharacters)));
            messages.moveCaretPosition(Math.max(0, Math.min(length, dot - removedCharacters)));
        } catch (BadLocationException impossible) {
            throw new IllegalStateException("Could not update chat messages", impossible);
        }
        visible = matching;
        int preservedPosition = Math.max(0, scrollPosition - removedHeight);
        SwingUtilities.invokeLater(() -> {
            if (displayRevision == revision) {
                scrollbar.setValue(follow ? scrollbar.getMaximum() : preservedPosition);
            }
        });
    }

    private static final class Entry {
        final String display;
        final String searchable;
        final boolean chat;

        Entry(String display, String searchable, boolean chat) {
            this.display = display;
            this.searchable = searchable.toLowerCase(Locale.ROOT);
            this.chat = chat;
        }
    }
}
