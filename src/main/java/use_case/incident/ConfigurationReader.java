package use_case.incident;

import java.util.Optional;
import java.util.UUID;

public interface ConfigurationReader {
    /** Empty means no such system. Storage/access failures throw IncidentDataException. */
    Optional<ConfigurationSnapshot> findBySystemId(UUID systemId);
}
