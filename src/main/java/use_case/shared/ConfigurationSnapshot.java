package use_case.shared;

import entity.MonitoredSystem;
import java.util.Objects;
import java.util.Optional;

/** A valid service roster with an explicit indication of dependency availability. */
public final class ConfigurationSnapshot {
    private final MonitoredSystem system;
    private final Optional<String> dependencyUnavailableReason;

    private ConfigurationSnapshot(MonitoredSystem system, Optional<String> reason) {
        this.system = Objects.requireNonNull(system, "system");
        this.dependencyUnavailableReason = reason;
    }

    /** An empty graph here means known to have no configured edges. */
    public static ConfigurationSnapshot available(MonitoredSystem system) {
        return new ConfigurationSnapshot(system, Optional.empty());
    }

    /** UC1 supplies the roster with no edges when dependency data could not be obtained. */
    public static ConfigurationSnapshot dependenciesUnavailable(MonitoredSystem system, String reason) {
        if (reason.isBlank() || !system.getDependencies().isEmpty()) {
            throw new IllegalArgumentException("Unavailable dependencies require a reason and an empty graph");
        }
        return new ConfigurationSnapshot(system, Optional.of(reason));
    }

    public MonitoredSystem getSystem() { return system; }
    public Optional<String> getDependencyUnavailableReason() { return dependencyUnavailableReason; }
}
