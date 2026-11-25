package use_case.shared;

import entity.MonitoredSystem;
import java.util.Objects;

/**
 * A monitored system's configuration as read by investigation and recovery. UC1 stores a system's services
 * and dependencies together, so the dependencies are always known; an empty list means none are configured.
 */
public final class ConfigurationSnapshot {
    private final MonitoredSystem system;

    private ConfigurationSnapshot(MonitoredSystem system) {
        this.system = Objects.requireNonNull(system, "system");
    }

    public static ConfigurationSnapshot available(MonitoredSystem system) {
        return new ConfigurationSnapshot(system);
    }

    public MonitoredSystem getSystem() { return system; }
}
