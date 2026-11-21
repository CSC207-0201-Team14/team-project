package use_case.configure_monitored_system;

import entity.MonitoredSystem;

import java.util.List;

public interface ConfigurationOutputBoundary {
    void presentSystems(List<MonitoredSystem> systems);

    void presentSaved(MonitoredSystem system);

    void presentError(String message);
}
