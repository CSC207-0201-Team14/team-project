package use_case.incident;

import entity.HealthCheckResult;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public interface ObservationReader {
    /**
     * Returns retained observations keyed by their IDs. Missing IDs are omitted.
     * Includes historical evidence, independently of the recent observation window.
     * Access failures throw IncidentDataException.
     */
    Map<UUID, HealthCheckResult> findByIds(Set<UUID> observationIds);
}
