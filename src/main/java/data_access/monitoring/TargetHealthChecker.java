package data_access.monitoring;

import entity.HealthCheckResult;
import entity.Service;
import use_case.monitor_service_health.HealthChecker;

import java.util.Objects;

/** Routes each configured target to its checker without putting transport choices in the interactor. */
public final class TargetHealthChecker implements HealthChecker {
    private final HealthChecker http;
    private final HealthChecker docker;

    public TargetHealthChecker(HealthChecker http, HealthChecker docker) {
        this.http = Objects.requireNonNull(http, "http");
        this.docker = Objects.requireNonNull(docker, "docker");
    }

    @Override
    public HealthCheckResult check(Service service) {
        Objects.requireNonNull(service, "service");
        switch (service.getMonitoringTarget().getMethod()) {
            case HTTP:
                return http.check(service);
            case DOCKER:
                return docker.check(service);
            default:
                throw new IllegalArgumentException("Unsupported monitoring method");
        }
    }
}
