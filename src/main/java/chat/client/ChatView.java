package chat.client;

import chat.protocol.ChatProtocol;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JTextPane;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.AbstractDocument;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultCaret;
import javax.swing.text.DocumentFilter;
import javax.swing.text.LabelView;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import javax.swing.text.StyledEditorKit;
import javax.swing.text.View;
import javax.swing.text.ViewFactory;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
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
    private final JTextPane messages = new JTextPane() {
        private static final long serialVersionUID = 1L;
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
    };
    private final JScrollPane messageScroll = new JScrollPane(messages);
    private final JTextField search = new JTextField();
    private final JLabel searchStatus = new JLabel("No messages yet");
    private final JButton clearSearch = new JButton("Clear");
    private final DefaultListModel<String> online = new DefaultListModel<>();
    private final JLabel onlineTitle = new JLabel("Online");
    private final JLabel presenceStatus = new JLabel("Disconnected");
    private final JTextArea reconnectNotice = new JTextArea(
            "Reconnecting to server...\nMessages remain visible. Sending is temporarily unavailable.");
    private final JPanel reconnectBanner = new JPanel(new BorderLayout());
    private final SimpleAttributeSet nameStyle = textStyle(UiTheme.LABEL.deriveFont(Font.BOLD), UiTheme.TEXT);
    private final SimpleAttributeSet timestampStyle = textStyle(UiTheme.SMALL, UiTheme.MUTED);
    private final SimpleAttributeSet bodyStyle = textStyle(UiTheme.BODY, UiTheme.TEXT);
    private final SimpleAttributeSet noticeStyle = textStyle(UiTheme.SMALL, UiTheme.MUTED);
    private final SimpleAttributeSet gapStyle = textStyle(UiTheme.SMALL.deriveFont(6f), UiTheme.MUTED);
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
        super(new BorderLayout(0, 12));
        this.onUnreadChanged = Objects.requireNonNull(onUnreadChanged, "onUnreadChanged");
        timestampFormat = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm", Locale.ROOT).withZone(timezone);
        setBackground(UiTheme.BACKGROUND);
        setBorder(BorderFactory.createEmptyBorder(16, 16, 12, 16));
        configureMessages();
        configureSearch();

        JPanel conversation = UiTheme.surfacePanel(new BorderLayout());
        conversation.add(createSearchPanel(), BorderLayout.NORTH);
        conversation.add(messageScroll, BorderLayout.CENTER);
        JPanel columns = new JPanel(new BorderLayout(12, 0));
        columns.setOpaque(false);
        columns.add(conversation, BorderLayout.CENTER);
        columns.add(createSidebar(), BorderLayout.EAST);
        add(columns, BorderLayout.CENTER);

        reconnectNotice.setName("reconnectNotice");
        UiTheme.styleNotice(reconnectNotice);
        reconnectNotice.setForeground(UiTheme.WARNING.darker());
        reconnectNotice.setPreferredSize(new Dimension(1,
                2 * reconnectNotice.getFontMetrics(reconnectNotice.getFont()).getHeight()));
        reconnectNotice.getAccessibleContext().setAccessibleName("Connection notice");
        reconnectBanner.setBackground(new Color(255, 248, 235));
        reconnectBanner.setBorder(BorderFactory.createCompoundBorder(
                UiTheme.roundedBorder(new Color(248, 218, 166), 6),
                BorderFactory.createEmptyBorder(9, 12, 9, 12)));
        reconnectBanner.add(reconnectNotice, BorderLayout.CENTER);
        reconnectBanner.setVisible(false);
        add(reconnectBanner, BorderLayout.NORTH);
    }

    private void configureMessages() {
        messages.setName("messages");
        messages.setEditorKit(new WrappingEditorKit());
        messages.setEditable(false);
        messages.setFont(UiTheme.BODY);
        messages.setForeground(UiTheme.TEXT);
        messages.setBackground(UiTheme.SURFACE);
        messages.setSelectionColor(UiTheme.SELECTION);
        messages.setSelectedTextColor(UiTheme.TEXT);
        messages.setCaretColor(UiTheme.PRIMARY);
        messages.setMargin(new Insets(14, 16, 14, 16));
        messages.getAccessibleContext().setAccessibleName("Conversation messages");
        messages.getAccessibleContext().setAccessibleDescription("Select message text to copy it.");
        ((DefaultCaret) messages.getCaret()).setUpdatePolicy(DefaultCaret.NEVER_UPDATE);
        messageScroll.setBorder(BorderFactory.createEmptyBorder());
        messageScroll.getViewport().setBackground(UiTheme.SURFACE);
        messageScroll.getVerticalScrollBar().setUnitIncrement(18);
        messageScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
    }

    private void configureSearch() {
        search.setName("searchMessages");
        search.setToolTipText("Search recent messages by username or message text. Search stays on this computer.");
        search.getAccessibleContext().setAccessibleName("Search recent messages");
        UiTheme.styleInput(search);
        search.setBorder(BorderFactory.createEmptyBorder(8, 0, 8, 8));
        search.setOpaque(false);
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
        UiTheme.styleLink(clearSearch);
        clearSearch.setEnabled(false);
        clearSearch.addActionListener(event -> {
            search.setText("");
            search.requestFocusInWindow();
        });
        searchStatus.setName("searchStatus");
        searchStatus.setFont(UiTheme.SMALL);
        searchStatus.setForeground(UiTheme.MUTED);
        search.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) { filterChanged(); }
            @Override public void removeUpdate(DocumentEvent event) { filterChanged(); }
            @Override public void changedUpdate(DocumentEvent event) { filterChanged(); }
        });
    }

    private JPanel createSearchPanel() {
        JLabel icon = new JLabel(new SearchIcon());
        icon.setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 8));
        icon.setLabelFor(search);
        JPanel searchBox = UiTheme.surfacePanel(new BorderLayout());
        searchBox.setBorder(UiTheme.roundedBorder(UiTheme.BORDER, 6));
        search.addFocusListener(new FocusAdapter() {
            @Override public void focusGained(FocusEvent event) {
                searchBox.setBorder(UiTheme.roundedBorder(UiTheme.PRIMARY, 6));
            }
            @Override public void focusLost(FocusEvent event) {
                searchBox.setBorder(UiTheme.roundedBorder(UiTheme.BORDER, 6));
            }
        });
        searchBox.add(icon, BorderLayout.WEST);
        searchBox.add(search, BorderLayout.CENTER);
        JLabel searchLabel = new JLabel("Search messages");
        searchLabel.setLabelFor(search);
        searchLabel.setFont(UiTheme.LABEL);
        searchLabel.setForeground(UiTheme.MUTED);
        JPanel searchRow = UiTheme.surfacePanel(new BorderLayout(8, 0));
        searchRow.setBorder(BorderFactory.createEmptyBorder());
        searchRow.add(searchBox, BorderLayout.CENTER);
        searchRow.add(clearSearch, BorderLayout.EAST);
        JPanel searchPanel = UiTheme.surfacePanel(new BorderLayout(0, 6));
        searchPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, UiTheme.BORDER),
                BorderFactory.createEmptyBorder(12, 14, 10, 14)));
        searchPanel.add(searchLabel, BorderLayout.NORTH);
        searchPanel.add(searchRow, BorderLayout.CENTER);
        searchPanel.add(searchStatus, BorderLayout.SOUTH);
        return searchPanel;
    }

    private JPanel createSidebar() {
        JList<String> onlineUsers = new JList<String>(online) {
            private static final long serialVersionUID = 1L;
            @Override public boolean getScrollableTracksViewportWidth() { return true; }
        };
        onlineUsers.setName("onlineUsers");
        onlineUsers.setBackground(UiTheme.SURFACE);
        onlineUsers.setForeground(UiTheme.TEXT);
        onlineUsers.setFont(UiTheme.BODY);
        onlineUsers.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        onlineUsers.setFixedCellHeight(48);
        onlineUsers.setVisibleRowCount(8);
        onlineUsers.setCellRenderer(new UserRenderer());
        onlineUsers.getAccessibleContext().setAccessibleName("Connected users");
        onlineTitle.setName("onlineCount");
        onlineTitle.setFont(UiTheme.LABEL.deriveFont(Font.BOLD, 14f));
        onlineTitle.setForeground(UiTheme.TEXT);
        presenceStatus.setName("presenceStatus");
        presenceStatus.setFont(UiTheme.SMALL);
        presenceStatus.setForeground(UiTheme.MUTED);
        JPanel presenceHeader = UiTheme.surfacePanel(new BorderLayout(0, 4));
        presenceHeader.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, UiTheme.BORDER),
                BorderFactory.createEmptyBorder(16, 14, 14, 14)));
        presenceHeader.add(onlineTitle, BorderLayout.NORTH);
        presenceHeader.add(presenceStatus, BorderLayout.SOUTH);
        JScrollPane presenceScroll = new JScrollPane(onlineUsers);
        presenceScroll.setBorder(BorderFactory.createEmptyBorder());
        presenceScroll.getViewport().setBackground(UiTheme.SURFACE);
        presenceScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        presenceScroll.getVerticalScrollBar().setUnitIncrement(48);
        JPanel sidebar = UiTheme.surfacePanel(new BorderLayout());
        sidebar.setPreferredSize(new Dimension(200, 0));
        sidebar.setMinimumSize(new Dimension(190, 0));
        sidebar.add(presenceHeader, BorderLayout.NORTH);
        sidebar.add(presenceScroll, BorderLayout.CENTER);
        return sidebar;
    }

    /** A restored session replays history; retain its filter, but never duplicate its messages. */
    void beginSession(String signedInUsername) {
        boolean sameUser = Objects.equals(username, signedInUsername);
        username = signedInUsername;
        entries.clear();
        characters = 0;
        clearUnread();
        if (signedInUsername == null || !sameUser) {
            search.setText("");
        }
        render(true);
        setReconnecting(false);
        setPresenceUnavailable();
    }

    void endSession() {
        beginSession(null);
    }

    void addMessage(ChatProtocol.ChatMessage message, boolean historical) {
        Objects.requireNonNull(message, "message");
        String timestamp = message.timestampMillis() == 0 ? "Earlier"
                : timestampFormat.format(Instant.ofEpochMilli(message.timestampMillis()));
        append(new Entry(message.username(), timestamp, message.text()));
        if (!historical && username != null && !message.username().equals(username) && !windowFocused) {
            if (unread < Integer.MAX_VALUE) {
                unread++;
            }
            onUnreadChanged.accept(unread);
        }
    }

    void addNotice(String notice) {
        append(new Entry(notice));
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
        presenceStatus.setText("Connected users");
    }

    void setPresenceUnavailable() {
        online.clear();
        onlineTitle.setText("Online");
        presenceStatus.setText("Disconnected");
    }

    void setReconnecting(boolean reconnecting) {
        reconnectBanner.setVisible(reconnecting);
        revalidate();
        repaint();
    }

    int unreadCount() { return unread; }

    private void clearUnread() {
        if (unread == 0) {
            return;
        }
        unread = 0;
        onUnreadChanged.accept(0);
    }

    private void append(Entry entry) {
        entries.addLast(entry);
        characters += entry.length;
        while (entries.size() > MAX_MESSAGES || characters > MAX_CHARACTERS) {
            characters -= entries.removeFirst().length;
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
            // Notices can report failed sends: keep them visible during message searches.
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
        StyledDocument document = messages.getStyledDocument();
        if (filterChanged) {
            try {
                document.remove(0, document.getLength());
                for (Entry entry : matching) {
                    insertStyled(document, entry);
                }
            } catch (BadLocationException impossible) {
                throw new IllegalStateException("Could not filter chat messages", impossible);
            }
            messages.setCaretPosition(0);
            visible = matching;
            SwingUtilities.invokeLater(() -> {
                if (displayRevision == revision) {
                    messageScroll.getVerticalScrollBar().setValue(0);
                }
            });
            return;
        }

        // Change only the evicted prefix and appended suffix to preserve copy selections.
        int removedEntries = matching.isEmpty() ? visible.size() : visible.indexOf(matching.get(0));
        if (removedEntries < 0) {
            removedEntries = visible.size();
        }
        int removedCharacters = 0;
        for (int i = 0; i < removedEntries; i++) {
            removedCharacters += visible.get(i).length;
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
                document.remove(0, removedCharacters);
            }
            for (int i = overlap; i < matching.size(); i++) {
                insertStyled(document, matching.get(i));
            }
            int length = document.getLength();
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

    private void insertStyled(StyledDocument document, Entry entry) throws BadLocationException {
        if (entry.chat) {
            int start = document.getLength();
            document.insertString(start, entry.author, nameStyle);
            document.insertString(document.getLength(), "   " + entry.timestamp + "\n", timestampStyle);
            setParagraphSpacing(document, start, document.getLength() - start, 3);
            start = document.getLength();
            document.insertString(start, entry.body + "\n", bodyStyle);
            setParagraphSpacing(document, start, entry.body.length() + 1, 0);
        } else {
            int start = document.getLength();
            document.insertString(start, entry.body + "\n", noticeStyle);
            setParagraphSpacing(document, start, entry.body.length() + 1, 0);
        }
        int gapStart = document.getLength();
        document.insertString(gapStart, "\n", gapStyle);
        setParagraphSpacing(document, gapStart, 1, 0);
    }

    private static void setParagraphSpacing(StyledDocument document, int offset, int length, float after) {
        SimpleAttributeSet paragraph = new SimpleAttributeSet();
        StyleConstants.setSpaceAbove(paragraph, 0);
        StyleConstants.setSpaceBelow(paragraph, after);
        StyleConstants.setLineSpacing(paragraph, 0);
        document.setParagraphAttributes(offset, length, paragraph, false);
    }

    private static SimpleAttributeSet textStyle(Font font, Color color) {
        SimpleAttributeSet style = new SimpleAttributeSet();
        StyleConstants.setFontFamily(style, font.getFamily());
        StyleConstants.setFontSize(style, font.getSize());
        StyleConstants.setBold(style, font.isBold());
        StyleConstants.setForeground(style, color);
        return style;
    }

    private static final class WrappingEditorKit extends StyledEditorKit {
        private static final long serialVersionUID = 1L;

        @Override
        public ViewFactory getViewFactory() {
            ViewFactory defaults = super.getViewFactory();
            return element -> {
                if (AbstractDocument.ContentElementName.equals(element.getName())) {
                    return new LabelView(element) {
                        @Override
                        public float getMinimumSpan(int axis) {
                            return axis == View.X_AXIS ? 0 : super.getMinimumSpan(axis);
                        }
                    };
                }
                return defaults.create(element);
            };
        }
    }

    private final class UserRenderer extends JPanel implements ListCellRenderer<String> {
        private static final long serialVersionUID = 1L;
        private final JLabel avatar = new JLabel();
        private final JLabel name = new JLabel();
        private final JLabel self = new JLabel();

        UserRenderer() {
            super(new BorderLayout(10, 0));
            name.setFont(UiTheme.LABEL);
            name.setForeground(UiTheme.TEXT);
            self.setFont(UiTheme.SMALL);
            self.setForeground(UiTheme.MUTED);
            add(avatar, BorderLayout.WEST);
            add(name, BorderLayout.CENTER);
            add(self, BorderLayout.EAST);
        }

        @Override
        protected void paintChildren(Graphics graphics) {
            // List renderers are painted as stamps, outside the normal layout hierarchy.
            doLayout();
            super.paintChildren(graphics);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends String> list, String value,
                                                      int index, boolean selected, boolean focused) {
            setBackground(selected ? UiTheme.BLUE_TINT : UiTheme.SURFACE);
            setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 0, 1, 0, focused ? UiTheme.PRIMARY : UiTheme.BORDER),
                    BorderFactory.createEmptyBorder(7, 12, 7, 12)));
            avatar.setIcon(new AvatarIcon(value));
            name.setText(value);
            setToolTipText(value);
            self.setText(Objects.equals(value, username) ? "you" : "");
            getAccessibleContext().setAccessibleName(value + (Objects.equals(value, username) ? ", you" : ""));
            return this;
        }
    }

    private static final class AvatarIcon implements Icon {
        private final String initial;

        AvatarIcon(String username) {
            initial = username.substring(0, 1).toUpperCase(Locale.ROOT);
        }

        @Override public int getIconWidth() { return 30; }
        @Override public int getIconHeight() { return 30; }

        @Override
        public void paintIcon(Component component, Graphics graphics, int x, int y) {
            Graphics2D draw = (Graphics2D) graphics.create();
            try {
                draw.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                draw.setColor(new Color(230, 238, 254));
                draw.fillOval(x, y, 28, 28);
                draw.setColor(UiTheme.PRIMARY);
                draw.setFont(UiTheme.LABEL.deriveFont(Font.BOLD));
                int textWidth = draw.getFontMetrics().stringWidth(initial);
                draw.drawString(initial, x + (28 - textWidth) / 2, y + 19);
                draw.setColor(UiTheme.SURFACE);
                draw.fillOval(x + 20, y + 20, 10, 10);
                draw.setColor(UiTheme.SUCCESS);
                draw.fillOval(x + 22, y + 22, 6, 6);
            } finally {
                draw.dispose();
            }
        }
    }

    private static final class SearchIcon implements Icon {
        @Override public int getIconWidth() { return 16; }
        @Override public int getIconHeight() { return 16; }

        @Override
        public void paintIcon(Component component, Graphics graphics, int x, int y) {
            Graphics2D draw = (Graphics2D) graphics.create();
            try {
                draw.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                draw.setColor(UiTheme.MUTED);
                draw.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                draw.drawOval(x + 2, y + 2, 8, 8);
                draw.drawLine(x + 9, y + 9, x + 14, y + 14);
            } finally {
                draw.dispose();
            }
        }
    }

    private static final class Entry {
        final int length;
        final String searchable;
        final boolean chat;
        final String author;
        final String timestamp;
        final String body;

        Entry(String author, String timestamp, String body) {
            this.author = author;
            this.timestamp = timestamp;
            this.body = body;
            chat = author != null;
            length = body.length() + 2 + (chat ? author.length() + timestamp.length() + 4 : 0);
            searchable = chat ? (author + "\n" + body).toLowerCase(Locale.ROOT) : "";
        }

        Entry(String notice) {
            this(null, null, notice);
        }
    }
}
