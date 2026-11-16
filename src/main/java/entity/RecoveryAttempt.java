package entity;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class RecoveryAttempt {
    private final UUID id;
    private final UUID actionId;
    private final Instant startedAt;
    private final Instant completedAt;
    private final Outcome outcome;
    private final String message;

    public RecoveryAttempt(UUID id, UUID actionId, Instant startedAt, Instant completedAt,
                           Outcome outcome, String message) {
        this.id = Objects.requireNonNull(id, "id");
        this.actionId = Objects.requireNonNull(actionId, "actionId");
        this.startedAt = Objects.requireNonNull(startedAt, "startedAt");
        this.completedAt = Objects.requireNonNull(completedAt, "completedAt");
        this.outcome = Objects.requireNonNull(outcome, "outcome");
        this.message = Objects.requireNonNull(message, "message");
        if (completedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("An attempt cannot complete before it starts");
        }
    }

    public enum Outcome { SUCCEEDED, FAILED, PERMISSION_DENIED }

    public UUID getId() {
        return id;
    }

    public UUID getActionId() {
        return actionId;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public Outcome getOutcome() {
        return outcome;
    }

    public String getMessage() {
        return message;
    }
}
