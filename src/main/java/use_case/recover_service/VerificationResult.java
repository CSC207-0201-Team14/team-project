package use_case.recover_service;

import entity.ServiceHealth;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/** Whether the services required by an incident satisfy their recovery thresholds since verification started. */
public final class VerificationResult {
    public enum Status {
        /** Every required service has enough fresh consecutive passing checks. */
        SATISFIED,
        /** At least one required service has not recovered yet; monitoring continues. */
        PENDING,
        /** At least one required service could not be verified, such as a monitoring error. */
        MONITORING_ERROR
    }

    private final UUID incidentId;
    private final Instant verificationStartedAt;
    private final Status status;
    private final List<ServiceVerification> services;

    public VerificationResult(UUID incidentId, Instant verificationStartedAt, Status status,
                              List<ServiceVerification> services) {
        this.incidentId = Objects.requireNonNull(incidentId, "incidentId");
        this.verificationStartedAt = Objects.requireNonNull(verificationStartedAt, "verificationStartedAt");
        this.status = Objects.requireNonNull(status, "status");
        this.services = List.copyOf(services);
        boolean allVerified = this.services.stream().allMatch(ServiceVerification::isVerified);
        if ((status == Status.SATISFIED) != allVerified) {
            throw new IllegalArgumentException("Only a result whose services are all verified is satisfied");
        }
    }

    public UUID getIncidentId() { return incidentId; }
    public Instant getVerificationStartedAt() { return verificationStartedAt; }
    public Status getStatus() { return status; }
    public List<ServiceVerification> getServices() { return services; }

    public boolean isSatisfied() {
        return status == Status.SATISFIED;
    }

    /** Services that still prevent resolution. */
    public List<ServiceVerification> getRemainingServices() {
        return services.stream().filter(service -> !service.isVerified()).collect(Collectors.toUnmodifiableList());
    }

    /** Evaluated health per service, in the form Incident.resolve accepts. */
    public Map<UUID, ServiceHealth> getHealthByService() {
        Map<UUID, ServiceHealth> health = new LinkedHashMap<>();
        services.forEach(service -> health.put(service.getServiceId(), service.getHealth()));
        return Map.copyOf(health);
    }
}
