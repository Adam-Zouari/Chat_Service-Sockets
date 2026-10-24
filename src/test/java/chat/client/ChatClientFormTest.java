package chat.client;

import chat.protocol.ChatProtocol;

import javax.imageio.ImageIO;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicInteger;

/** Headless Swing interaction tests; no JFrame, reflective access, or network needed. */
public final class ChatClientFormTest {
    private ChatClientFormTest() { }

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        SwingUtilities.invokeAndWait(() -> {
            try {
                formsAndVisibility();
                validation();
            } catch (IOException failure) {
                throw new IllegalStateException(failure);
            }
        });
        System.out.println("PASS login/signup forms, masked/revealed passwords, offline/busy states and submit actions");
        System.out.println("PASS signup confirmation and credential validation");
    }

    private static void formsAndVisibility() throws IOException {
        AtomicInteger submissions = new AtomicInteger();
        ChatClient.AuthenticationForm form = new ChatClient.AuthenticationForm(submissions::incrementAndGet);
        JTextField username = named(form, "username", JTextField.class);
        JPasswordField password = named(form, "password", JPasswordField.class);
        JPasswordField confirm = named(form, "confirmPassword", JPasswordField.class);
        JCheckBox visibility = named(form, "showPassword", JCheckBox.class);
        JButton submit = named(form, "authenticate", JButton.class);
        JToggleButton login = named(form, "loginMode", JToggleButton.class);
        JToggleButton signup = named(form, "signupMode", JToggleButton.class);

        check(!form.isSignup() && !confirm.isVisible(), "Login must omit password confirmation");
        check(password.getEchoChar() != 0 && confirm.getEchoChar() != 0, "Passwords must start masked");
        check(username.isEnabled() && password.isEnabled() && !submit.isEnabled(),
                "Offline users should fill the form without being allowed to submit");
        username.setText("Alice");
        password.setText("SecurePassword9!");
        visibility.doClick();
        check(password.getEchoChar() == 0 && confirm.getEchoChar() == 0,
                "Show password must reveal both password fields");
        visibility.doClick();
        check(password.getEchoChar() != 0 && confirm.getEchoChar() != 0,
                "Unchecking Show password must mask both fields");
        render(form, "auth-login.png");

        visibility.doClick();
        signup.doClick();
        check(form.isSignup() && confirm.isVisible(), "Signup must require a confirmation field");
        check(password.getPassword().length == 0 && confirm.getPassword().length == 0,
                "Changing authentication mode must erase passwords");
        check(!visibility.isSelected() && password.getEchoChar() != 0 && confirm.getEchoChar() != 0,
                "Changing mode must reset visibility");
        check(form.username().equals("Alice"), "Changing mode should retain the username");
        check(submit.getText().equals("Create account"), "Signup needs a Create account button");

        password.setText("SecurePassword9!");
        confirm.setText("SecurePassword9!");
        form.setAvailability(true, false);
        render(form, "auth-signup.png");
        form.setError("That username is already in use. Choose another username, or switch to Log in "
                + "if you already have an account with this name.");
        render(form, "auth-signup-minimum.png", 604, 430);
        form.setError("");
        submit.doClick();
        check(submissions.get() == 1, "Clicking the form button must submit once");
        confirm.postActionEvent();
        check(submissions.get() == 2, "Enter in the confirmation field must submit once");
        form.setAvailability(true, true);
        check(!submit.isEnabled() && !username.isEnabled() && !signup.isEnabled(),
                "An in-flight login should prevent duplicate submissions and mode changes");
        confirm.postActionEvent();
        submit.doClick();
        check(submissions.get() == 2, "Busy forms must ignore Enter and button submissions");

        form.setAvailability(false, false);
        visibility.doClick();
        form.clearSecrets();
        check(password.getPassword().length == 0 && confirm.getPassword().length == 0,
                "A disconnect must clear both password fields");
        check(!visibility.isSelected() && password.getEchoChar() != 0 && confirm.getEchoChar() != 0,
                "Clearing credentials must restore masked mode");
        check(!submit.isEnabled() && username.isEnabled(), "Disconnected forms should remain editable");
        login.doClick();
        check(!form.isSignup() && !confirm.isVisible(), "Users must be able to return to login");
        form.setAvailability(true, false);
        password.postActionEvent();
        check(submissions.get() == 3, "Enter in the login password field must submit once");

        username.setText("a".repeat(33));
        check(username.getText().length() <= 32, "Username input must be bounded");
        password.setText("a".repeat(ChatProtocol.MAX_PASSWORD_LENGTH + 1));
        check(password.getPassword().length <= ChatProtocol.MAX_PASSWORD_LENGTH, "Password input must be bounded");
    }

    private static void validation() {
        char[] good = "SecurePassword9!".toCharArray();
        char[] other = "DifferentPassword9!".toCharArray();
        check(ChatClient.validateCredentials(true, "Alice", good, good) == null, "Valid signup rejected");
        check(ChatClient.validateCredentials(true, "Alice", good, other) != null,
                "Signup must reject mismatched passwords");
        check(ChatClient.validateCredentials(true, "Alice", good, new char[0]) != null,
                "Signup must require confirmation");
        check(ChatClient.validateCredentials(false, "Alice", good, new char[0]) == null,
                "Login should not require password confirmation");
        check(ChatClient.validateCredentials(false, "signup", good, good) != null, "Reserved username accepted");
        check(ChatClient.validateCredentials(false, "Alice", new char[0], good) != null, "Empty password accepted");
        check(ChatClient.validateCredentials(true, "Alice", "short".toCharArray(), "short".toCharArray()) != null,
                "Weak signup password accepted");
        check(ChatClient.validateCredentials(false, "Alice", "Secret\n9!".toCharArray(), good) != null,
                "Password containing a newline accepted");
    }

    private static <T extends Component> T named(Container parent, String name, Class<T> type) {
        Component result = find(parent, name);
        if (!type.isInstance(result)) {
            throw new AssertionError("Missing component: " + name);
        }
        return type.cast(result);
    }

    private static Component find(Container parent, String name) {
        for (Component component : parent.getComponents()) {
            if (name.equals(component.getName())) {
                return component;
            }
            if (component instanceof Container) {
                Component nested = find((Container) component, name);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }

    private static void render(ChatClient.AuthenticationForm form, String filename) throws IOException {
        render(form, filename, 736, 480);
    }

    private static void render(ChatClient.AuthenticationForm form, String filename, int width, int height)
            throws IOException {
        form.setSize(width, height);
        layout(form);
        BufferedImage image = new BufferedImage(form.getWidth(), form.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            form.printAll(graphics);
        } finally {
            graphics.dispose();
        }
        Path directory = Paths.get("build");
        Files.createDirectories(directory);
        ImageIO.write(image, "png", directory.resolve(filename).toFile());
    }

    private static void layout(Container container) {
        container.doLayout();
        for (Component component : container.getComponents()) {
            if (component instanceof Container) {
                layout((Container) component);
            }
        }
    }

    private static void check(boolean condition, String description) {
        if (!condition) {
            throw new AssertionError(description);
        }
    }
}
