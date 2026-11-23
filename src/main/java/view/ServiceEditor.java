package view;

import entity.MonitoringTarget;
import use_case.configure_monitored_system.ServiceInput;

import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import java.awt.Component;
import java.awt.GridLayout;
import java.util.Optional;
import java.util.UUID;

final class ServiceEditor {
    private ServiceEditor() {
    }

    static Optional<ServiceInput> edit(Component parent, ServiceInput existing) {
        boolean isNew = existing == null;
        JTextField name = new JTextField(isNew ? "" : existing.getName(), 24);
        JComboBox<MonitoringTarget.Method> method = new JComboBox<>(MonitoringTarget.Method.values());
        method.setSelectedItem(isNew ? MonitoringTarget.Method.HTTP : existing.getMethod());
        JTextField target = new JTextField(isNew ? "" : existing.getTarget());
        JTextField interval = new JTextField(isNew ? "10000" : existing.getIntervalMillis());
        JTextField timeout = new JTextField(isNew ? "2000" : existing.getTimeoutMillis());
        JTextField failure = new JTextField(isNew ? "3" : existing.getFailureThreshold());
        JTextField recovery = new JTextField(isNew ? "2" : existing.getRecoveryThreshold());

        JLabel targetLabel = new JLabel();
        targetLabel.setLabelFor(target);
        Runnable updateLabel = () -> targetLabel.setText(method.getSelectedItem() == MonitoringTarget.Method.HTTP
                ? "HTTP(S) URL" : "Container ID or name");
        method.addActionListener(event -> updateLabel.run());
        updateLabel.run();

        JPanel form = new JPanel(new GridLayout(0, 2, 8, 6));
        addField(form, "Service name", name);
        addField(form, "Monitoring method", method);
        form.add(targetLabel);
        form.add(target);
        addField(form, "Check interval (ms)", interval);
        addField(form, "Timeout (ms)", timeout);
        addField(form, "Failure threshold", failure);
        addField(form, "Recovery threshold", recovery);

        int result = JOptionPane.showConfirmDialog(parent, form, isNew ? "Add service" : "Edit service",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (result != JOptionPane.OK_OPTION) {
            return Optional.empty();
        }
        UUID id = isNew ? UUID.randomUUID() : existing.getId();
        return Optional.of(new ServiceInput(id, name.getText(), (MonitoringTarget.Method) method.getSelectedItem(),
                target.getText(), interval.getText(), timeout.getText(), failure.getText(), recovery.getText()));
    }

    static void addField(JPanel form, String labelText, Component field) {
        JLabel label = new JLabel(labelText);
        label.setLabelFor(field);
        form.add(label);
        form.add(field);
    }
}
