package use_case.shared;

import java.util.Optional;
import java.util.UUID;

/**
 * Read-only access to a monitored system's configuration, shared by investigation (UC3) and recovery (UC4).
 * UC1 remains the only writer; an adapter translates its repository into this port.
 */
public interface ConfigurationReader {
    /** Empty means no such system. Storage/access failures throw IncidentDataException. */
    Optional<ConfigurationSnapshot> findBySystemId(UUID systemId);
}
