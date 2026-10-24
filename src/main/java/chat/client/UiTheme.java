package chat.client;

import javax.swing.AbstractButton;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.UIManager;
import javax.swing.border.AbstractBorder;
import javax.swing.border.Border;
import javax.swing.plaf.basic.BasicButtonUI;
import javax.swing.plaf.basic.BasicGraphicsUtils;
import javax.swing.plaf.basic.BasicTextFieldUI;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Insets;
import java.awt.LayoutManager;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.BasicStroke;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.util.Arrays;

/** Small shared light theme using the Swing components already shipped with Java. */
final class UiTheme {
    static final Color BACKGROUND = new Color(0xF5F7FA);
    static final Color SURFACE = Color.WHITE;
    static final Color BORDER = new Color(0xDCE2EA);
    static final Color PRIMARY = new Color(0x2563EB);
    static final Color TEXT = new Color(0x1F2937);
    static final Color MUTED = new Color(0x6B7280);
    static final Color SUCCESS = new Color(0x16A34A);
    static final Color WARNING = new Color(0xD97706);
    static final Color ERROR = new Color(0xDC2626);
    static final Color BLUE_TINT = new Color(0xEFF6FF);
    static final Color SELECTION = new Color(0xDBEAFE);
    private static final Color DISABLED = new Color(0xE8EDF4);
    private static final String FONT_FAMILY = Arrays.asList(GraphicsEnvironment.getLocalGraphicsEnvironment()
            .getAvailableFontFamilyNames()).contains("Segoe UI") ? "Segoe UI" : Font.SANS_SERIF;
    static final Font BODY = new Font(FONT_FAMILY, Font.PLAIN, 14);
    static final Font LABEL = new Font(FONT_FAMILY, Font.PLAIN, 13);
    static final Font HEADING = new Font(FONT_FAMILY, Font.BOLD, 18);
    static final Font TITLE = new Font(FONT_FAMILY, Font.BOLD, 24);
    static final Font SMALL = new Font(FONT_FAMILY, Font.PLAIN, 12);

    private UiTheme() { }

    static void install() {
        UIManager.put("Panel.background", BACKGROUND);
        UIManager.put("Label.foreground", TEXT);
        UIManager.put("Label.font", LABEL);
        UIManager.put("Button.font", BODY);
        UIManager.put("Button.disabledText", MUTED);
        UIManager.put("ToggleButton.font", LABEL);
        UIManager.put("CheckBox.font", LABEL);
        UIManager.put("CheckBox.background", SURFACE);
        UIManager.put("CheckBox.foreground", TEXT);
        UIManager.put("TextField.font", BODY);
        UIManager.put("PasswordField.font", BODY);
        UIManager.put("TextArea.font", BODY);
        UIManager.put("TextPane.font", BODY);
        UIManager.put("TextField.foreground", TEXT);
        UIManager.put("PasswordField.foreground", TEXT);
        UIManager.put("TextField.selectionBackground", SELECTION);
        UIManager.put("PasswordField.selectionBackground", SELECTION);
        UIManager.put("TextField.caretForeground", PRIMARY);
        UIManager.put("PasswordField.caretForeground", PRIMARY);
        UIManager.put("List.font", BODY);
        UIManager.put("List.background", SURFACE);
        UIManager.put("List.foreground", TEXT);
        UIManager.put("List.selectionBackground", BLUE_TINT);
        UIManager.put("List.selectionForeground", TEXT);
        UIManager.put("ScrollPane.background", SURFACE);
        UIManager.put("Viewport.background", SURFACE);
        UIManager.put("ScrollBarUI", "javax.swing.plaf.basic.BasicScrollBarUI");
        UIManager.put("ScrollBar.width", 12);
        UIManager.put("ScrollBar.thumb", new Color(0xC2CAD6));
        UIManager.put("ScrollBar.track", BACKGROUND);
        UIManager.put("ToolTip.font", SMALL);
        UIManager.put("ToolTip.background", SURFACE);
        UIManager.put("ToolTip.foreground", TEXT);
        UIManager.put("OptionPane.background", SURFACE);
        UIManager.put("OptionPane.messageFont", BODY);
    }

    static void styleInput(JTextField field) {
        if (!(field instanceof JPasswordField)) {
            field.setUI(new BasicTextFieldUI() {
                @Override
                protected void paintSafely(Graphics graphics) {
                    super.paintSafely(graphics);
                    Object placeholder = field.getClientProperty("JTextField.placeholderText");
                    if (field.getText().isEmpty() && placeholder instanceof String) {
                        Insets insets = field.getInsets();
                        Graphics hint = graphics.create(insets.left, insets.top,
                                Math.max(0, field.getWidth() - insets.left - insets.right),
                                Math.max(0, field.getHeight() - insets.top - insets.bottom));
                        hint.setFont(field.getFont());
                        hint.setColor(MUTED);
                        int baseline = (field.getHeight() - insets.top - insets.bottom
                                - hint.getFontMetrics().getHeight()) / 2 + hint.getFontMetrics().getAscent();
                        hint.drawString((String) placeholder, 0, baseline);
                        hint.dispose();
                    }
                }
            });
        }
        field.setFont(BODY);
        field.setForeground(TEXT);
        field.setBackground(SURFACE);
        field.setCaretColor(PRIMARY);
        field.setSelectionColor(SELECTION);
        field.setSelectedTextColor(TEXT);
        field.setBorder(new InputBorder());
        field.setPreferredSize(new Dimension(field.getPreferredSize().width, 38));
        field.setMinimumSize(new Dimension(40, 38));
        field.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));
        field.addFocusListener(new FocusAdapter() {
            @Override public void focusGained(FocusEvent event) { field.repaint(); }
            @Override public void focusLost(FocusEvent event) { field.repaint(); }
        });
    }

    static void stylePrimary(JButton button) { styleButton(button, ButtonKind.PRIMARY); }
    static void styleSecondary(JButton button) { styleButton(button, ButtonKind.SECONDARY); }
    static void styleToggle(JToggleButton button) { styleButton(button, ButtonKind.TOGGLE); }
    static void styleLink(JButton button) { styleButton(button, ButtonKind.LINK); }

    private static void styleButton(AbstractButton button, ButtonKind kind) {
        button.setFont(kind == ButtonKind.PRIMARY ? BODY.deriveFont(Font.BOLD) : LABEL);
        button.setForeground(kind == ButtonKind.PRIMARY ? SURFACE : kind == ButtonKind.LINK ? PRIMARY : TEXT);
        button.setOpaque(false);
        button.setContentAreaFilled(false);
        button.setRolloverEnabled(true);
        button.setBorderPainted(false);
        button.setFocusPainted(true);
        button.setBorder(javax.swing.BorderFactory.createEmptyBorder(8, 14, 8, 14));
        button.setUI(new FlatButtonUI(kind));
        int height = kind == ButtonKind.LINK ? 28 : kind == ButtonKind.TOGGLE ? 32 : 38;
        int width = button.getPreferredSize().width;
        button.setPreferredSize(new Dimension(Math.max(width, kind == ButtonKind.LINK ? 0 : 76), height));
        button.setMinimumSize(new Dimension(40, height));
        button.setMaximumSize(new Dimension(Integer.MAX_VALUE, height));
    }

    static void styleNotice(JTextArea notice) {
        notice.setEditable(false);
        notice.setFocusable(false);
        notice.setOpaque(false);
        notice.setLineWrap(true);
        notice.setWrapStyleWord(true);
        notice.setFont(LABEL);
        notice.setForeground(MUTED);
        notice.setBorder(javax.swing.BorderFactory.createEmptyBorder());
    }

    static JPanel surfacePanel(LayoutManager layout) {
        JPanel panel = new SurfacePanel(layout);
        panel.setBackground(SURFACE);
        panel.setBorder(roundedBorder(BORDER, 8));
        return panel;
    }

    static Border roundedBorder(Color color, int radius) {
        return new RoundedBorder(color, radius);
    }

    private enum ButtonKind { PRIMARY, SECONDARY, TOGGLE, LINK }

    private static final class FlatButtonUI extends BasicButtonUI {
        private final ButtonKind kind;

        FlatButtonUI(ButtonKind kind) { this.kind = kind; }

        @Override
        public void paint(Graphics graphics, JComponent component) {
            AbstractButton button = (AbstractButton) component;
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            boolean active = button.getModel().isRollover() || button.getModel().isPressed();
            Color fill = SURFACE;
            if (!button.isEnabled()) {
                fill = DISABLED;
            } else if (kind == ButtonKind.PRIMARY) {
                fill = active ? new Color(0x1D4ED8) : PRIMARY;
            } else if (active || (kind == ButtonKind.TOGGLE && button.isSelected())) {
                fill = BLUE_TINT;
            }
            if (kind != ButtonKind.LINK || active) {
                g.setColor(fill);
                g.fillRoundRect(0, 0, component.getWidth(), component.getHeight(), 12, 12);
            }
            if (kind != ButtonKind.LINK) {
                g.setColor(kind == ButtonKind.PRIMARY ? fill
                        : kind == ButtonKind.TOGGLE && button.isSelected() ? PRIMARY : BORDER);
                g.drawRoundRect(0, 0, component.getWidth() - 1, component.getHeight() - 1, 12, 12);
            }
            if (button.isFocusOwner()) {
                g.setColor(kind == ButtonKind.PRIMARY ? new Color(0xBFDBFE) : PRIMARY);
                g.setStroke(new BasicStroke(2));
                g.drawRoundRect(2, 2, component.getWidth() - 5, component.getHeight() - 5, 10, 10);
            }
            g.dispose();
            super.paint(graphics, component);
        }

        @Override
        protected void paintText(Graphics g, AbstractButton button, Rectangle bounds, String text) {
            g.setColor(!button.isEnabled() ? MUTED : kind == ButtonKind.PRIMARY ? SURFACE
                    : kind == ButtonKind.LINK || (kind == ButtonKind.TOGGLE && button.isSelected()) ? PRIMARY : TEXT);
            BasicGraphicsUtils.drawStringUnderlineCharAt(g, text, button.getDisplayedMnemonicIndex(),
                    bounds.x, bounds.y + g.getFontMetrics().getAscent());
        }
    }

    private static final class RoundedBorder extends AbstractBorder {
        private static final long serialVersionUID = 1L;
        private final Color color;
        private final int radius;

        RoundedBorder(Color color, int radius) { this.color = color; this.radius = radius; }

        @Override
        public Insets getBorderInsets(java.awt.Component component, Insets insets) {
            insets.set(1, 1, 1, 1);
            return insets;
        }

        @Override
        public void paintBorder(java.awt.Component component, Graphics graphics, int x, int y, int width, int height) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(color);
            g.drawRoundRect(x, y, width - 1, height - 1, radius * 2, radius * 2);
            g.dispose();
        }
    }

    private static final class InputBorder extends AbstractBorder {
        private static final long serialVersionUID = 1L;

        @Override
        public Insets getBorderInsets(java.awt.Component component, Insets insets) {
            insets.set(8, 12, 8, 12);
            return insets;
        }

        @Override
        public void paintBorder(java.awt.Component component, Graphics graphics, int x, int y, int width, int height) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(component.isFocusOwner() ? PRIMARY : BORDER);
            g.setStroke(new BasicStroke(component.isFocusOwner() ? 2 : 1));
            g.drawRoundRect(x + 1, y + 1, width - 3, height - 3, 12, 12);
            g.dispose();
        }
    }

    private static final class SurfacePanel extends JPanel {
        private static final long serialVersionUID = 1L;

        SurfacePanel(LayoutManager layout) { super(layout); setOpaque(false); }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(getBackground());
            g.fillRoundRect(0, 0, getWidth(), getHeight(), 16, 16);
            g.dispose();
        }
    }
}
