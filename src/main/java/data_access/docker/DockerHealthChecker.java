package data_access.docker;

import entity.HealthCheckResult;
import entity.MonitoringTarget;
import entity.Service;
import use_case.monitor_service_health.HealthChecker;

import java.io.IOException;
import java.time.Clock;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Translates a read-only Engine observation into UC2 outcomes using the application clock. */
public final class DockerHealthChecker implements HealthChecker {
    private final DockerInspector inspector;
    private final Clock clock;

    public DockerHealthChecker(DockerInspector inspector, Clock clock) {
        this.inspector = Objects.requireNonNull(inspector, "inspector");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public HealthCheckResult check(Service service) {
        Objects.requireNonNull(service, "service");
        if (service.getMonitoringTarget().getMethod() != MonitoringTarget.Method.DOCKER) {
            return result(service, HealthCheckResult.Outcome.MONITORING_ERROR, OptionalLong.empty(),
                    "Expected a Docker monitoring target");
        }
        String containerId = service.getMonitoringTarget().getContainerId().orElseThrow();
        long started = System.nanoTime();
        try {
            DockerInspection inspection = inspector.inspect(containerId, service.getMonitoringPolicy().getTimeout());
            OptionalLong elapsed = OptionalLong.of(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
            String prefix = "Docker container " + containerId + ": ";
            if (inspection.getState() != DockerInspection.State.RUNNING) {
                return result(service, HealthCheckResult.Outcome.FAIL, elapsed,
                        prefix + inspection.getState().name().toLowerCase(java.util.Locale.ROOT));
            }
            if (inspection.isOomKilled()) {
                return result(service, HealthCheckResult.Outcome.FAIL, elapsed, prefix + "reported OOMKilled");
            }
            switch (inspection.getHealth()) {
                case HEALTHY:
                    return result(service, HealthCheckResult.Outcome.PASS, elapsed, prefix + "running and healthy");
                case UNHEALTHY:
                    return result(service, HealthCheckResult.Outcome.FAIL, elapsed, prefix + "healthcheck is unhealthy");
                case STARTING:
                    return result(service, HealthCheckResult.Outcome.MONITORING_ERROR, elapsed,
                            prefix + "healthcheck is still starting; health is not known yet");
                default:
                    return result(service, HealthCheckResult.Outcome.PASS, elapsed,
                            prefix + "running; no HEALTHCHECK configured (runtime state only)");
            }
        } catch (IOException | RuntimeException exception) {
            return result(service, HealthCheckResult.Outcome.MONITORING_ERROR, OptionalLong.empty(),
                    "Docker check could not complete for " + containerId + ": " + exception.getMessage());
        }
    }

    private HealthCheckResult result(Service service, HealthCheckResult.Outcome outcome,
                                     OptionalLong elapsed, String message) {
        return new HealthCheckResult(UUID.randomUUID(), service.getId(), clock.instant(), outcome, elapsed, message);
    }
}
