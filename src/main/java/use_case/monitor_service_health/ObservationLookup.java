package use_case.monitor_service_health;

import entity.HealthCheckResult;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

public interface ObservationLookup {

    /**
     * Returns requested observations belonging to the allowed services.
     * Missing observations and observations outside the scope are omitted.
     *
     * @throws ObservationStoreException if observations cannot be read
     */
    Map<UUID, HealthCheckResult> findByIds(
            Set<UUID> allowedServiceIds,
            Set<UUID> observationIds
    );
}