package use_case.configure_monitored_system;

import entity.MonitoredSystem;

import java.util.List;

/** The readable systems and the names of configuration files that could not be loaded. */
public final class ConfigurationLoadResult {
    private final List<MonitoredSystem> systems;
    private final List<String> skippedFiles;

    public ConfigurationLoadResult(List<MonitoredSystem> systems, List<String> skippedFiles) {
        this.systems = List.copyOf(systems);
        this.skippedFiles = List.copyOf(skippedFiles);
    }

    public List<MonitoredSystem> getSystems() {
        return systems;
    }

    public List<String> getSkippedFiles() {
        return skippedFiles;
    }
}
