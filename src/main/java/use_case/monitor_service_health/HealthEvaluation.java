package use_case.monitor_service_health;

import entity.FailureEvent;
import entity.ServiceHealth;

import java.util.Objects;
import java.util.Optional;

public final class HealthEvaluation {

    private final ServiceHealth health;
    private final Optional<FailureEvent> failureEvent;

    public HealthEvaluation(
            ServiceHealth health,
            Optional<FailureEvent> failureEvent
    ) {
        this.health = Objects.requireNonNull(health, "health");
        this.failureEvent = Objects.requireNonNull(
                failureEvent, "failureEvent"
        );
    }

    public ServiceHealth getHealth() {
        return health;
    }

    public Optional<FailureEvent> getFailureEvent() {
        return failureEvent;
    }
}