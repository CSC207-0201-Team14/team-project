package view.monitor_service_health;

import entity.HealthCheckResult;
import entity.Service;
import interface_adapter.monitor_service_health.MonitoringController;
import interface_adapter.monitor_service_health.MonitoringViewModel;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.util.Objects;

public final class MonitoringView extends JPanel {

    private static final long serialVersionUID = 1L;

    private final MonitoringViewModel viewModel;
    private final JLabel errorLabel = new JLabel(" ");
    private final JLabel healthLabel = new JLabel("Health: UNKNOWN");

    private final DefaultTableModel tableModel = new DefaultTableModel(
            new String[]{"Time (UTC)", "Result", "Response (ms)", "Message"},
            0
    ) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };

    public MonitoringView(
            Service service,
            MonitoringController controller,
            MonitoringViewModel viewModel
    ) {
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(controller, "controller");
        this.viewModel = Objects.requireNonNull(viewModel, "viewModel");

        setLayout(new BorderLayout(8, 8));

        JButton checkButton = new JButton("Check once");
        JButton startButton = new JButton("Start");
        JButton stopButton = new JButton("Stop");
        JLabel statusLabel = new JLabel("Stopped");

        stopButton.setEnabled(false);

        checkButton.addActionListener(
                event -> controller.checkOnce(service)
        );

        startButton.addActionListener(event -> {
            boolean started = controller.startMonitoring(service);

            if (started) {
                startButton.setEnabled(false);
                stopButton.setEnabled(true);
                checkButton.setEnabled(false);
                statusLabel.setText("Monitoring");
            }
        });

        stopButton.addActionListener(event -> {
            controller.stopMonitoring(service.getId());

            startButton.setEnabled(true);
            stopButton.setEnabled(false);
            checkButton.setEnabled(true);
            statusLabel.setText("Stopped");
        });

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT));
        controls.add(new JLabel("Service: " + service.getName()));
        controls.add(checkButton);
        controls.add(startButton);
        controls.add(stopButton);
        controls.add(statusLabel);
        controls.add(healthLabel);

        JTable table = new JTable(tableModel);
        table.setFillsViewportHeight(true);

        add(controls, BorderLayout.NORTH);
        add(new JScrollPane(table), BorderLayout.CENTER);
        add(errorLabel, BorderLayout.SOUTH);

        viewModel.addPropertyChangeListener(event -> refresh());
        refresh();
    }

    private void refresh() {
        healthLabel.setText(
                "Health: " + viewModel.getHealthStatus().name()
        );
        tableModel.setRowCount(0);

        for (HealthCheckResult result : viewModel.getResults()) {
            String responseTime = "";

            if (result.getResponseTimeMs().isPresent()) {
                responseTime = Long.toString(
                        result.getResponseTimeMs().getAsLong()
                );
            }

            tableModel.addRow(new Object[]{
                    result.getTimestamp().toString(),
                    result.getOutcome().name(),
                    responseTime,
                    result.getMessage()
            });
        }

        String error = viewModel.getErrorMessage();

        if (error.isEmpty()) {
            errorLabel.setText(" ");
        } else {
            errorLabel.setText("Error: " + error);
        }
    }
}