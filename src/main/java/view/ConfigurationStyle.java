package view;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.border.Border;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.LayoutManager;
import java.awt.RenderingHints;

final class ConfigurationStyle {
    static final Color CANVAS = new Color(0xEEF4F4);
    static final Color SURFACE = Color.WHITE;
    static final Color BORDER = new Color(0xC8D6D5);
    static final Color TEXT = new Color(0x172B30);
    static final Color MUTED = new Color(0x4C6268);
    static final Color SELECTED = new Color(0xE0F1EE);
    static final Color TEAL = new Color(0x16695F);

    private ConfigurationStyle() {
    }

    static JPanel surface(LayoutManager layout) {
        JPanel panel = new JPanel(layout);
        panel.setBackground(SURFACE);
        return panel;
    }

    static JLabel heading(String text) {
        JLabel label = new JLabel(text);
        label.setFont(label.getFont().deriveFont(Font.BOLD, 16f));
        label.setForeground(TEXT);
        return label;
    }

    static void label(JLabel label) {
        label.setForeground(MUTED);
        label.setFont(label.getFont().deriveFont(Font.BOLD));
    }

    static Border inset(int vertical, int horizontal) {
        return BorderFactory.createEmptyBorder(vertical, horizontal, vertical, horizontal);
    }

    static Border divider(int top, int bottom) {
        return BorderFactory.createMatteBorder(top, 0, bottom, 0, BORDER);
    }

    static void field(JComponent field) {
        field.setBackground(SURFACE);
        field.setForeground(TEXT);
    }

    static JScrollPane scroll(JComponent content) {
        JScrollPane pane = new JScrollPane(content);
        pane.setBorder(BorderFactory.createEmptyBorder());
        pane.getViewport().setBackground(SURFACE);
        return pane;
    }

    static JButton primaryButton(String text) {
        return button(text, true);
    }

    static JButton secondaryButton(String text) {
        return button(text, false);
    }

    private static JButton button(String text, boolean primary) {
        JButton button = new JButton(text) {
            @Override
            protected void paintComponent(Graphics graphics) {
                Graphics2D paint = (Graphics2D) graphics.create();
                try {
                    paint.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    paint.setColor(buttonFill(primary));
                    paint.fillRoundRect(0, 0, getWidth(), getHeight(), 12, 12);
                    if (!primary) {
                        paint.setColor(BORDER);
                        paint.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 12, 12);
                    }
                    if (isFocusOwner()) {
                        paint.setColor(primary ? Color.WHITE : TEAL);
                        paint.drawRoundRect(3, 3, getWidth() - 7, getHeight() - 7, 8, 8);
                    }
                } finally {
                    paint.dispose();
                }
                super.paintComponent(graphics);
            }

            private Color buttonFill(boolean highlighted) {
                if (!isEnabled()) {
                    return highlighted ? new Color(0xD3DFDE) : CANVAS;
                }
                if (getModel().isPressed()) {
                    return highlighted ? TEAL.darker() : BORDER;
                }
                if (getModel().isRollover()) {
                    return highlighted ? new Color(0x10564E) : SELECTED;
                }
                return highlighted ? TEAL : SURFACE;
            }
        };
        button.setContentAreaFilled(false);
        button.setOpaque(false);
        button.setForeground(primary ? Color.WHITE : TEXT);
        button.setFont(button.getFont().deriveFont(primary ? Font.BOLD : Font.PLAIN));
        button.setBorder(inset(8, primary ? 15 : 12));
        button.setFocusPainted(false);
        button.setRolloverEnabled(true);
        return button;
    }
}
