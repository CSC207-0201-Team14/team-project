package interface_adapter.configuration;

import entity.MonitoredSystem;
import use_case.configure_monitored_system.ConfigurationOutputBoundary;

import javax.swing.SwingUtilities;
import java.util.List;

public final class ConfigurationPresenter implements ConfigurationOutputBoundary {
    public interface View {
        void showSystems(List<MonitoredSystem> systems);

        void showSaved(MonitoredSystem system);

        void showError(String message);
    }

    private final View view;

    public ConfigurationPresenter(View view) {
        this.view = view;
    }

    @Override
    public void presentSystems(List<MonitoredSystem> systems) {
        SwingUtilities.invokeLater(() -> view.showSystems(systems));
    }

    @Override
    public void presentSaved(MonitoredSystem system) {
        SwingUtilities.invokeLater(() -> view.showSaved(system));
    }

    @Override
    public void presentError(String message) {
        SwingUtilities.invokeLater(() -> view.showError(message));
    }
}
