package use_case.monitor_service_health;

import entity.HealthCheckResult;

import java.util.List;
import java.util.UUID;

/** Stores health check observations and provides service history. */
public interface ObservationStore {

    /**
     * Saves an observation.
     *
     * @throws ObservationStoreException if the observation cannot be saved
     */
    void save(HealthCheckResult result);

    /**
     * Returns observations from oldest to newest, or an empty list.
     *
     * @throws ObservationStoreException if history cannot be read
     */
    List<HealthCheckResult> findByServiceId(UUID serviceId);
}