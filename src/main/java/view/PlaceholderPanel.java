package view;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Font;

/** Holds the tab of a use case whose screen is not part of this build yet. It has no behaviour of its own. */
public final class PlaceholderPanel extends JPanel {
    public PlaceholderPanel(String heading) {
        super(new BorderLayout());
        JLabel title = new JLabel(heading);
        title.setFont(title.getFont().deriveFont(Font.BOLD, title.getFont().getSize2D() + 4f));
        title.setBorder(BorderFactory.createEmptyBorder(32, 32, 32, 32));
        add(title, BorderLayout.NORTH);
    }
}
