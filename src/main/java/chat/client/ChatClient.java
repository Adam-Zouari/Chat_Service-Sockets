package chat.client;

import chat.protocol.ChatProtocol;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import javax.swing.text.AbstractDocument;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.DocumentFilter;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GraphicsEnvironment;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.Arrays;
import java.util.List;

/** Swing presentation only; socket work and reconnects belong to ChatConnection. */
public final class ChatClient implements ChatConnection.Listener {
    private static final String HELP_TEXT = "Write a message and press Enter or Send to chat.\n\n"
            + "The sidebar updates automatically as people join and leave.\n"
            + "Search filters recent messages by username or message text on this computer.\n"
            + "Clear the search to see all recent messages again.\n"
            + "The window title counts new messages while you are away.\n"
            + "Use Log out to return to the login form.\n\n"
            + "If the server stops, the client retries automatically every 3 seconds.\n"
            + "Your session is restored while this window stays open, for up to 7 days.\n"
            + "Logging out or closing the window requires a new login.\n"
            + "Your password is never saved.";

    private final String address;
    private final ChatConnection connection;
    private final JFrame frame = new JFrame("Socket Chat");
    private final CardLayout cards = new CardLayout();
    private final JPanel content = new JPanel(cards);
    private final AuthenticationForm authentication = new AuthenticationForm(this::authenticate);
    private final ChatView chatView = new ChatView(count -> frame.setTitle(
            count == 0 ? "Socket Chat" : "(" + count + ") Socket Chat"));
    private final JTextField input = new JTextField();
    private final JButton sendButton = new JButton("Send");
    private final JButton logoutButton = new JButton("Log out");
    private final JButton helpButton = new JButton("Help");
    private final JTextArea status = new JTextArea(1, 1);
    private final JLabel identity = new JLabel("A simple place to connect.");
    private final JLabel connectionStatus = new JLabel("Connecting");
    private boolean ready;
    private boolean authenticated;
    private boolean reconnectingSession;
    private boolean closed;

    private ChatClient(String host, int port) {
        address = host + ":" + port;
        connection = new ChatConnection(host, port, this);
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        frame.setMinimumSize(new Dimension(740, 660));
        frame.setSize(960, 720);
        frame.setLocationByPlatform(true);
        frame.getContentPane().setBackground(UiTheme.BACKGROUND);
        content.setBackground(UiTheme.BACKGROUND);
        frame.add(createHeader(), BorderLayout.NORTH);

        content.add(authentication, "authentication");
        content.add(createChatPanel(), "chat");
        frame.add(content, BorderLayout.CENTER);
        showAuthentication();
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                closed = true;
                authentication.clearSecrets();
                connection.close();
                frame.dispose();
            }
        });
        frame.addWindowFocusListener(new WindowAdapter() {
            @Override
            public void windowGainedFocus(WindowEvent event) {
                chatView.setWindowFocused(true);
            }

            @Override
            public void windowLostFocus(WindowEvent event) {
                chatView.setWindowFocused(false);
            }
        });
        frame.setVisible(true);
        authentication.username.requestFocusInWindow();
        connection.start();
    }

    public static void main(String[] args) {
        String host = args.length > 0 ? args[0] : "127.0.0.1";
        int port = ChatProtocol.PORT;
        try {
            if (args.length > 2 || host.isBlank()) {
                throw new IllegalArgumentException();
            }
            if (args.length > 1) {
                port = Integer.parseInt(args[1]);
            }
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException invalidArguments) {
            System.err.println("Usage: java -cp build/classes/main chat.client.ChatClient [host] [port (1-65535)]");
            return;
        }
        if (GraphicsEnvironment.isHeadless()) {
            System.err.println("ChatClient needs a graphical desktop to display its window.");
            return;
        }
        final int serverPort = port;
        SwingUtilities.invokeLater(() -> {
            UiTheme.install();
            new ChatClient(host, serverPort);
        });
    }

    private JPanel createHeader() {
        helpButton.addActionListener(event -> showHelp());
        logoutButton.addActionListener(event -> logout());
        UiTheme.styleSecondary(helpButton);
        UiTheme.styleSecondary(logoutButton);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.setOpaque(false);
        actions.add(helpButton);
        actions.add(logoutButton);

        JLabel brand = new JLabel("Socket Chat");
        brand.setFont(UiTheme.HEADING);
        brand.setForeground(UiTheme.TEXT);
        identity.setFont(UiTheme.SMALL);
        identity.setForeground(UiTheme.MUTED);
        JPanel branding = new JPanel(new BorderLayout(0, 4));
        branding.setOpaque(false);
        branding.add(brand, BorderLayout.NORTH);
        branding.add(identity, BorderLayout.SOUTH);

        connectionStatus.setFont(UiTheme.LABEL);
        setConnectionState("Connecting", UiTheme.WARNING);
        JLabel serverAddress = new JLabel(address);
        serverAddress.setFont(UiTheme.SMALL);
        serverAddress.setForeground(UiTheme.MUTED);
        JPanel connectionInfo = new JPanel(new BorderLayout(0, 4));
        connectionInfo.setOpaque(false);
        connectionInfo.add(connectionStatus, BorderLayout.NORTH);
        connectionInfo.add(serverAddress, BorderLayout.SOUTH);
        JPanel right = new JPanel(new BorderLayout(16, 0));
        right.setOpaque(false);
        right.add(connectionInfo, BorderLayout.CENTER);
        right.add(actions, BorderLayout.EAST);

        JPanel bar = new JPanel(new BorderLayout(16, 0));
        bar.setBackground(UiTheme.SURFACE);
        bar.setBorder(BorderFactory.createEmptyBorder(16, 20, 16, 20));
        bar.add(branding, BorderLayout.CENTER);
        bar.add(right, BorderLayout.EAST);
        UiTheme.styleNotice(status);
        status.setFont(UiTheme.SMALL);
        status.setBorder(BorderFactory.createEmptyBorder(0, 20, 10, 20));
        status.setPreferredSize(new Dimension(1, status.getFontMetrics(status.getFont()).getHeight() * 2 + 10));
        status.setVisible(false);
        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(UiTheme.SURFACE);
        header.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, UiTheme.BORDER));
        header.add(bar, BorderLayout.CENTER);
        header.add(status, BorderLayout.SOUTH);
        return header;
    }

    private JPanel createChatPanel() {

        input.setToolTipText("Write a message and press Enter or Send.");
        input.getAccessibleContext().setAccessibleName("Message");
        UiTheme.styleInput(input);
        input.putClientProperty("JTextField.placeholderText", "Type a message...");
        input.setPreferredSize(new Dimension(0, 40));
        UiTheme.stylePrimary(sendButton);
        sendButton.setPreferredSize(new Dimension(84, 40));
        installLimit(input, ChatProtocol.MAX_MESSAGE_LENGTH,
                () -> setNotice("Messages are limited to "
                        + ChatProtocol.MAX_MESSAGE_LENGTH + " characters."));
        input.addActionListener(event -> sendInput());
        sendButton.addActionListener(event -> sendInput());
        JPanel composer = new JPanel(new BorderLayout(8, 0));
        composer.setBackground(UiTheme.SURFACE);
        composer.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, UiTheme.BORDER),
                BorderFactory.createEmptyBorder(12, 16, 16, 16)));
        composer.add(input, BorderLayout.CENTER);
        composer.add(sendButton, BorderLayout.EAST);

        JPanel chat = new JPanel(new BorderLayout());
        chat.setBackground(UiTheme.BACKGROUND);
        chat.add(chatView, BorderLayout.CENTER);
        chat.add(composer, BorderLayout.SOUTH);
        return chat;
    }

    private void authenticate() {
        if (!ready || closed || authenticated) {
            return;
        }
        char[] password = authentication.password();
        char[] confirmation = authentication.confirmation();
        try {
            String username = authentication.username();
            String error = validateCredentials(authentication.isSignup(), username, password, confirmation);
            if (error != null) {
                authentication.setError(error);
                return;
            }
            if (!connection.authenticate(authentication.isSignup(), username, password)) {
                authentication.setError("Connection is busy. Please try again in a moment.");
                return;
            }
            authentication.clearSecrets();
            authentication.setError("");
            authentication.setAvailability(true, true);
            setNotice(authentication.isSignup() ? "Creating your account..." : "Signing you in...");
        } finally {
            Arrays.fill(password, '\0');
            Arrays.fill(confirmation, '\0');
        }
    }

    static String validateCredentials(boolean signup, String username, char[] password, char[] confirmation) {
        if (!ChatProtocol.validUsername(username)) {
            return "Use 1-32 letters, digits or _, starting with a letter; login/signup are reserved.";
        }
        if (password.length == 0) {
            return "Enter your password.";
        }
        if (password.length > ChatProtocol.MAX_PASSWORD_LENGTH || !allowedPassword(password)) {
            return "Use at most " + ChatProtocol.MAX_PASSWORD_LENGTH
                    + " password characters, without control characters.";
        }
        if (signup) {
            boolean uppercase = false;
            boolean digit = false;
            boolean special = false;
            for (char ch : password) {
                uppercase |= Character.isUpperCase(ch);
                digit |= Character.isDigit(ch);
                special |= !Character.isLetterOrDigit(ch) && !Character.isWhitespace(ch);
            }
            if (password.length < 8 || !uppercase || !digit || !special) {
                return "Use 8-128 characters with an uppercase letter, a number and a symbol.";
            }
            if (!Arrays.equals(password, confirmation)) {
                return "Passwords do not match.";
            }
        }
        return null;
    }

    private static boolean allowedPassword(char[] password) {
        for (int i = 0; i < password.length; i++) {
            char ch = password[i];
            if (Character.isISOControl(ch) || ch == '\u2028' || ch == '\u2029') {
                return false;
            }
            if (Character.isHighSurrogate(ch)) {
                if (++i == password.length || !Character.isLowSurrogate(password[i])) {
                    return false;
                }
            } else if (Character.isLowSurrogate(ch)) {
                return false;
            }
        }
        return true;
    }

    private void sendInput() {
        if (sendMessage(input.getText())) {
            input.setText("");
        }
    }

    private boolean sendMessage(String text) {
        if (closed || !authenticated) {
            return false;
        }
        if (text.isBlank()) {
            setNotice("Enter a message.");
            return false;
        }
        if (text.length() > ChatProtocol.MAX_MESSAGE_LENGTH || !ChatProtocol.isAllowedText(text)) {
            setNotice("Use at most " + ChatProtocol.MAX_MESSAGE_LENGTH
                    + " characters, without control characters.");
            return false;
        }
        if (!connection.sendMessage(text)) {
            setNotice("Sending is busy. Wait a moment and try again.");
            return false;
        }
        return true;
    }

    private void showHelp() {
        JOptionPane.showMessageDialog(frame, HELP_TEXT, "Chat help", JOptionPane.INFORMATION_MESSAGE);
    }

    private void logout() {
        showAuthentication();
        setConnectionState("Connecting", UiTheme.WARNING);
        setNotice("You have logged out. Connecting for your next sign-in...");
        connection.logout();
    }

    private void showAuthentication() {
        showAuthentication(true);
    }

    private void showAuthentication(boolean clearSecrets) {
        boolean returningToForm = authenticated || reconnectingSession;
        ready = false;
        authenticated = false;
        reconnectingSession = false;
        chatView.endSession();
        chatView.setReconnecting(false);
        identity.setText("A simple place to connect.");
        identity.setToolTipText(null);
        if (clearSecrets) {
            authentication.clearSecrets();
        }
        authentication.setError("");
        authentication.setAvailability(false, false);
        input.setText("");
        input.setEnabled(false);
        sendButton.setEnabled(false);
        logoutButton.setEnabled(false);
        logoutButton.setVisible(false);
        cards.show(content, "authentication");
        frame.getRootPane().setDefaultButton(authentication.submit);
        if (returningToForm) {
            authentication.username.requestFocusInWindow();
        }
    }

    private void showReconnectingChat() {
        ready = false;
        authenticated = false;
        reconnectingSession = true;
        chatView.setPresenceUnavailable();
        chatView.setReconnecting(true);
        input.setEnabled(false);
        sendButton.setEnabled(false);
        logoutButton.setEnabled(true);
        logoutButton.setVisible(true);
        setConnectionState("Reconnecting", UiTheme.WARNING);
        cards.show(content, "chat");
    }

    @Override
    public void onStatus(String description) {
        if (!closed) {
            if (reconnectingSession) {
                setConnectionState("Reconnecting", UiTheme.WARNING);
                setNotice("");
            } else {
                setNotice(description);
            }
        }
    }

    @Override
    public void onReady() {
        if (!closed) {
            if (reconnectingSession) {
                showAuthentication();
            }
            ready = true;
            authentication.setAvailability(true, false);
            setConnectionState("Connected", UiTheme.SUCCESS);
            setNotice("");
        }
    }

    @Override
    public void onAuthenticated(String username) {
        if (closed) {
            return;
        }
        authenticated = true;
        reconnectingSession = false;
        ready = false;
        authentication.clearSecrets();
        authentication.setAvailability(false, false);
        chatView.beginSession(username);
        chatView.setWindowFocused(frame.isFocused());
        identity.setText("General chat  \u00b7  " + username);
        identity.setToolTipText("Signed in as " + username);
        chatView.setReconnecting(false);
        input.setEnabled(true);
        sendButton.setEnabled(true);
        logoutButton.setEnabled(true);
        logoutButton.setVisible(true);
        cards.show(content, "chat");
        frame.getRootPane().setDefaultButton(sendButton);
        setConnectionState("Connected", UiTheme.SUCCESS);
        setNotice("");
        input.requestFocusInWindow();
    }

    @Override
    public void onAuthenticationError(String explanation) {
        if (!closed) {
            authentication.clearSecrets();
            authentication.setAvailability(ready, false);
            authentication.setError(explanation);
            setNotice("");
        }
    }

    @Override
    public void onMessage(String line) {
        if (!closed && authenticated) {
            chatView.addNotice(line);
        }
    }

    @Override
    public void onChatMessage(ChatProtocol.ChatMessage message, boolean historical) {
        if (!closed && authenticated) {
            chatView.setWindowFocused(frame.isFocused());
            chatView.addMessage(message, historical);
        }
    }

    @Override
    public void onOnlineUsers(List<String> users) {
        if (!closed && authenticated) {
            chatView.setOnlineUsers(users);
        }
    }

    @Override
    public void onDisconnected(String explanation) {
        if (!closed) {
            if (connection.canResumeSession()) {
                showReconnectingChat();
            } else {
                showAuthentication(authenticated || reconnectingSession);
            }
            setConnectionState("Reconnecting", UiTheme.WARNING);
            setNotice(reconnectingSession ? "" : explanation);
        }
    }

    private static void installLimit(JTextField field, int limit, Runnable rejected) {
        ((AbstractDocument) field.getDocument()).setDocumentFilter(new DocumentFilter() {
            @Override
            public void insertString(FilterBypass bypass, int offset, String text,
                                     AttributeSet attributes) throws BadLocationException {
                replace(bypass, offset, 0, text, attributes);
            }

            @Override
            public void replace(FilterBypass bypass, int offset, int length, String text,
                                AttributeSet attributes) throws BadLocationException {
                int replacementLength = text == null ? 0 : text.length();
                if (bypass.getDocument().getLength() - length + replacementLength <= limit) {
                    super.replace(bypass, offset, length, text, attributes);
                } else {
                    rejected.run();
                }
            }
        });
    }

    private void setNotice(String description) {
        status.setText(description);
        status.setToolTipText(description.isBlank() ? null : description);
        status.setVisible(!description.isBlank());
        status.getParent().revalidate();
    }

    private void setConnectionState(String description, Color color) {
        connectionStatus.setText("\u25cf  " + description);
        connectionStatus.setForeground(color);
    }

    /** An ordinary form that can also be exercised without creating a desktop window. */
    static final class AuthenticationForm extends JPanel {
        private static final long serialVersionUID = 1L;
        private final JToggleButton loginMode = new JToggleButton("Log in", true);
        private final JToggleButton signupMode = new JToggleButton("Sign up");
        private final JLabel title = new JLabel("Welcome back");
        private final JLabel subtitle = new JLabel("Sign in to join the conversation.");
        private final JTextField username = new JTextField(24);
        private final JPasswordField password = new JPasswordField(24);
        private final JPasswordField confirmation = new JPasswordField(24);
        private final JLabel confirmationLabel = new JLabel("Confirm password");
        private final JLabel guidance = new JLabel("8-128 characters: uppercase, number and symbol.");
        private final JCheckBox showPassword = new JCheckBox("Show password");
        private final JTextArea error = new JTextArea(2, 1);
        private final JButton submit = new JButton("Log in");
        private final char passwordEcho = password.getEchoChar() == 0 ? '*' : password.getEchoChar();
        private final char confirmationEcho = confirmation.getEchoChar() == 0 ? '*' : confirmation.getEchoChar();

        AuthenticationForm(Runnable onSubmit) {
            super(new GridBagLayout());
            setBackground(UiTheme.BACKGROUND);
            setBorder(BorderFactory.createEmptyBorder(8, 16, 8, 16));
            username.setName("username");
            password.setName("password");
            confirmation.setName("confirmPassword");
            showPassword.setName("showPassword");
            submit.setName("authenticate");
            loginMode.setName("loginMode");
            signupMode.setName("signupMode");
            error.setName("authenticationError");
            password.setEchoChar(passwordEcho);
            confirmation.setEchoChar(confirmationEcho);
            UiTheme.styleInput(username);
            UiTheme.styleInput(password);
            UiTheme.styleInput(confirmation);
            for (JTextField field : new JTextField[] {username, password, confirmation}) {
                field.setPreferredSize(new Dimension(1, 38));
            }
            username.getAccessibleContext().setAccessibleName("Username");
            password.getAccessibleContext().setAccessibleName("Password");
            confirmation.getAccessibleContext().setAccessibleName("Confirm password");
            username.setToolTipText("1-32 letters, digits or underscores, starting with a letter.");
            title.setFont(UiTheme.TITLE);
            title.setForeground(UiTheme.TEXT);
            subtitle.setFont(UiTheme.LABEL);
            subtitle.setForeground(UiTheme.MUTED);
            guidance.setFont(UiTheme.SMALL);
            guidance.setForeground(UiTheme.MUTED);
            showPassword.setOpaque(false);
            showPassword.setFont(UiTheme.LABEL);
            showPassword.setForeground(UiTheme.TEXT);
            showPassword.setBorder(BorderFactory.createEmptyBorder());
            UiTheme.stylePrimary(submit);
            submit.setPreferredSize(new Dimension(1, 38));

            ButtonGroup modes = new ButtonGroup();
            modes.add(loginMode);
            modes.add(signupMode);
            UiTheme.styleToggle(loginMode);
            UiTheme.styleToggle(signupMode);
            loginMode.setFont(UiTheme.SMALL);
            signupMode.setFont(UiTheme.SMALL);
            loginMode.setBorder(BorderFactory.createEmptyBorder(5, 8, 5, 8));
            signupMode.setBorder(BorderFactory.createEmptyBorder(5, 8, 5, 8));
            loginMode.setPreferredSize(new Dimension(60, 28));
            signupMode.setPreferredSize(new Dimension(64, 28));
            JPanel modeButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
            modeButtons.setOpaque(false);
            modeButtons.add(loginMode);
            modeButtons.add(signupMode);
            loginMode.addActionListener(event -> changeMode());
            signupMode.addActionListener(event -> changeMode());

            JPanel form = UiTheme.surfacePanel(new GridBagLayout());
            form.setName("authenticationCard");
            form.setBorder(BorderFactory.createCompoundBorder(form.getBorder(),
                    BorderFactory.createEmptyBorder(16, 16, 16, 16)));
            GridBagConstraints cell = new GridBagConstraints();
            cell.gridx = 0;
            cell.weightx = 1;
            cell.fill = GridBagConstraints.HORIZONTAL;
            cell.anchor = GridBagConstraints.WEST;
            cell.insets = new Insets(0, 0, 4, 0);
            JPanel heading = new JPanel(new BorderLayout(8, 0));
            heading.setOpaque(false);
            heading.add(title, BorderLayout.CENTER);
            heading.add(modeButtons, BorderLayout.EAST);
            addRow(form, cell, heading);
            addRow(form, cell, subtitle);
            addRow(form, cell, Box.createVerticalStrut(8));
            JLabel usernameLabel = new JLabel("Username");
            usernameLabel.setLabelFor(username);
            addRow(form, cell, usernameLabel);
            addRow(form, cell, username);
            addRow(form, cell, Box.createVerticalStrut(4));
            JLabel passwordLabel = new JLabel("Password");
            passwordLabel.setLabelFor(password);
            addRow(form, cell, passwordLabel);
            addRow(form, cell, password);
            addRow(form, cell, guidance);
            addRow(form, cell, Box.createVerticalStrut(4));
            confirmationLabel.setLabelFor(confirmation);
            addRow(form, cell, confirmationLabel);
            addRow(form, cell, confirmation);
            addRow(form, cell, showPassword);
            UiTheme.styleNotice(error);
            error.setFont(UiTheme.SMALL);
            error.setForeground(UiTheme.ERROR);
            error.setVisible(false);
            addRow(form, cell, error);
            addRow(form, cell, Box.createVerticalStrut(4));
            addRow(form, cell, submit);
            GridBagConstraints centered = new GridBagConstraints();
            centered.anchor = GridBagConstraints.CENTER;
            centered.fill = GridBagConstraints.HORIZONTAL;
            form.setPreferredSize(new Dimension(420, form.getPreferredSize().height));
            form.setMinimumSize(new Dimension(380, 0));
            add(form, centered);

            installLimit(username, 32, () -> setError("Usernames are limited to 32 characters."));
            installLimit(password, ChatProtocol.MAX_PASSWORD_LENGTH,
                    () -> setError("Passwords are limited to " + ChatProtocol.MAX_PASSWORD_LENGTH + " characters."));
            installLimit(confirmation, ChatProtocol.MAX_PASSWORD_LENGTH,
                    () -> setError("Passwords are limited to " + ChatProtocol.MAX_PASSWORD_LENGTH + " characters."));
            showPassword.addActionListener(event -> updatePasswordVisibility());
            submit.addActionListener(event -> onSubmit.run());
            username.addActionListener(event -> password.requestFocusInWindow());
            password.addActionListener(event -> {
                if (isSignup()) {
                    confirmation.requestFocusInWindow();
                } else if (submit.isEnabled()) {
                    onSubmit.run();
                }
            });
            confirmation.addActionListener(event -> {
                if (submit.isEnabled()) {
                    onSubmit.run();
                }
            });
            changeMode();
            setAvailability(false, false);
        }

        private static void addRow(JPanel panel, GridBagConstraints constraints, java.awt.Component component) {
            constraints.gridy++;
            panel.add(component, constraints);
        }

        private void changeMode() {
            clearSecrets();
            setError("");
            boolean signup = isSignup();
            title.setText(signup ? "Create your account" : "Welcome back");
            subtitle.setText(signup ? "Join the conversation in a moment." : "Sign in to join the conversation.");
            submit.setText(signup ? "Create account" : "Log in");
            confirmationLabel.setVisible(signup);
            confirmation.setVisible(signup);
            guidance.setVisible(signup);
            resizeCard();
            revalidate();
            repaint();
        }

        boolean isSignup() {
            return signupMode.isSelected();
        }

        String username() {
            return username.getText().trim();
        }

        char[] password() {
            return password.getPassword();
        }

        char[] confirmation() {
            return confirmation.getPassword();
        }

        void clearSecrets() {
            password.setText("");
            confirmation.setText("");
            showPassword.setSelected(false);
            updatePasswordVisibility();
        }

        private void updatePasswordVisibility() {
            password.setEchoChar(showPassword.isSelected() ? (char) 0 : passwordEcho);
            confirmation.setEchoChar(showPassword.isSelected() ? (char) 0 : confirmationEcho);
        }

        void setAvailability(boolean ready, boolean busy) {
            username.setEnabled(!busy);
            password.setEnabled(!busy);
            confirmation.setEnabled(!busy);
            showPassword.setEnabled(!busy);
            loginMode.setEnabled(!busy);
            signupMode.setEnabled(!busy);
            submit.setEnabled(ready && !busy);
            submit.setToolTipText(ready ? null : "Waiting for the server to connect. You can fill the form now.");
        }

        void setError(String description) {
            error.setText(description);
            error.setVisible(!description.isEmpty());
            error.setPreferredSize(null);
            error.setSize(384, Short.MAX_VALUE);
            error.setPreferredSize(new Dimension(1, error.getPreferredSize().height));
            error.setToolTipText(description.isEmpty() ? null : description);
            resizeCard();
            revalidate();
            repaint();
        }

        private void resizeCard() {
            JPanel card = (JPanel) error.getParent();
            if (card != null) {
                card.setPreferredSize(null);
                card.setPreferredSize(new Dimension(420, card.getPreferredSize().height));
            }
        }
    }
}
