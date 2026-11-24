package use_case.investigate_incident;

import entity.FailureEvent;
import entity.HealthCheckResult;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * UC2 emits one stable event ID per threshold crossing, reusing it on retries.
 * Recent observations belong to this system and include PASS/FAIL/MONITORING_ERROR. Observations of services
 * that are no longer configured are ignored, so removing a service does not lose another service's failure.
 * UC2 retains observations by ID before delivery, including failure evidence.
 * The failure's evidence may predate the recent-observation window.
 */
public final class FailureInput {
    private final UUID systemId;
    private final FailureEvent failure;
    private final List<HealthCheckResult> recentObservations;

    public FailureInput(UUID systemId, FailureEvent failure, List<HealthCheckResult> recentObservations) {
        this.systemId = Objects.requireNonNull(systemId, "systemId");
        this.failure = Objects.requireNonNull(failure, "failure");
        this.recentObservations = List.copyOf(recentObservations);
    }

    public UUID getSystemId() { return systemId; }
    public FailureEvent getFailure() { return failure; }
    public List<HealthCheckResult> getRecentObservations() { return recentObservations; }
}
