package use_case.recover_service;

import entity.HealthCheckResult;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Reads the observations recorded by health monitoring (UC2) after recovery began.
 * Recovery does not run checks itself; monitoring keeps recording them while the incident is verified.
 */
public interface RecoveryObservationReader {
    /**
     * Returns the service's observations with timestamps at or after since, oldest first, or an empty list.
     * Access failures throw IncidentDataException.
     */
    List<HealthCheckResult> findByServiceSince(UUID serviceId, Instant since);
}
