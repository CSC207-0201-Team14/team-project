package use_case.monitor_service_health;

import entity.FailureEvent;
import entity.ServiceHealth;

import java.util.Objects;
import java.util.Optional;

public final class HealthEvaluation {

    private final ServiceHealth health;
    private final FailureEvent failureEvent;

    /** failureEvent may be null when no new failure occurred. */
    public HealthEvaluation(
            ServiceHealth health,
            FailureEvent failureEvent
    ) {
        this.health = Objects.requireNonNull(health, "health");
        this.failureEvent = failureEvent;
    }

    public ServiceHealth getHealth() {
        return health;
    }

    public Optional<FailureEvent> getFailureEvent() {
        return Optional.ofNullable(failureEvent);
    }
}