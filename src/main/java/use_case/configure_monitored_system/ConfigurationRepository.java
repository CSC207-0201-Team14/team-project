package use_case.configure_monitored_system;

import entity.MonitoredSystem;

import java.util.Optional;
import java.util.UUID;

/** Stores complete immutable system configurations. Store-level failures throw ConfigurationStoreException. */
public interface ConfigurationRepository {
    /** Lists readable systems and reports individual configuration files that were skipped. */
    ConfigurationLoadResult findAll();

    /** Empty means the requested system does not exist. */
    Optional<MonitoredSystem> findById(UUID systemId);

    /** Replaces the system by ID atomically. A failed save leaves the previous version intact. */
    void save(MonitoredSystem system);
}
