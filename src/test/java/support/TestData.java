package support;

import entity.FailureEvent;
import entity.HealthCheckResult;
import entity.HealthStatus;
import entity.MonitoringPolicy;
import entity.MonitoringTarget;
import entity.Service;
import entity.ServiceHealth;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;

/** Small domain builders for check programs; each value gets a fresh ID. */
public final class TestData {
    public static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    public static final MonitoringPolicy POLICY =
            new MonitoringPolicy(Duration.ofSeconds(10), Duration.ofSeconds(2), 2, 2);

    private TestData() {
    }

    public static Instant at(long seconds) {
        return T0.plusSeconds(seconds);
    }

    public static HealthCheckResult observation(UUID serviceId, Instant timestamp, HealthCheckResult.Outcome outcome) {
        return new HealthCheckResult(UUID.randomUUID(), serviceId, timestamp, outcome, OptionalLong.empty(), "");
    }

    /** A failure event whose single failed check is its evidence. */
    public static FailureEvent failure(UUID serviceId, Instant timestamp) {
        return new FailureEvent(UUID.randomUUID(), serviceId, timestamp,
                List.of(observation(serviceId, timestamp, HealthCheckResult.Outcome.FAIL)));
    }

    public static ServiceHealth healthy(UUID serviceId, Instant timestamp) {
        return ServiceHealth.evaluated(HealthStatus.HEALTHY,
                observation(serviceId, timestamp, HealthCheckResult.Outcome.PASS));
    }

    public static Service service(UUID id, UUID systemId, String name, MonitoringTarget target) {
        return new Service(id, systemId, name, target, POLICY);
    }
}
