package entity;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** The current evaluation of a service's observations. */
public final class ServiceHealth {
    private final UUID serviceId;
    private final HealthStatus status;
    private final HealthCheckResult latestResult;

    private ServiceHealth(UUID serviceId, HealthStatus status, HealthCheckResult latestResult) {
        this.serviceId = serviceId;
        this.status = status;
        this.latestResult = latestResult;
    }

    public static ServiceHealth unobserved(UUID serviceId) {
        return new ServiceHealth(Objects.requireNonNull(serviceId, "serviceId"), HealthStatus.UNKNOWN, null);
    }

    public static ServiceHealth evaluated(HealthStatus status, HealthCheckResult latestResult) {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(latestResult, "latestResult");
        if (latestResult.getOutcome() == HealthCheckResult.Outcome.MONITORING_ERROR
                && status != HealthStatus.UNKNOWN) {
            throw new IllegalArgumentException("A monitoring error yields unknown health");
        }
        return new ServiceHealth(latestResult.getServiceId(), status, latestResult);
    }

    public UUID getServiceId() {
        return serviceId;
    }

    public HealthStatus getStatus() {
        return status;
    }

    public Optional<HealthCheckResult> getLatestResult() {
        return Optional.ofNullable(latestResult);
    }
}
