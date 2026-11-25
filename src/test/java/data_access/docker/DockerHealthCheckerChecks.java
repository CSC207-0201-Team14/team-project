package data_access.docker;

import data_access.monitoring.TargetHealthChecker;
import entity.HealthCheckResult;
import entity.MonitoringPolicy;
import entity.MonitoringTarget;
import entity.Service;

import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/** Pure checker tests: injected fake inspectors do not require Docker or network access. */
public final class DockerHealthCheckerChecks {
    private static final Instant NOW = Instant.parse("2026-11-25T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final Duration TIMEOUT = Duration.ofSeconds(2);
    private static final Service DOCKER = service(MonitoringTarget.docker("fake-container"));

    public static void main(String[] args) {
        checksStateAndHealth();
        checksErrorsAndClock();
        routesByTarget();
        System.out.println("DockerHealthCheckerChecks passed");
    }

    private static void checksStateAndHealth() {
        require(check(DockerInspection.State.RUNNING, DockerInspection.Health.HEALTHY, false)
                .getOutcome() == HealthCheckResult.Outcome.PASS, "Running and healthy must pass");
        HealthCheckResult runtimeOnly = check(DockerInspection.State.RUNNING, DockerInspection.Health.NONE, false);
        require(runtimeOnly.getOutcome() == HealthCheckResult.Outcome.PASS
                        && runtimeOnly.getMessage().contains("runtime state only"),
                "No HEALTHCHECK means a clearly labelled runtime-state observation");
        require(check(DockerInspection.State.RUNNING, DockerInspection.Health.UNHEALTHY, false)
                .getOutcome() == HealthCheckResult.Outcome.FAIL, "An unhealthy healthcheck must fail");
        require(check(DockerInspection.State.RUNNING, DockerInspection.Health.STARTING, false)
                .getOutcome() == HealthCheckResult.Outcome.MONITORING_ERROR, "Starting health is unknown");
        require(check(DockerInspection.State.RUNNING, DockerInspection.Health.HEALTHY, true)
                .getOutcome() == HealthCheckResult.Outcome.FAIL, "OOMKilled must not be presented as healthy");
        for (DockerInspection.State state : DockerInspection.State.values()) {
            if (state != DockerInspection.State.RUNNING) {
                require(check(state, DockerInspection.Health.NONE, false).getOutcome() == HealthCheckResult.Outcome.FAIL,
                        "A non-running or missing container is a negative observation: " + state);
            }
        }
    }

    private static HealthCheckResult check(DockerInspection.State state, DockerInspection.Health health, boolean oom) {
        return new DockerHealthChecker((id, timeout) -> new DockerInspection(state, health, oom), CLOCK).check(DOCKER);
    }

    private static void checksErrorsAndClock() {
        AtomicInteger calls = new AtomicInteger();
        DockerHealthChecker checker = new DockerHealthChecker((id, timeout) -> {
            require(id.equals("fake-container") && timeout.equals(TIMEOUT),
                    "The configured target and timeout must reach the inspector");
            calls.incrementAndGet();
            return new DockerInspection(DockerInspection.State.RUNNING, DockerInspection.Health.HEALTHY, false);
        }, CLOCK);
        HealthCheckResult first = checker.check(DOCKER);
        HealthCheckResult second = checker.check(DOCKER);
        require(first.getTimestamp().equals(NOW) && first.getServiceId().equals(DOCKER.getId())
                        && !first.getId().equals(second.getId()) && first.getResponseTimeMs().isPresent(),
                "Each result needs fresh identity, the shared clock, service identity and response timing");
        checker.check(service(MonitoringTarget.http(URI.create("http://localhost/health"))));
        require(calls.get() == 2, "A wrong target type must not contact Docker");
        HealthCheckResult unavailable = new DockerHealthChecker((id, timeout) -> {
            throw new IOException("Engine unavailable or timed out");
        }, CLOCK).check(DOCKER);
        require(unavailable.getOutcome() == HealthCheckResult.Outcome.MONITORING_ERROR
                        && unavailable.getTimestamp().equals(NOW) && unavailable.getResponseTimeMs().isEmpty(),
                "An unreadable engine is a monitoring error, not proof of container failure");
        HealthCheckResult invalid = new DockerHealthChecker((id, timeout) -> {
            throw new IllegalStateException("Bad transport configuration");
        }, CLOCK).check(DOCKER);
        require(invalid.getOutcome() == HealthCheckResult.Outcome.MONITORING_ERROR,
                "Invalid transport configuration must be surfaced as an observation");
    }

    private static void routesByTarget() {
        AtomicInteger httpCalls = new AtomicInteger();
        AtomicInteger dockerCalls = new AtomicInteger();
        TargetHealthChecker router = new TargetHealthChecker(service -> {
            httpCalls.incrementAndGet();
            return check(DockerInspection.State.RUNNING, DockerInspection.Health.NONE, false);
        }, service -> {
            dockerCalls.incrementAndGet();
            return check(DockerInspection.State.RUNNING, DockerInspection.Health.HEALTHY, false);
        });
        router.check(DOCKER);
        router.check(service(MonitoringTarget.http(URI.create("http://localhost/health"))));
        require(httpCalls.get() == 1 && dockerCalls.get() == 1, "Each method must invoke only its matching checker");
    }

    private static Service service(MonitoringTarget target) {
        return new Service(UUID.randomUUID(), UUID.randomUUID(), "Checker test", target,
                new MonitoringPolicy(Duration.ofSeconds(3), TIMEOUT, 2, 2));
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
