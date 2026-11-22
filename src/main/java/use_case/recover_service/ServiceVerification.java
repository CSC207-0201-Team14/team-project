package use_case.recover_service;

import entity.HealthStatus;
import entity.ServiceHealth;
import java.util.Objects;
import java.util.UUID;

/** Verification progress for one service that must recover before the incident can be resolved. */
public final class ServiceVerification {
    private final UUID serviceId;
    private final ServiceHealth health;
    private final int consecutivePasses;
    private final int requiredPasses;
    private final String explanation;

    public ServiceVerification(UUID serviceId, ServiceHealth health, int consecutivePasses, int requiredPasses,
                               String explanation) {
        this.serviceId = Objects.requireNonNull(serviceId, "serviceId");
        this.health = Objects.requireNonNull(health, "health");
        this.consecutivePasses = consecutivePasses;
        this.requiredPasses = requiredPasses;
        this.explanation = Objects.requireNonNull(explanation, "explanation");
        if (!serviceId.equals(health.getServiceId()) || consecutivePasses < 0 || requiredPasses < 0) {
            throw new IllegalArgumentException("Verification progress must describe its own service");
        }
    }

    public UUID getServiceId() { return serviceId; }
    /** Health evaluated only from checks recorded since verification started. */
    public ServiceHealth getHealth() { return health; }
    public int getConsecutivePasses() { return consecutivePasses; }
    /** The service's configured recovery threshold, or 0 when the service is no longer configured. */
    public int getRequiredPasses() { return requiredPasses; }
    public String getExplanation() { return explanation; }

    public boolean isVerified() {
        return health.getStatus() == HealthStatus.HEALTHY;
    }
}
