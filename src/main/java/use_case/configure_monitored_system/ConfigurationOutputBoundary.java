package use_case.configure_monitored_system;

import entity.MonitoredSystem;

public interface ConfigurationOutputBoundary {
    void presentSystems(ConfigurationLoadResult result);

    void presentSaved(MonitoredSystem system);

    void presentError(String message);
}
