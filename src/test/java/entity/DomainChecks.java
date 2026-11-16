package entity;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;

/** Run directly with Java until the team adopts a test framework. */
public final class DomainChecks {
    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

    public static void main(String[] args) {
        validatesSystemGraph();
        separatesObservationsFromEvaluatedHealth();
        retainsFindingsAndVerifiesRecovery();
    }

    private static void validatesSystemGraph() {
        UUID systemId = UUID.randomUUID();
        MonitoringPolicy policy = new MonitoringPolicy(Duration.ofSeconds(10), Duration.ofSeconds(2), 2, 3);
        MonitoringTarget target = MonitoringTarget.http(URI.create("https://example.com/health"));
        Service api = new Service(UUID.randomUUID(), systemId, "API", target, policy);
        Service db = new Service(UUID.randomUUID(), systemId, "DB", MonitoringTarget.docker("database"), policy);
        Dependency edge = new Dependency(UUID.randomUUID(), api.getId(), db.getId());
        List<Service> services = new ArrayList<>(List.of(api, db));
        MonitoredSystem system = new MonitoredSystem(systemId, "Orders", "", services, List.of(edge));
        services.clear();
        check(system.getServices().size() == 2, "System must retain its own roster");
        rejects(IllegalArgumentException.class, () -> new Dependency(UUID.randomUUID(), api.getId(), api.getId()));
        rejects(IllegalArgumentException.class,
                () -> new MonitoredSystem(systemId, "Orders", "", List.of(api), List.of(edge)));
        rejects(IllegalArgumentException.class, () -> new MonitoredSystem(systemId, "Orders", "",
                List.of(api, db), List.of(edge, new Dependency(UUID.randomUUID(), api.getId(), db.getId()))));
        rejects(IllegalArgumentException.class,
                () -> MonitoringTarget.http(URI.create("file:///health")));
        rejects(IllegalArgumentException.class,
                () -> new MonitoringPolicy(Duration.ofSeconds(10), Duration.ofSeconds(2), 2, 0));
    }

    private static void separatesObservationsFromEvaluatedHealth() {
        UUID service = UUID.randomUUID();
        HealthCheckResult error = result(service, START, HealthCheckResult.Outcome.MONITORING_ERROR);
        check(ServiceHealth.unobserved(service).getStatus() == HealthStatus.UNKNOWN, "Initial health is unknown");
        check(ServiceHealth.evaluated(HealthStatus.UNKNOWN, error).getLatestResult().orElseThrow() == error,
                "Monitoring errors remain visible as evidence");
        rejects(IllegalArgumentException.class, () -> ServiceHealth.evaluated(HealthStatus.UNHEALTHY, error));
        rejects(IllegalArgumentException.class, () -> new FailureEvent(UUID.randomUUID(), service, START, List.of(error)));
    }

    private static void retainsFindingsAndVerifiesRecovery() {
        UUID service = UUID.randomUUID();
        UUID dependent = UUID.randomUUID();
        HealthCheckResult failed = result(service, START, HealthCheckResult.Outcome.FAIL);
        FailureEvent failure = new FailureEvent(UUID.randomUUID(), service, START, List.of(failed));
        Incident open = Incident.open(UUID.randomUUID(), UUID.randomUUID(), START, failure);
        check(open.getActiveFindings().isEmpty(), "An incident can open with an unknown cause");
        Instant verificationStart = START.plusSeconds(2);
        Instant finished = START.plusSeconds(3);
        Incident investigating = open.startInvestigation(START.plusSeconds(1));
        DiagnosticFinding hypothesis = new DiagnosticFinding(UUID.randomUUID(), "dependency-health", dependent,
                START.plusSeconds(1), Optional.of(service), List.of(failed.getId()),
                List.of(new Dependency(UUID.randomUUID(), dependent, service)),
                "A failed dependency may explain the degradation", List.of("Inspect the dependency"));
        Incident assessed = investigating.updateAssessment(Set.of(dependent), List.of(hypothesis));
        Incident reassessed = assessed.updateAssessment(Set.of(dependent), List.of());
        check(reassessed.getActiveFindings().isEmpty() && reassessed.getFindingHistory().size() == 1,
                "Reassessment preserves the previous finding");

        RecoveryAction action = new RecoveryAction(UUID.randomUUID(), reassessed.getId(), service,
                RecoveryAction.ActionType.START_DOCKER_CONTAINER, "Start the container");
        check(action.requiresConfirmation(), "Executable actions require confirmation");
        RecoveryAttempt attempt = new RecoveryAttempt(UUID.randomUUID(), action.getId(), START.plusSeconds(1),
                verificationStart, RecoveryAttempt.Outcome.SUCCEEDED, "");
        rejects(IllegalArgumentException.class, () -> new RecoveryAttempt(UUID.randomUUID(), action.getId(),
                verificationStart, START, RecoveryAttempt.Outcome.FAILED, ""));
        check(attempt.getOutcome() == RecoveryAttempt.Outcome.SUCCEEDED
                && reassessed.getStatus() == Incident.Status.INVESTIGATING, "Execution alone cannot resolve an incident");

        ServiceHealth recovered = healthy(service, finished);
        ServiceHealth dependentRecovered = healthy(dependent, finished);
        rejects(IllegalStateException.class, () -> reassessed.resolve(finished, verificationStart,
                Map.of(service, healthy(service, START), dependent, dependentRecovered)));
        rejects(IllegalStateException.class,
                () -> reassessed.resolve(finished, verificationStart, Map.of(service, recovered)));
        Incident resolved = reassessed.resolve(finished, verificationStart,
                Map.of(service, recovered, dependent, dependentRecovered));
        check(resolved.getStatus() == Incident.Status.RESOLVED && resolved.getResolutionEvidence().size() == 2,
                "Resolution retains passing evidence for both services");
        rejects(IllegalStateException.class, () -> resolved.recordFailure(failure));
    }

    private static ServiceHealth healthy(UUID service, Instant timestamp) {
        return ServiceHealth.evaluated(HealthStatus.HEALTHY, result(service, timestamp, HealthCheckResult.Outcome.PASS));
    }

    private static HealthCheckResult result(UUID service, Instant timestamp, HealthCheckResult.Outcome outcome) {
        return new HealthCheckResult(UUID.randomUUID(), service, timestamp, outcome, OptionalLong.empty(), "");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void rejects(Class<? extends RuntimeException> expected, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException exception) {
            if (expected.isInstance(exception)) {
                return;
            }
            throw exception;
        }
        throw new AssertionError("Expected " + expected.getSimpleName());
    }
}
