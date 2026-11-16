package entity;

import java.time.Instant;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.UUID;

public final class HealthCheckResult {
    private final UUID id;
    private final UUID serviceId;
    private final Instant timestamp;
    private final Outcome outcome;
    private final OptionalLong responseTimeMs;
    private final String message;

    public HealthCheckResult(UUID id, UUID serviceId, Instant timestamp, Outcome outcome,
                             OptionalLong responseTimeMs, String message) {
        this.id = Objects.requireNonNull(id, "id");
        this.serviceId = Objects.requireNonNull(serviceId, "serviceId");
        this.timestamp = Objects.requireNonNull(timestamp, "timestamp");
        this.outcome = Objects.requireNonNull(outcome, "outcome");
        this.responseTimeMs = Objects.requireNonNull(responseTimeMs, "responseTimeMs");
        if (responseTimeMs.isPresent() && responseTimeMs.getAsLong() < 0) {
            throw new IllegalArgumentException("Response time must not be negative");
        }
        this.message = Objects.requireNonNull(message, "message");
    }

    public enum Outcome { PASS, FAIL, MONITORING_ERROR }

    public UUID getId() {
        return id;
    }

    public UUID getServiceId() {
        return serviceId;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public Outcome getOutcome() {
        return outcome;
    }

    public OptionalLong getResponseTimeMs() {
        return responseTimeMs;
    }

    public String getMessage() {
        return message;
    }
}
