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
import java.awt.Font;
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
    private final JTextArea status = new JTextArea(2, 1);
    private final JLabel identity = new JLabel();
    private boolean ready;
    private boolean authenticated;
    private boolean reconnectingSession;
    private boolean closed;

    private ChatClient(String host, int port) {
        address = host + ":" + port;
        connection = new ChatConnection(host, port, this);
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        frame.setMinimumSize(new Dimension(620, 530));
        frame.setSize(760, 580);
        frame.setLocationByPlatform(true);

        JPanel header = new JPanel(new BorderLayout(12, 0));
        header.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        configureNotice(status);
        status.setText("Connecting to " + address + "...");
        header.add(status, BorderLayout.CENTER);
        frame.add(header, BorderLayout.NORTH);

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
        SwingUtilities.invokeLater(() -> new ChatClient(host, serverPort));
    }

    private JPanel createChatPanel() {
        JButton helpButton = new JButton("Help");
        helpButton.addActionListener(event -> showHelp());
        logoutButton.addActionListener(event -> logout());
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        actions.add(helpButton);
        actions.add(logoutButton);
        JPanel toolbar = new JPanel(new BorderLayout(8, 0));
        toolbar.setBorder(BorderFactory.createEmptyBorder(0, 12, 8, 12));
        toolbar.add(identity, BorderLayout.CENTER);
        toolbar.add(actions, BorderLayout.EAST);

        input.setToolTipText("Write a message and press Enter or Send.");
        installLimit(input, ChatProtocol.MAX_MESSAGE_LENGTH,
                () -> status.setText("Messages are limited to "
                        + ChatProtocol.MAX_MESSAGE_LENGTH + " characters."));
        input.addActionListener(event -> sendInput());
        sendButton.addActionListener(event -> sendInput());
        JPanel composer = new JPanel(new BorderLayout(8, 0));
        composer.setBorder(BorderFactory.createEmptyBorder(10, 12, 12, 12));
        composer.add(input, BorderLayout.CENTER);
        composer.add(sendButton, BorderLayout.EAST);

        JPanel chat = new JPanel(new BorderLayout());
        chat.add(toolbar, BorderLayout.NORTH);
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
            status.setText(authentication.isSignup() ? "Creating account..." : "Logging in...");
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
            status.setText("Enter a message.");
            return false;
        }
        if (text.length() > ChatProtocol.MAX_MESSAGE_LENGTH || !ChatProtocol.isAllowedText(text)) {
            status.setText("Use at most " + ChatProtocol.MAX_MESSAGE_LENGTH
                    + " characters, without control characters.");
            return false;
        }
        if (!connection.sendMessage(text)) {
            status.setText("Sending is busy. Wait a moment and try again.");
            return false;
        }
        return true;
    }

    private void showHelp() {
        JOptionPane.showMessageDialog(frame, HELP_TEXT, "Chat help", JOptionPane.INFORMATION_MESSAGE);
    }

    private void logout() {
        showAuthentication();
        status.setText("Logged out. Reconnecting for login...");
        connection.logout();
    }

    private void showAuthentication() {
        showAuthentication(true);
    }

    private void showAuthentication(boolean clearSecrets) {
        ready = false;
        authenticated = false;
        reconnectingSession = false;
        chatView.endSession();
        if (clearSecrets) {
            authentication.clearSecrets();
        }
        authentication.setError("");
        authentication.setAvailability(false, false);
        input.setText("");
        input.setEnabled(false);
        sendButton.setEnabled(false);
        logoutButton.setEnabled(false);
        cards.show(content, "authentication");
        frame.getRootPane().setDefaultButton(authentication.submit);
    }

    private void showReconnectingChat() {
        ready = false;
        authenticated = false;
        reconnectingSession = true;
        chatView.setPresenceUnavailable();
        input.setEnabled(false);
        sendButton.setEnabled(false);
        logoutButton.setEnabled(true);
        cards.show(content, "chat");
    }

    @Override
    public void onStatus(String description) {
        if (!closed) {
            status.setText(description);
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
            status.setText("Connected to " + address + ". Log in or create an account.");
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
        identity.setText("Signed in as " + username);
        input.setEnabled(true);
        sendButton.setEnabled(true);
        logoutButton.setEnabled(true);
        cards.show(content, "chat");
        frame.getRootPane().setDefaultButton(sendButton);
        status.setText("Connected to " + address + ". Ready to chat.");
        input.requestFocusInWindow();
    }

    @Override
    public void onAuthenticationError(String explanation) {
        if (!closed) {
            authentication.clearSecrets();
            authentication.setAvailability(ready, false);
            authentication.setError(explanation);
            status.setText("Login or signup was not completed. Check the form and try again.");
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
            status.setText(explanation);
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

    private static void configureNotice(JTextArea notice) {
        notice.setEditable(false);
        notice.setFocusable(false);
        notice.setOpaque(false);
        notice.setLineWrap(true);
        notice.setWrapStyleWord(true);
        notice.setFont(new JLabel().getFont());
    }

    /** An ordinary form that can also be exercised without creating a desktop window. */
    static final class AuthenticationForm extends JPanel {
        private static final long serialVersionUID = 1L;
        private final JToggleButton loginMode = new JToggleButton("Log in", true);
        private final JToggleButton signupMode = new JToggleButton("Sign up");
        private final JLabel title = new JLabel("Log in to Socket Chat");
        private final JTextField username = new JTextField(24);
        private final JPasswordField password = new JPasswordField(24);
        private final JPasswordField confirmation = new JPasswordField(24);
        private final JLabel confirmationLabel = new JLabel("Confirm password");
        private final JLabel guidance = new JLabel("Enter your username and password.");
        private final JCheckBox showPassword = new JCheckBox("Show password");
        private final JTextArea error = new JTextArea(2, 1);
        private final JButton submit = new JButton("Log in");
        private final char passwordEcho = password.getEchoChar() == 0 ? '*' : password.getEchoChar();
        private final char confirmationEcho = confirmation.getEchoChar() == 0 ? '*' : confirmation.getEchoChar();

        AuthenticationForm(Runnable onSubmit) {
            super(new GridBagLayout());
            setBorder(BorderFactory.createEmptyBorder(12, 24, 24, 24));
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

            ButtonGroup modes = new ButtonGroup();
            modes.add(loginMode);
            modes.add(signupMode);
            JPanel modeButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            modeButtons.add(loginMode);
            modeButtons.add(signupMode);
            loginMode.addActionListener(event -> changeMode());
            signupMode.addActionListener(event -> changeMode());

            JPanel form = new JPanel(new GridBagLayout());
            GridBagConstraints cell = new GridBagConstraints();
            cell.gridx = 0;
            cell.weightx = 1;
            cell.fill = GridBagConstraints.HORIZONTAL;
            cell.anchor = GridBagConstraints.WEST;
            cell.insets = new Insets(0, 0, 6, 0);
            addRow(form, cell, modeButtons);
            title.setFont(title.getFont().deriveFont(Font.BOLD, 21f));
            addRow(form, cell, title);
            addRow(form, cell, Box.createVerticalStrut(6));
            addRow(form, cell, new JLabel("Username"));
            addRow(form, cell, username);
            addRow(form, cell, new JLabel("Password"));
            addRow(form, cell, password);
            addRow(form, cell, confirmationLabel);
            addRow(form, cell, confirmation);
            addRow(form, cell, showPassword);
            addRow(form, cell, guidance);
            configureNotice(error);
            error.setForeground(new Color(170, 35, 35));
            addRow(form, cell, error);
            addRow(form, cell, submit);
            cell.gridy++;
            cell.weighty = 1;
            add(form, cell);

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
            title.setText(signup ? "Create a Socket Chat account" : "Log in to Socket Chat");
            submit.setText(signup ? "Create account" : "Log in");
            confirmationLabel.setVisible(signup);
            confirmation.setVisible(signup);
            guidance.setText(signup
                    ? "8-128 characters, with an uppercase letter, a number and a symbol."
                    : "Enter your username and password.");
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
            error.setText(description.isEmpty() ? " " : description);
            error.setToolTipText(description.isEmpty() ? null : description);
        }
    }
}
