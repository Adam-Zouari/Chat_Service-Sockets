import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.util.Base64;

/** Shared limits and UTF-8 line framing for the server and desktop client. */
public final class ChatProtocol {
    public static final int PORT = 1903;
    public static final int MAX_LINE_LENGTH = 4096;
    public static final int MAX_PASSWORD_LENGTH = 128;
    public static final int MAX_MESSAGE_LENGTH = 2000;
    public static final String CHOICE_PROMPT = "Do you want to login or sign up? (login/signup)";
    public static final String USERNAME_PROMPT = "Enter your username:";
    public static final String PASSWORD_PROMPT = "Enter your password:";
    public static final String FORM_HELLO = "@forms";
    public static final String FORM_SESSION_HELLO = "@forms2";
    public static final String FORM_READY = "@ready";
    public static final String FORM_AUTH_PREFIX = "@auth\t";
    public static final String FORM_OK_PREFIX = "@ok\t";
    public static final String FORM_ERROR_PREFIX = "@error\t";
    public static final String FORM_SESSION_PREFIX = "@session\t";
    public static final String FORM_RESUME_PREFIX = "@resume\t";

    public static boolean validSessionToken(String token) {
        return token != null && token.matches("[A-Za-z0-9_-]{43}");
    }

    private ChatProtocol() { }

    public static String encodeField(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        try {
            return Base64.getEncoder().encodeToString(bytes);
        } finally {
            java.util.Arrays.fill(bytes, (byte) 0);
        }
    }

    public static String decodeField(String value) throws IOException {
        if (value.length() > MAX_LINE_LENGTH) {
            throw new IOException("Authentication field is too long.");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid authentication field.", e);
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } finally {
            java.util.Arrays.fill(bytes, (byte) 0);
        }
    }

    public static boolean validUsername(String username) {
        return username != null && username.matches("[A-Za-z][A-Za-z0-9_]{0,31}")
                && !username.equalsIgnoreCase("login") && !username.equalsIgnoreCase("signup");
    }

    public static boolean validPassword(String password) {
        if (password == null || password.length() < 8 || password.length() > MAX_PASSWORD_LENGTH
                || !isAllowedText(password)) {
            return false;
        }
        boolean uppercase = false;
        boolean digit = false;
        boolean special = false;
        for (int i = 0; i < password.length(); i++) {
            char ch = password.charAt(i);
            uppercase |= Character.isUpperCase(ch);
            digit |= Character.isDigit(ch);
            special |= !Character.isLetterOrDigit(ch) && !Character.isWhitespace(ch);
        }
        return uppercase && digit && special;
    }

    public static BufferedReader newUtf8Reader(InputStream input) {
        return new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)));
    }

    public static BufferedWriter newUtf8Writer(OutputStream output) {
        return new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8));
    }

    /** Reads LF or CRLF lines without allocating an unbounded string. */
    public static String readLine(Reader reader) throws IOException {
        StringBuilder line = new StringBuilder();
        int ch;
        while ((ch = reader.read()) != -1) {
            if (ch == '\n') {
                return line.toString();
            }
            if (ch == '\r') {
                if (reader.read() != '\n') {
                    throw new IOException("Lines must end with LF or CRLF.");
                }
                return line.toString();
            }
            if (line.length() == MAX_LINE_LENGTH) {
                throw new IOException("Input line is too long.");
            }
            line.append((char) ch);
        }
        return line.length() == 0 ? null : line.toString();
    }

    public static boolean isAllowedText(String text) {
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (Character.isISOControl(ch) || ch == '\u2028' || ch == '\u2029') {
                return false;
            }
            if (Character.isHighSurrogate(ch)) {
                if (++i == text.length() || !Character.isLowSurrogate(text.charAt(i))) {
                    return false;
                }
            } else if (Character.isLowSurrogate(ch)) {
                return false;
            }
        }
        return true;
    }
}
