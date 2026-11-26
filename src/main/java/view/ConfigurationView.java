package view;

import entity.Dependency;
import entity.MonitoredSystem;
import entity.MonitoringPolicy;
import entity.MonitoringTarget;
import entity.Service;
import interface_adapter.configuration.ConfigurationController;
import interface_adapter.configuration.ConfigurationPresenter;
import use_case.configure_monitored_system.ConfigurationInput;
import use_case.configure_monitored_system.ServiceInput;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Use Case 1 panel, shown as the Configure tab of the main window. */
public final class ConfigurationView extends JPanel implements ConfigurationPresenter.View {
    private final JTextField name = new JTextField();
    private final JTextArea description = new JTextArea(2, 28);
    private final JComboBox<String> savedSystems = new JComboBox<>();
    private final DefaultListModel<String> serviceLabels = new DefaultListModel<>();
    private final DefaultListModel<String> dependencyLabels = new DefaultListModel<>();
    private final JList<String> serviceList = new JList<>(serviceLabels);
    private final JList<String> dependencyList = new JList<>(dependencyLabels);
    private final JLabel status = new JLabel(" ");

    private final JButton newSystem = new JButton("New system");
    private final JButton reload = new JButton("Reload");
    private final JButton addService = new JButton("Add service");
    private final JButton editService = new JButton("Edit");
    private final JButton removeService = new JButton("Remove");
    private final JButton addDependency = new JButton("Add dependency");
    private final JButton removeDependency = new JButton("Remove");
    private final JButton save = new JButton("Save configuration");

    private final List<MonitoredSystem> systems = new ArrayList<>();
    private final List<ServiceInput> services = new ArrayList<>();
    private final List<Dependency> dependencies = new ArrayList<>();
    private MonitoredSystem original;
    private UUID systemId;
    private boolean updatingSelection;
    private boolean mapChanged;
    private boolean busy;

    public ConfigurationView() {
        super(new BorderLayout());
        JPanel content = new JPanel(new BorderLayout(8, 10));
        content.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        add(content, BorderLayout.CENTER);

        JPanel systemPicker = new JPanel(new BorderLayout(8, 0));
        JLabel systemLabel = new JLabel("Saved system");
        systemLabel.setLabelFor(savedSystems);
        systemPicker.add(systemLabel, BorderLayout.WEST);
        systemPicker.add(savedSystems, BorderLayout.CENTER);
        JPanel systemActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        systemActions.add(newSystem);
        systemActions.add(reload);
        systemPicker.add(systemActions, BorderLayout.EAST);

        JPanel fields = new JPanel(new BorderLayout(8, 6));
        JPanel labels = new JPanel(new GridLayout(0, 1, 0, 6));
        JLabel nameLabel = new JLabel("System name");
        nameLabel.setLabelFor(name);
        labels.add(nameLabel);
        JLabel descriptionLabel = new JLabel("Description");
        descriptionLabel.setLabelFor(description);
        labels.add(descriptionLabel);
        fields.add(labels, BorderLayout.WEST);
        JPanel inputs = new JPanel(new BorderLayout(0, 6));
        inputs.add(name, BorderLayout.NORTH);
        description.setLineWrap(true);
        description.setWrapStyleWord(true);
        inputs.add(new JScrollPane(description), BorderLayout.CENTER);
        fields.add(inputs, BorderLayout.CENTER);
        JPanel header = new JPanel(new BorderLayout(0, 8));
        header.add(systemPicker, BorderLayout.NORTH);
        header.add(fields, BorderLayout.SOUTH);
        content.add(header, BorderLayout.NORTH);

        JSplitPane map = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                mapSection("System Map — Services", serviceList, addService, editService, removeService),
                mapSection("Dependencies (service → required service)", dependencyList, addDependency,
                        removeDependency));
        map.setResizeWeight(0.5);
        content.add(map, BorderLayout.CENTER);

        JPanel footer = new JPanel(new BorderLayout());
        footer.add(status, BorderLayout.CENTER);
        footer.add(save, BorderLayout.EAST);
        content.add(footer, BorderLayout.SOUTH);

        serviceList.addListSelectionListener(event -> updateActions());
        dependencyList.addListSelectionListener(event -> updateActions());
        setMinimumSize(new Dimension(760, 360));
        openDraft(null);
    }

    /** Called by the main window before it closes: false while saving or if the user keeps unsaved changes. */
    public boolean confirmClose() {
        return !busy && confirmDiscard();
    }

    public void connect(ConfigurationController controller) {
        savedSystems.addActionListener(event -> selectSavedSystem());
        newSystem.addActionListener(event -> {
            if (confirmDiscard()) {
                openDraft(null);
                status.setText("New system");
            }
        });
        reload.addActionListener(event -> {
            if (confirmDiscard()) {
                requestLoad(controller);
            }
        });
        addService.addActionListener(event -> editService(-1));
        editService.addActionListener(event -> editService(serviceList.getSelectedIndex()));
        removeService.addActionListener(event -> removeService());
        addDependency.addActionListener(event -> addDependency());
        removeDependency.addActionListener(event -> {
            dependencies.remove(dependencyList.getSelectedIndex());
            updateMap();
        });
        save.addActionListener(event -> {
            ConfigurationInput draft = new ConfigurationInput(systemId, name.getText(), description.getText(),
                    services, dependencies);
            setBusy(true, "Saving configuration…");
            controller.save(draft);
        });
        requestLoad(controller);
    }

    @Override
    public void showSystems(List<MonitoredSystem> loaded, List<String> skippedFiles) {
        MonitoredSystem selected = loaded.stream().filter(system -> system.getId().equals(systemId))
                .findFirst().orElseGet(() -> loaded.isEmpty() ? null : loaded.get(0));
        systems.clear();
        systems.addAll(loaded);
        refreshPicker();
        openDraft(selected);
        if (skippedFiles.isEmpty()) {
            setBusy(false, loaded.isEmpty() ? "New system" : "Loaded saved systems");
            return;
        }

        setBusy(false, "Some configurations could not be loaded");
        JTextArea files = new JTextArea(String.join("\n", skippedFiles), Math.min(skippedFiles.size(), 8), 42);
        files.setEditable(false);
        files.setCaretPosition(0);
        JOptionPane.showMessageDialog(this,
                new Object[]{"These configuration files were skipped:", new JScrollPane(files)},
                "Configuration warning", JOptionPane.WARNING_MESSAGE);
    }

    @Override
    public void showSaved(MonitoredSystem system) {
        systems.removeIf(saved -> saved.getId().equals(system.getId()));
        systems.add(system);
        systems.sort(Comparator.comparing(MonitoredSystem::getName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(MonitoredSystem::getId));
        refreshPicker();
        openDraft(system);
        setBusy(false, "Configuration saved");
    }

    @Override
    public void showError(String message) {
        setBusy(false, "Configuration error");
        JOptionPane.showMessageDialog(this, message, "Configuration error", JOptionPane.ERROR_MESSAGE);
    }

    private void requestLoad(ConfigurationController controller) {
        setBusy(true, "Loading saved systems…");
        controller.loadSystems();
    }

    private JPanel mapSection(String title, JList<String> list, JButton... buttons) {
        JPanel section = new JPanel(new BorderLayout());
        section.setBorder(BorderFactory.createTitledBorder(title));
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        section.add(new JScrollPane(list), BorderLayout.CENTER);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEADING));
        for (JButton button : buttons) {
            actions.add(button);
        }
        section.add(actions, BorderLayout.SOUTH);
        return section;
    }

    private void refreshPicker() {
        updatingSelection = true;
        savedSystems.removeAllItems();
        savedSystems.addItem("New system");
        for (MonitoredSystem system : systems) {
            savedSystems.addItem(system.getName() + " (" + system.getId().toString().substring(0, 8) + ")");
        }
        updatingSelection = false;
    }

    private void selectSavedSystem() {
        if (updatingSelection) {
            return;
        }
        int selection = savedSystems.getSelectedIndex() - 1;
        MonitoredSystem selected = selection < 0 ? null : systems.get(selection);
        if (selected == original) {
            return;
        }
        if (confirmDiscard()) {
            openDraft(selected);
            status.setText(selected == null ? "New system" : "Editing saved configuration");
        } else {
            syncSelection();
        }
    }

    private void syncSelection() {
        updatingSelection = true;
        savedSystems.setSelectedIndex(systems.indexOf(original) + 1);
        updatingSelection = false;
    }

    private void openDraft(MonitoredSystem system) {
        original = system;
        systemId = system == null ? UUID.randomUUID() : system.getId();
        name.setText(system == null ? "" : system.getName());
        description.setText(system == null ? "" : system.getDescription());
        services.clear();
        dependencies.clear();
        if (system != null) {
            system.getServices().stream().map(ConfigurationView::toInput).forEach(services::add);
            dependencies.addAll(system.getDependencies());
        }
        if (savedSystems.getItemCount() == 0) {
            refreshPicker();
        }
        syncSelection();
        renderMap();
        mapChanged = false;
    }

    private boolean confirmDiscard() {
        String savedName = original == null ? "" : original.getName();
        String savedDescription = original == null ? "" : original.getDescription();
        boolean dirty = mapChanged || !name.getText().equals(savedName) || !description.getText().equals(savedDescription);
        return !dirty || JOptionPane.showConfirmDialog(this, "Discard unsaved changes?", "Unsaved changes",
                JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION;
    }

    private void editService(int index) {
        ServiceEditor.edit(this, index < 0 ? null : services.get(index)).ifPresent(service -> {
            if (index < 0) {
                services.add(service);
            } else {
                services.set(index, service);
            }
            updateMap();
            serviceList.setSelectedIndex(index < 0 ? services.size() - 1 : index);
        });
    }

    private void removeService() {
        UUID removed = services.remove(serviceList.getSelectedIndex()).getId();
        dependencies.removeIf(edge -> edge.getDependentServiceId().equals(removed)
                || edge.getRequiredServiceId().equals(removed));
        updateMap();
    }

    private void addDependency() {
        String[] choices = new String[services.size()];
        for (int index = 0; index < services.size(); index++) {
            ServiceInput service = services.get(index);
            choices[index] = displayName(service) + " (" + service.getTarget() + ")";
        }
        JComboBox<String> dependent = new JComboBox<>(choices);
        JComboBox<String> required = new JComboBox<>(choices);
        required.setSelectedIndex(1);
        JPanel form = new JPanel(new GridLayout(0, 2, 8, 6));
        ServiceEditor.addField(form, "Service", dependent);
        ServiceEditor.addField(form, "Depends on", required);
        if (JOptionPane.showConfirmDialog(this, form, "Add dependency", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }
        UUID from = services.get(dependent.getSelectedIndex()).getId();
        UUID to = services.get(required.getSelectedIndex()).getId();
        try {
            dependencies.add(new Dependency(UUID.randomUUID(), from, to));
            updateMap();
        } catch (IllegalArgumentException exception) {
            showError(exception.getMessage());
        }
    }

    private void updateMap() {
        mapChanged = true;
        renderMap();
        status.setText("Unsaved changes");
    }

    private void renderMap() {
        Map<UUID, String> names = new HashMap<>();
        serviceLabels.clear();
        for (ServiceInput service : services) {
            String serviceName = displayName(service);
            names.put(service.getId(), serviceName);
            serviceLabels.addElement(serviceName + " — " + service.getMethod() + " " + service.getTarget());
        }
        dependencyLabels.clear();
        for (Dependency edge : dependencies) {
            dependencyLabels.addElement(names.get(edge.getDependentServiceId()) + " → "
                    + names.get(edge.getRequiredServiceId()));
        }
        updateActions();
    }

    private void setBusy(boolean busy, String message) {
        this.busy = busy;
        status.setText(message);
        updateActions();
    }

    private void updateActions() {
        for (Component component : List.of(savedSystems, name, description, newSystem, reload, serviceList,
                dependencyList, addService, save)) {
            component.setEnabled(!busy);
        }
        boolean serviceSelected = !busy && serviceList.getSelectedIndex() >= 0;
        editService.setEnabled(serviceSelected);
        removeService.setEnabled(serviceSelected);
        addDependency.setEnabled(!busy && services.size() >= 2);
        removeDependency.setEnabled(!busy && dependencyList.getSelectedIndex() >= 0);
    }

    private static String displayName(ServiceInput service) {
        return service.getName().isBlank() ? "(Unnamed service)" : service.getName();
    }

    private static ServiceInput toInput(Service service) {
        MonitoringTarget target = service.getMonitoringTarget();
        MonitoringPolicy policy = service.getMonitoringPolicy();
        return new ServiceInput(service.getId(), service.getName(), target.getMethod(),
                target.getEndpoint().map(URI::toString).orElseGet(() -> target.getContainerId().orElseThrow()),
                milliseconds(policy.getCheckInterval()), milliseconds(policy.getTimeout()),
                Integer.toString(policy.getFailureThreshold()), Integer.toString(policy.getRecoveryThreshold()));
    }

    private static String milliseconds(Duration duration) {
        return BigDecimal.valueOf(duration.getSeconds()).movePointRight(3)
                .add(BigDecimal.valueOf(duration.getNano(), 6)).stripTrailingZeros().toPlainString();
    }
}
