package entity;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class FailureEvent {
    private final UUID id;
    private final UUID serviceId;
    private final Instant timestamp;
    private final List<HealthCheckResult> evidence;

    public FailureEvent(UUID id, UUID serviceId, Instant timestamp, List<HealthCheckResult> evidence) {
        this.id = Objects.requireNonNull(id, "id");
        this.serviceId = Objects.requireNonNull(serviceId, "serviceId");
        this.timestamp = Objects.requireNonNull(timestamp, "timestamp");
        this.evidence = List.copyOf(evidence);
        if (this.evidence.isEmpty()) {
            throw new IllegalArgumentException("A failure event requires failed observations");
        }
        Set<UUID> resultIds = new HashSet<>();
        for (HealthCheckResult result : this.evidence) {
            if (!serviceId.equals(result.getServiceId()) || result.getOutcome() != HealthCheckResult.Outcome.FAIL
                    || result.getTimestamp().isAfter(timestamp) || !resultIds.add(result.getId())) {
                throw new IllegalArgumentException("Failure evidence must be distinct failed checks for the service");
            }
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getServiceId() {
        return serviceId;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public List<HealthCheckResult> getEvidence() {
        return evidence;
    }
}
