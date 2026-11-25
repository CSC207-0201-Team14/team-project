package view;

import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JTabbedPane;
import javax.swing.WindowConstants;
import java.awt.Dimension;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** The application window: one tab per use case. Closing asks the panels first, then releases resources. */
public final class SentinelWindow extends JFrame {
    public SentinelWindow(JComponent configure, JComponent monitor, JComponent incidents, JComponent recover,
                          BooleanSupplier canClose, Runnable onClosed) {
        super("Sentinel");
        Objects.requireNonNull(canClose, "canClose");
        Objects.requireNonNull(onClosed, "onClosed");

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Configure", null, configure, "Use Case 1: configure a monitored system");
        tabs.addTab("Monitor", null, monitor, "Use Case 2: monitor service health");
        tabs.addTab("Incidents", null, incidents, "Use Case 3: investigate an incident");
        tabs.addTab("Recover", null, recover, "Use Case 4: recover a service and verify resolution");
        setContentPane(tabs);

        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                if (canClose.getAsBoolean()) {
                    dispose();
                }
            }

            @Override
            public void windowClosed(WindowEvent event) {
                onClosed.run();
            }
        });
        setMinimumSize(new Dimension(800, 420));
        setSize(1040, 640);
        setLocationByPlatform(true);
    }
}
