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
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
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
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
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
    private final DefaultListModel<ServiceInput> serviceRows = new DefaultListModel<>();
    private final DefaultListModel<String> dependencyLabels = new DefaultListModel<>();
    private final JList<ServiceInput> serviceList = new JList<>(serviceRows);
    private final JList<String> dependencyList = new JList<>(dependencyLabels);
    private final JLabel status = new JLabel(" ");

    private final JButton newSystem = ConfigurationStyle.secondaryButton("New system");
    private final JButton reload = ConfigurationStyle.secondaryButton("Reload");
    private final JButton addService = ConfigurationStyle.secondaryButton("Add service");
    private final JButton editService = ConfigurationStyle.secondaryButton("Edit service");
    private final JButton removeService = ConfigurationStyle.secondaryButton("Remove");
    private final JButton addDependency = ConfigurationStyle.secondaryButton("Add dependency");
    private final JButton removeDependency = ConfigurationStyle.secondaryButton("Remove");
    private final JButton save = ConfigurationStyle.primaryButton("Save configuration");

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

        content.setBackground(ConfigurationStyle.CANVAS);
        content.add(buildHeader(), BorderLayout.NORTH);

        serviceList.setCellRenderer(new ServiceRowRenderer());
        dependencyList.setCellRenderer(new DependencyRowRenderer());
        removeService.getAccessibleContext().setAccessibleName("Remove selected service");
        removeDependency.getAccessibleContext().setAccessibleName("Remove selected dependency");
        JSplitPane map = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                mapSection("Services", serviceList, addService, editService, removeService),
                mapSection("Dependencies", dependencyList, addDependency, removeDependency));
        map.setBorder(BorderFactory.createEmptyBorder());
        map.setBackground(ConfigurationStyle.CANVAS);
        map.setDividerSize(10);
        map.setResizeWeight(0.53);
        content.add(map, BorderLayout.CENTER);

        JPanel footer = new JPanel(new BorderLayout());
        footer.setOpaque(false);
        status.setForeground(ConfigurationStyle.MUTED);
        status.setBorder(ConfigurationStyle.inset(6, 4));
        footer.add(status, BorderLayout.CENTER);
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
        ConfigurationStyle.ensureWindowFitsConfiguration(savedSystems);
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

    private JPanel buildHeader() {
        JPanel header = ConfigurationStyle.surface(new BorderLayout(0, 12));
        header.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(ConfigurationStyle.BORDER, 1, true),
                ConfigurationStyle.inset(12, 14)));

        JLabel systemLabel = new JLabel("Monitored system");
        systemLabel.setLabelFor(savedSystems);
        ConfigurationStyle.label(systemLabel);
        ConfigurationStyle.field(savedSystems);
        JPanel picker = ConfigurationStyle.surface(new BorderLayout(0, 4));
        picker.add(systemLabel, BorderLayout.NORTH);
        picker.add(savedSystems, BorderLayout.CENTER);
        Dimension pickerMinimum = picker.getMinimumSize();
        picker.setMinimumSize(new Dimension(Math.max(pickerMinimum.width, 220), pickerMinimum.height));

        JPanel systemActions = ConfigurationStyle.surface(new FlowLayout(FlowLayout.TRAILING, 8, 0));
        systemActions.add(newSystem);
        systemActions.add(reload);
        systemActions.add(save);

        JPanel toolbar = ConfigurationStyle.surface(new BorderLayout(12, 0));
        toolbar.add(picker, BorderLayout.CENTER);
        toolbar.add(systemActions, BorderLayout.EAST);
        header.add(toolbar, BorderLayout.NORTH);

        description.setLineWrap(true);
        description.setWrapStyleWord(true);
        ConfigurationStyle.field(name);
        ConfigurationStyle.field(description);
        JScrollPane descriptionScroll = new JScrollPane(description);
        descriptionScroll.setBorder(BorderFactory.createLineBorder(ConfigurationStyle.BORDER));

        JPanel fields = ConfigurationStyle.surface(new GridBagLayout());
        addSystemField(fields, 0, "System name", name, name);
        addSystemField(fields, 1, "Description", description, descriptionScroll);
        header.add(fields, BorderLayout.CENTER);
        return header;
    }

    private static void addSystemField(JPanel panel, int row, String text, Component field, Component input) {
        JLabel label = new JLabel(text);
        label.setLabelFor(field);
        ConfigurationStyle.label(label);

        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridy = row;
        constraints.anchor = GridBagConstraints.NORTHWEST;
        constraints.insets.set(4, 0, 4, 12);
        panel.add(label, constraints);

        constraints.gridx = 1;
        constraints.weightx = 1;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.insets.right = 0;
        panel.add(input, constraints);
    }

    private static JPanel mapSection(String title, JList<?> list, JButton... buttons) {
        JPanel section = ConfigurationStyle.surface(new BorderLayout());
        section.setBorder(BorderFactory.createLineBorder(ConfigurationStyle.BORDER, 1, true));
        JLabel heading = ConfigurationStyle.heading(title);
        heading.setLabelFor(list);
        heading.setBorder(BorderFactory.createCompoundBorder(
                ConfigurationStyle.divider(0, 1), ConfigurationStyle.inset(11, 13)));
        section.add(heading, BorderLayout.NORTH);

        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setBackground(ConfigurationStyle.SURFACE);
        list.setForeground(ConfigurationStyle.TEXT);
        list.setSelectionBackground(ConfigurationStyle.SELECTED);
        list.setSelectionForeground(ConfigurationStyle.TEXT);
        section.add(ConfigurationStyle.scroll(list), BorderLayout.CENTER);

        JPanel actions = ConfigurationStyle.surface(null);
        actions.setLayout(new BoxLayout(actions, BoxLayout.LINE_AXIS));
        actions.setBorder(BorderFactory.createCompoundBorder(
                ConfigurationStyle.divider(1, 0), ConfigurationStyle.inset(7, 8)));
        for (int i = 0; i < buttons.length; i++) {
            if (i > 0) {
                actions.add(Box.createHorizontalStrut(8));
            }
            actions.add(buttons[i]);
        }
        actions.add(Box.createHorizontalGlue());
        Dimension actionSize = actions.getPreferredSize();
        section.setMinimumSize(new Dimension(Math.max(actionSize.width + 2, 250),
                heading.getPreferredSize().height + actionSize.height + 58));
        section.add(actions, BorderLayout.SOUTH);
        return section;
    }

    private static final class ServiceRowRenderer extends JPanel implements ListCellRenderer<ServiceInput> {
        private final JLabel name = new JLabel();
        private final JLabel target = new JLabel();
        private final JLabel method = new JLabel();

        ServiceRowRenderer() {
            super(new BorderLayout(10, 0));
            JPanel details = new JPanel(new GridLayout(0, 1, 0, 2));
            details.setOpaque(false);
            name.setFont(name.getFont().deriveFont(Font.BOLD));
            name.setForeground(ConfigurationStyle.TEXT);
            target.setForeground(ConfigurationStyle.MUTED);
            details.add(name);
            details.add(target);
            add(details, BorderLayout.CENTER);

            JPanel type = new JPanel(new GridBagLayout());
            type.setOpaque(false);
            method.setOpaque(true);
            method.setFont(method.getFont().deriveFont(Font.BOLD, 11f));
            method.setBorder(ConfigurationStyle.inset(3, 7));
            type.add(method);
            add(type, BorderLayout.EAST);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends ServiceInput> list, ServiceInput service,
                                                      int index, boolean selected, boolean hasFocus) {
            boolean http = service.getMethod() == MonitoringTarget.Method.HTTP;
            name.setText(displayName(service));
            target.setText(service.getTarget());
            method.setText(http ? "HTTP" : "Docker");
            method.setBackground(http ? new Color(0xE6EFFD) : new Color(0xE6F3EE));
            method.setForeground(http ? new Color(0x264E91) : new Color(0x215E4C));
            setBackground(selected ? list.getSelectionBackground() : list.getBackground());
            setBorder(BorderFactory.createCompoundBorder(
                    hasFocus ? BorderFactory.createLineBorder(ConfigurationStyle.TEAL)
                            : ConfigurationStyle.divider(0, 1),
                    BorderFactory.createEmptyBorder(hasFocus ? 8 : 9, hasFocus ? 12 : 13, 9, hasFocus ? 12 : 13)));
            getAccessibleContext().setAccessibleName(name.getText() + ", " + method.getText() + ", " + target.getText());
            return this;
        }
    }

    private static final class DependencyRowRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value,
                                                      int index, boolean selected, boolean hasFocus) {
            super.getListCellRendererComponent(list, value, index, selected, hasFocus);
            setBorder(BorderFactory.createCompoundBorder(
                    hasFocus ? BorderFactory.createLineBorder(ConfigurationStyle.TEAL)
                            : ConfigurationStyle.divider(0, 1), ConfigurationStyle.inset(12, 13)));
            return this;
        }
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
        serviceRows.clear();
        for (ServiceInput service : services) {
            String serviceName = displayName(service);
            names.put(service.getId(), serviceName);
            serviceRows.addElement(service);
        }
        dependencyLabels.clear();
        for (Dependency edge : dependencies) {
            dependencyLabels.addElement(names.get(edge.getDependentServiceId()) + " depends on "
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
