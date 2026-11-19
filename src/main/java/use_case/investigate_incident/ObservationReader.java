package use_case.investigate_incident;

import entity.HealthCheckResult;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public interface ObservationReader {
    /**
     * Returns retained observations keyed by their IDs, searching only the given services.
     * Missing IDs, and observations of other services, are omitted.
     * Includes historical evidence, independently of the recent observation window.
     * Access failures throw IncidentDataException.
     */
    Map<UUID, HealthCheckResult> findByIds(Set<UUID> serviceIds, Set<UUID> observationIds);
}
