package view;

import interface_adapter.investigate_incident.IncidentDetailsState;
import interface_adapter.investigate_incident.IncidentListItem;
import interface_adapter.investigate_incident.IncidentViewModel;
import interface_adapter.investigate_incident.InvestigateIncidentController;
import interface_adapter.recover_service.RecoveryController;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.text.DefaultCaret;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Rectangle;
import java.beans.PropertyChangeEvent;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Use Case 3 panel: pick a monitored system, choose one of its incidents, and read the presenter's
 * explanation of it. It only forwards selections to the controller and renders the view model.
 */
public final class IncidentView extends JPanel {
    private static final String NO_SYSTEMS = "Save a system in the Configure tab to see its incidents.";
    private static final String NO_SELECTION = "Select an incident to investigate it.";
    private static final String NO_INCIDENTS = "No incidents have been recorded for this system.";
    private static final Color ERROR_COLOUR = new Color(0xB00020);

    private final IncidentViewModel viewModel;
    private final JComboBox<SystemChoice> systems = new JComboBox<>();
    private final JButton refresh = new JButton("Refresh");
    private final DefaultListModel<IncidentListItem> incidentItems = new DefaultListModel<>();
    private final JList<IncidentListItem> incidentList = new JList<>(incidentItems);
    private final JLabel status = new JLabel(" ");

    private final JTextArea title = section();
    private final JTextArea statusText = section();
    private final JTextArea causeText = section();
    private final JTextArea failuresText = section();
    private final JTextArea evidenceText = section();
    private final JTextArea impactText = section();
    private final JTextArea nextStepsText = section();
    private final RecoveryPanel recovery;
    private final JTextArea historyText = section();
    private final JPanel details = new WidthTrackingPanel();
    private final JScrollPane detailsScroll = new JScrollPane(details);

    private InvestigateIncidentController controller;
    private boolean updating;

    public IncidentView(IncidentViewModel viewModel, RecoveryController recoveryController) {
        super(new BorderLayout(8, 10));
        this.viewModel = Objects.requireNonNull(viewModel, "viewModel");
        setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        JPanel picker = new JPanel(new BorderLayout(8, 0));
        JLabel systemLabel = new JLabel("Monitored system");
        systemLabel.setLabelFor(systems);
        picker.add(systemLabel, BorderLayout.WEST);
        picker.add(systems, BorderLayout.CENTER);
        JPanel pickerActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        pickerActions.add(refresh);
        picker.add(pickerActions, BorderLayout.EAST);
        add(picker, BorderLayout.NORTH);

        incidentList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JPanel listSection = new JPanel(new BorderLayout());
        listSection.setBorder(BorderFactory.createTitledBorder("Incidents (newest first)"));
        listSection.add(new JScrollPane(incidentList), BorderLayout.CENTER);
        listSection.setMinimumSize(new Dimension(260, 120));

        title.setFont(title.getFont().deriveFont(Font.BOLD, title.getFont().getSize2D() + 2f));
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        title.setText(NO_SELECTION);
        details.setLayout(new BoxLayout(details, BoxLayout.Y_AXIS));
        details.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        details.add(title);
        details.add(Box.createVerticalStrut(8));
        details.add(titled("Status", statusText));
        details.add(titled("Suspected cause", causeText));
        details.add(titled("Recorded failures", failuresText));
        details.add(titled("Evidence", evidenceText));
        details.add(titled("Potential impact", impactText));
        details.add(titled("Next steps", nextStepsText));
        recovery = new RecoveryPanel(recoveryController, this::refreshSelectedIncident);
        details.add(titled("Recovery", recovery));
        details.add(titled("History", historyText));
        detailsScroll.getVerticalScrollBar().setUnitIncrement(16);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, listSection, detailsScroll);
        split.setResizeWeight(0.35);
        add(split, BorderLayout.CENTER);
        add(status, BorderLayout.SOUTH);

        showNoDetails(NO_SYSTEMS);
        updateActions();
        viewModel.addPropertyChangeListener(this::modelChanged);
    }

    public void connect(InvestigateIncidentController controller) {
        this.controller = Objects.requireNonNull(controller, "controller");
        systems.addActionListener(event -> {
            if (!updating) {
                showNoDetails(NO_SELECTION);
                requestIncidents();
            }
        });
        refresh.addActionListener(event -> {
            requestIncidents();
            refreshSelectedIncident();
        });
        incidentList.addListSelectionListener(event -> {
            IncidentListItem selected = incidentList.getSelectedValue();
            if (!updating && !event.getValueIsAdjusting() && selected != null) {
                status.setText("Opening incident…");
                recovery.select(selected.getIncidentId());
                controller.openIncident(selected.getIncidentId());
            }
        });
    }

    /** Replaces the systems to choose from, in display order, keeping the current choice when it still exists. */
    public void showSystems(Map<UUID, String> namesById) {
        UUID previous = selectedSystem();
        updating = true;
        systems.removeAllItems();
        namesById.forEach((id, name) -> systems.addItem(new SystemChoice(id, name)));
        int index = indexOfSystem(previous);
        if (index >= 0) {
            systems.setSelectedIndex(index);
        }
        updating = false;
        updateActions();
        if (systems.getItemCount() == 0) {
            incidentItems.clear();
            showNoDetails(NO_SYSTEMS);
        } else if (index < 0) {
            requestIncidents();
        }
    }

    /** Adds or renames one system after it was saved in the Configure tab. */
    public void showSavedSystem(UUID systemId, String name) {
        Map<UUID, String> names = new LinkedHashMap<>();
        for (int i = 0; i < systems.getItemCount(); i++) {
            names.put(systems.getItemAt(i).id, systems.getItemAt(i).name);
        }
        names.put(systemId, name);
        showSystems(names);
        if (systemId.equals(selectedSystem())) {
            refreshSelectedIncident();
        }
    }

    private void modelChanged(PropertyChangeEvent event) {
        switch (event.getPropertyName()) {
            case IncidentViewModel.INCIDENTS:
                showIncidents(viewModel.getIncidents());
                break;
            case IncidentViewModel.DETAILS:
                viewModel.getDetails().ifPresent(this::showDetails);
                break;
            case IncidentViewModel.ERROR:
                status.setForeground(ERROR_COLOUR);
                status.setText(viewModel.getError());
                break;
            case IncidentViewModel.PROCESSED:
                if (event.getNewValue().equals(selectedSystem())) {
                    requestIncidents();
                    refreshSelectedIncident();
                }
                break;
            default:
                break;
        }
    }

    private void showIncidents(List<IncidentListItem> items) {
        UUID previous = incidentList.getSelectedValue() == null ? null
                : incidentList.getSelectedValue().getIncidentId();
        updating = true;
        incidentItems.clear();
        items.forEach(incidentItems::addElement);
        int index = -1;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).getIncidentId().equals(previous)) {
                index = i;
            }
        }
        if (index >= 0) {
            incidentList.setSelectedIndex(index);
        }
        updating = false;
        if (index < 0) {
            showNoDetails(items.isEmpty() ? NO_INCIDENTS : NO_SELECTION);
        }
        setStatus(items.isEmpty() ? "No incidents" : items.size() + " incident" + (items.size() == 1 ? "" : "s"));
    }

    private void showDetails(IncidentDetailsState state) {
        IncidentListItem selected = incidentList.getSelectedValue();
        if (selected == null || !selected.getIncidentId().equals(state.getIncidentId())) {
            return; // A response for an incident that is no longer selected.
        }
        title.setText(state.getTitle());
        statusText.setText(state.getStatus() + "\n" + state.getTimeline());
        causeText.setText(state.getSuspectedCause());
        failuresText.setText(state.getRecordedFailures());
        evidenceText.setText(state.getEvidence());
        impactText.setText(state.getPotentialImpact());
        nextStepsText.setText(state.getNextSteps());
        recovery.showDetails(state);
        historyText.setText(state.getHistory());
        setDetailsVisible(true);
        SwingUtilities.invokeLater(() -> detailsScroll.getVerticalScrollBar().setValue(0));
        setStatus("Showing incident details");
        // Opening an incident can move it from Open to Investigating, so refresh the row's status.
        if (!state.getStatus().equals(selected.getStatus())) {
            requestIncidents();
        }
    }

    private void showNoDetails(String message) {
        recovery.select(null);
        title.setText(message);
        setDetailsVisible(false);
    }

    private void setDetailsVisible(boolean visible) {
        for (Component component : details.getComponents()) {
            if (component != title) {
                component.setVisible(visible);
            }
        }
        details.revalidate();
        details.repaint();
    }

    private void requestIncidents() {
        UUID systemId = selectedSystem();
        if (controller == null || systemId == null) {
            return;
        }
        setStatus("Loading incidents…");
        controller.listIncidents(systemId);
    }

    private void setStatus(String message) {
        status.setForeground(UIManager.getColor("Label.foreground"));
        status.setText(message);
    }

    private void updateActions() {
        boolean hasSystems = systems.getItemCount() > 0;
        systems.setEnabled(hasSystems);
        refresh.setEnabled(hasSystems);
    }

    private void refreshSelectedIncident() {
        IncidentListItem selected = incidentList.getSelectedValue();
        if (controller != null && selected != null) {
            controller.openIncident(selected.getIncidentId());
        }
    }

    private UUID selectedSystem() {
        SystemChoice choice = (SystemChoice) systems.getSelectedItem();
        return choice == null ? null : choice.id;
    }

    private int indexOfSystem(UUID systemId) {
        for (int i = 0; i < systems.getItemCount(); i++) {
            if (systems.getItemAt(i).id.equals(systemId)) {
                return i;
            }
        }
        return -1;
    }

    private static JTextArea section() {
        JTextArea text = new JTextArea();
        text.setEditable(false);
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        text.setOpaque(false);
        text.setFont(UIManager.getFont("Label.font"));
        // Keep the caret from scrolling the details pane to the last section that was filled.
        ((DefaultCaret) text.getCaret()).setUpdatePolicy(DefaultCaret.NEVER_UPDATE);
        return text;
    }

    private static JPanel titled(String heading, JComponent body) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createTitledBorder(heading),
                BorderFactory.createEmptyBorder(2, 4, 4, 4)));
        panel.add(body, BorderLayout.CENTER);
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        return panel;
    }

    private static final class SystemChoice {
        private final UUID id;
        private final String name;

        private SystemChoice(UUID id, String name) {
            this.id = id;
            this.name = name;
        }

        @Override
        public String toString() {
            return name + " (" + id.toString().substring(0, 8) + ")";
        }
    }

    /** Lets wrapped text follow the scroll pane's width instead of growing sideways. */
    private static final class WidthTrackingPanel extends JPanel implements Scrollable {
        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) {
            return 16;
        }
        @Override public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
            return orientation == SwingConstants.VERTICAL ? visible.height : visible.width;
        }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return false; }
    }
}
