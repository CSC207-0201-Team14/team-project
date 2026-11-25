package view.monitor_service_health;

import entity.HealthCheckResult;
import entity.MonitoredSystem;
import entity.Service;
import interface_adapter.monitor_service_health.MonitoringController;
import interface_adapter.monitor_service_health.MonitoringViewModel;

import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/** Saved configurations arrive from the configuration presenter; this view performs no file I/O. */
public final class MonitoringView extends JPanel {
    private static final long serialVersionUID = 1L;
    private final MonitoringController controller;
    private final MonitoringViewModel viewModel;
    private final JComboBox<MonitoredSystem> systemPicker = new JComboBox<>();
    private final JComboBox<Service> servicePicker = new JComboBox<>();
    private final JButton checkButton = new JButton("Check once");
    private final JButton startButton = new JButton("Start");
    private final JButton stopButton = new JButton("Stop");
    private final JButton stopAllButton = new JButton("Stop all");
    private final JLabel statusLabel = new JLabel("No service selected");
    private final JLabel healthLabel = new JLabel("Health: UNKNOWN");
    private final JLabel targetLabel = new JLabel("Save a system in Configure to begin.");
    private final JLabel noticeLabel = new JLabel(" ");
    private final JLabel errorLabel = new JLabel(" ");
    private List<MonitoredSystem> systems = List.of();
    private boolean updatingPickers;

    private final DefaultTableModel tableModel = new DefaultTableModel(
            new String[]{"Time (UTC)", "Result", "Response (ms)", "Message"}, 0) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };

    public MonitoringView(MonitoringController controller, MonitoringViewModel viewModel) {
        this.controller = Objects.requireNonNull(controller, "controller");
        this.viewModel = Objects.requireNonNull(viewModel, "viewModel");
        setLayout(new BorderLayout(8, 8));
        systemPicker.setName("monitorSystem");
        servicePicker.setName("monitorService");
        systemPicker.setRenderer(new ChoiceRenderer());
        servicePicker.setRenderer(new ChoiceRenderer());
        JPanel choices = new JPanel(new FlowLayout(FlowLayout.LEFT));
        choices.add(new JLabel("System"));
        choices.add(systemPicker);
        choices.add(new JLabel("Service"));
        choices.add(servicePicker);

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT));
        controls.add(checkButton);
        controls.add(startButton);
        controls.add(stopButton);
        controls.add(stopAllButton);
        controls.add(statusLabel);
        controls.add(healthLabel);
        JPanel header = new JPanel(new GridLayout(0, 1, 0, 4));
        header.add(choices);
        header.add(targetLabel);
        header.add(controls);
        add(header, BorderLayout.NORTH);
        JTable table = new JTable(tableModel);
        table.setFillsViewportHeight(true);
        add(new JScrollPane(table), BorderLayout.CENTER);
        JPanel messages = new JPanel(new GridLayout(0, 1));
        messages.add(noticeLabel);
        messages.add(errorLabel);
        add(messages, BorderLayout.SOUTH);

        systemPicker.addActionListener(event -> {
            if (!updatingPickers) {
                populateServices(null);
            }
        });
        servicePicker.addActionListener(event -> {
            if (!updatingPickers) {
                selectService();
            }
        });
        checkButton.addActionListener(event -> {
            Service selected = selectedService();
            if (selected != null) {
                controller.checkOnce(selected);
            }
        });
        startButton.addActionListener(event -> {
            Service selected = selectedService();
            if (selected != null) {
                controller.startMonitoring(selected);
                refresh();
            }
        });
        stopButton.addActionListener(event -> {
            Service selected = selectedService();
            if (selected != null) {
                controller.stopMonitoring(selected.getId());
                refresh();
            }
        });
        stopAllButton.addActionListener(event -> {
            controller.stopAll();
            refresh();
        });
        viewModel.addPropertyChangeListener(event -> refresh());
        refresh();
    }

    /** Updates the complete saved roster on the EDT, preserving the selection by ID. */
    public void showSystems(List<MonitoredSystem> configured) {
        MonitoredSystem previousSystem = (MonitoredSystem) systemPicker.getSelectedItem();
        Service previousService = selectedService();
        UUID systemId = previousSystem == null ? null : previousSystem.getId();
        UUID serviceId = previousService == null ? null : previousService.getId();
        systems = List.copyOf(configured);
        int stopped = controller.reconcileServices(systems.stream()
                .flatMap(system -> system.getServices().stream()).collect(Collectors.toList()));
        if (stopped > 0) {
            noticeLabel.setText("Configuration changed: stopped " + stopped
                    + " monitoring session(s). Select a service and Start again.");
        }
        updatingPickers = true;
        try {
            systemPicker.removeAllItems();
            for (MonitoredSystem system : systems) {
                systemPicker.addItem(system);
            }
            for (MonitoredSystem system : systems) {
                if (system.getId().equals(systemId)) {
                    systemPicker.setSelectedItem(system);
                    break;
                }
            }
        } finally {
            updatingPickers = false;
        }
        populateServices(serviceId);
    }

    /** A successful save updates just that system; other saved systems remain available. */
    public void showSavedSystem(MonitoredSystem saved) {
        Objects.requireNonNull(saved, "saved");
        List<MonitoredSystem> updated = new ArrayList<>(systems);
        updated.removeIf(system -> system.getId().equals(saved.getId()));
        updated.add(saved);
        showSystems(updated);
    }

    private void populateServices(UUID preferredId) {
        updatingPickers = true;
        try {
            servicePicker.removeAllItems();
            MonitoredSystem system = (MonitoredSystem) systemPicker.getSelectedItem();
            if (system != null) {
                for (Service service : system.getServices()) {
                    servicePicker.addItem(service);
                }
                for (Service service : system.getServices()) {
                    if (service.getId().equals(preferredId)) {
                        servicePicker.setSelectedItem(service);
                        break;
                    }
                }
            }
        } finally {
            updatingPickers = false;
        }
        selectService();
    }

    private Service selectedService() {
        return (Service) servicePicker.getSelectedItem();
    }

    private void selectService() {
        Service selected = selectedService();
        viewModel.selectService(selected == null ? null : selected.getId());
    }

    private void refresh() {
        Service service = selectedService();
        boolean running = service != null && controller.isMonitoring(service.getId());
        checkButton.setEnabled(service != null && !running);
        startButton.setEnabled(service != null && !running);
        stopButton.setEnabled(running);
        stopAllButton.setEnabled(systems.stream().flatMap(system -> system.getServices().stream())
                .anyMatch(candidate -> controller.isMonitoring(candidate.getId())));
        statusLabel.setText(service == null ? "No service selected" : running ? "Monitoring" : "Stopped");
        healthLabel.setText("Health: " + viewModel.getHealthStatus());
        targetLabel.setText(service == null ? "Save a system and service in Configure to begin."
                : service.getMonitoringTarget().getMethod() + " "
                + service.getMonitoringTarget().getEndpoint().map(Object::toString)
                        .orElseGet(() -> service.getMonitoringTarget().getContainerId().orElse("")));
        tableModel.setRowCount(0);
        for (HealthCheckResult result : viewModel.getResults()) {
            tableModel.addRow(new Object[]{result.getTimestamp().toString(), result.getOutcome(),
                    result.getResponseTimeMs().isPresent() ? result.getResponseTimeMs().getAsLong() : "",
                    result.getMessage()});
        }
        String error = viewModel.getErrorMessage();
        errorLabel.setText(error.isEmpty() ? " " : "Error: " + error);
    }

    private static final class ChoiceRenderer extends DefaultListCellRenderer {
        private static final long serialVersionUID = 1L;

        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                       boolean selected, boolean focused) {
            super.getListCellRendererComponent(list, value, index, selected, focused);
            if (value instanceof MonitoredSystem) {
                setText(((MonitoredSystem) value).getName());
            } else if (value instanceof Service) {
                Service service = (Service) value;
                setText(service.getName() + " (" + service.getMonitoringTarget().getMethod() + ")");
            }
            return this;
        }
    }
}
