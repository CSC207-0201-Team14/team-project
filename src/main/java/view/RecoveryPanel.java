package view;

import interface_adapter.investigate_incident.IncidentDetailsState;
import interface_adapter.investigate_incident.IncidentDetailsState.RecoveryChoice;
import interface_adapter.recover_service.RecoveryController;
import interface_adapter.recover_service.RecoveryViewState;
import use_case.recover_service.RecoveryPreview;

import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import java.awt.FlowLayout;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/** Swing controls for the selected incident's recovery actions. */
final class RecoveryPanel extends JPanel {
    private final RecoveryController controller;
    private final Runnable refreshDetails;
    private final JComboBox<RecoveryChoice> recommendations = new JComboBox<>();
    private final JButton previewButton = new JButton("Preview");
    private final JButton confirmButton = new JButton("Confirm operation");
    private final JButton manualButton = new JButton("Begin manual verification");
    private final JButton verifyButton = new JButton("Verify recovery");
    private final JButton historyButton = new JButton("Recovery history");
    private final JTextArea result = messageArea();
    private final JTextArea note = messageArea();

    private UUID incidentId;
    private long selection;
    private RecoveryPreview preview;
    private boolean unresolved;
    private boolean busy;
    private boolean updatingChoices;

    RecoveryPanel(RecoveryController controller, Runnable refreshDetails) {
        this.controller = controller;
        this.refreshDetails = refreshDetails;
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        recommendations.getAccessibleContext().setAccessibleName("Recommended recovery action");
        add(recommendations);
        add(note);
        JPanel execution = new JPanel(new FlowLayout(FlowLayout.LEADING));
        execution.add(previewButton);
        execution.add(confirmButton);
        add(execution);
        JPanel verification = new JPanel(new FlowLayout(FlowLayout.LEADING));
        verification.add(manualButton);
        verification.add(verifyButton);
        verification.add(historyButton);
        add(verification);
        add(result);
        updateButtons();

        recommendations.addActionListener(event -> {
            if (!updatingChoices) {
                preview = null;
                result.setText("");
                updateButtons();
            }
        });
        previewButton.addActionListener(event -> {
            RecoveryChoice choice = (RecoveryChoice) recommendations.getSelectedItem();
            start(show -> controller.preview(incidentId, choice.id(), show), false);
        });
        confirmButton.addActionListener(event -> confirm());
        manualButton.addActionListener(event -> start(
                show -> controller.beginManualVerification(incidentId, show), true));
        verifyButton.addActionListener(event -> start(show -> controller.verify(incidentId, show), true));
        historyButton.addActionListener(event -> start(show -> controller.history(incidentId, show), false));
    }

    void select(UUID id) {
        if (Objects.equals(incidentId, id)) {
            return;
        }
        selection++;
        incidentId = id;
        unresolved = false;
        preview = null;
        result.setText("");
        note.setText("");
        recommendations.removeAllItems();
        updateButtons();
    }

    void showDetails(IncidentDetailsState details) {
        select(details.getIncidentId());
        unresolved = !details.getStatus().equals("Resolved");
        note.setText(details.getRecommendationNote());
        RecoveryChoice selected = (RecoveryChoice) recommendations.getSelectedItem();
        updatingChoices = true;
        recommendations.removeAllItems();
        details.getRecoveryChoices().forEach(recommendations::addItem);
        if (selected != null) {
            details.getRecoveryChoices().stream().filter(choice -> choice.id().equals(selected.id())).findFirst()
                    .ifPresent(recommendations::setSelectedItem);
        }
        updatingChoices = false;
        RecoveryChoice current = (RecoveryChoice) recommendations.getSelectedItem();
        if (preview != null && (current == null || !current.id().equals(preview.getAction().getId()))) {
            preview = null;
        }
        updateButtons();
    }

    private void confirm() {
        RecoveryPreview approved = preview;
        if (approved == null) {
            return;
        }
        long currentSelection = selection;
        String target = approved.getContainerId() + " (" + approved.getState().getContainerIdentity().orElseThrow() + ")";
        int choice = JOptionPane.showConfirmDialog(this,
                approved.getDecision().getOperation().orElseThrow() + " " + target + "?",
                "Confirm Docker recovery", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (choice == JOptionPane.OK_OPTION && currentSelection == selection && preview == approved && unresolved) {
            start(show -> controller.confirm(approved, show), true);
        }
    }

    private void start(Consumer<Consumer<RecoveryViewState>> operation, boolean refresh) {
        long requestedSelection = selection;
        preview = null;
        busy = true;
        result.setText("Working...");
        updateButtons();
        operation.accept(state -> {
            busy = false;
            if (selection == requestedSelection) {
                preview = state.preview();
                result.setText(state.text());
                if (refresh) {
                    refreshDetails.run();
                }
            }
            updateButtons();
        });
    }

    private void updateButtons() {
        recommendations.setEnabled(!busy && unresolved && recommendations.getItemCount() > 0);
        previewButton.setEnabled(recommendations.isEnabled());
        confirmButton.setEnabled(!busy && unresolved && preview != null);
        manualButton.setEnabled(!busy && unresolved);
        verifyButton.setEnabled(!busy && unresolved);
        historyButton.setEnabled(!busy && incidentId != null);
    }

    private static JTextArea messageArea() {
        JTextArea area = new JTextArea();
        area.setEditable(false);
        area.setOpaque(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        return area;
    }
}
