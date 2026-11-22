package use_case.monitor_service_health;

import entity.FailureEvent;
import entity.ServiceHealth;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class HealthEvaluation {

    private final ServiceHealth health;
    private final FailureEvent failureEvent;
    private final List<FailureEvent> failureEvents;

    public HealthEvaluation(
            ServiceHealth health,
            FailureEvent failureEvent
    ) {
        this(
                health,
                failureEvent,
                failureEvent == null ? List.of() : List.of(failureEvent)
        );
    }

    public HealthEvaluation(
            ServiceHealth health,
            FailureEvent failureEvent,
            List<FailureEvent> failureEvents
    ) {
        this.health = Objects.requireNonNull(health, "health");
        this.failureEvent = failureEvent;
        this.failureEvents = List.copyOf(failureEvents);
    }

    public ServiceHealth getHealth() {
        return health;
    }

    /** Returns the event for the current unhealthy episode, if any. */
    public Optional<FailureEvent> getFailureEvent() {
        return Optional.ofNullable(failureEvent);
    }

    /** Returns all threshold-crossing events identified in the supplied history. */
    public List<FailureEvent> getFailureEvents() {
        return failureEvents;
    }
}