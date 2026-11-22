package use_case.recover_service;

import entity.Dependency;
import entity.DiagnosticFinding;
import entity.FailureEvent;
import entity.HealthCheckResult;
import entity.HealthStatus;
import entity.Incident;
import entity.MonitoringPolicy;
import entity.MonitoringTarget;
import entity.Service;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;

/** Run explicitly after mvn test-compile, following the existing test convention. */
public final class RecoveryVerificationChecks {
    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant WINDOW = START.plusSeconds(100);
    private static final Instant NOW = START.plusSeconds(1000);
    private static final RecoveryVerifier VERIFIER = new RecoveryVerifier();

    public static void main(String[] args) {
        waitsForTheRecoveryThreshold();
        flappingResetsTheCount();
        reportsMonitoringErrors();
        ignoresChecksBeforeTheWindow();
        requiresAffectedDependentsToRecover();
        satisfiedResultResolvesTheIncident();
        laterFailureMovesTheServiceWindow();
        unconfiguredServiceCannotBeVerified();
        assessmentCountsOnlyChecksAfterTheLatestFailure();
        assessmentReportsHealthAgainstTheThreshold();
        assessmentFollowsALaterFailure();
        System.out.println("UC4 recovery verification checks passed");
    }

    private static void waitsForTheRecoveryThreshold() {
        Fixture f = new Fixture();
        f.record(f.db, 110, HealthCheckResult.Outcome.PASS);
        f.record(f.db, 120, HealthCheckResult.Outcome.PASS);
        VerificationResult result = f.evaluate(f.dbOnlyIncident());
        check(result.getStatus() == VerificationResult.Status.PENDING, "Two of three passes keep verification pending");
        ServiceVerification db = only(result.getRemainingServices());
        check(db.getConsecutivePasses() == 2 && db.getRequiredPasses() == 3
                && db.getHealth().getStatus() == HealthStatus.UNKNOWN, "Progress reports passes against the threshold");
        f.record(f.db, 130, HealthCheckResult.Outcome.PASS);
        check(f.evaluate(f.dbOnlyIncident()).isSatisfied(), "Three fresh passes satisfy the threshold");
    }

    private static void flappingResetsTheCount() {
        Fixture f = new Fixture();
        f.record(f.db, 110, HealthCheckResult.Outcome.PASS);
        f.record(f.db, 120, HealthCheckResult.Outcome.PASS);
        f.record(f.db, 130, HealthCheckResult.Outcome.FAIL);
        VerificationResult failing = f.evaluate(f.dbOnlyIncident());
        check(failing.getStatus() == VerificationResult.Status.PENDING
                && only(failing.getRemainingServices()).getHealth().getStatus() == HealthStatus.UNHEALTHY,
                "A failure after passes is reported as a remaining failure");
        f.record(f.db, 140, HealthCheckResult.Outcome.PASS);
        f.record(f.db, 150, HealthCheckResult.Outcome.PASS);
        ServiceVerification flapping = only(f.evaluate(f.dbOnlyIncident()).getRemainingServices());
        check(flapping.getConsecutivePasses() == 2 && flapping.getHealth().getStatus() == HealthStatus.UNHEALTHY,
                "Passes before a failure do not count towards recovery");
        f.record(f.db, 160, HealthCheckResult.Outcome.PASS);
        check(f.evaluate(f.dbOnlyIncident()).isSatisfied(), "Consecutive passes after the failure satisfy it");
    }

    private static void reportsMonitoringErrors() {
        Fixture f = new Fixture();
        f.record(f.db, 110, HealthCheckResult.Outcome.PASS);
        f.record(f.db, 120, HealthCheckResult.Outcome.PASS);
        f.record(f.db, 130, HealthCheckResult.Outcome.MONITORING_ERROR);
        VerificationResult result = f.evaluate(f.dbOnlyIncident());
        check(result.getStatus() == VerificationResult.Status.MONITORING_ERROR
                && only(result.getRemainingServices()).getHealth().getStatus() == HealthStatus.UNKNOWN,
                "A monitoring error is reported and yields unknown health, not a confirmed failure");
    }

    private static void ignoresChecksBeforeTheWindow() {
        Fixture f = new Fixture();
        f.record(f.db, 50, HealthCheckResult.Outcome.PASS);
        f.record(f.db, 60, HealthCheckResult.Outcome.PASS);
        f.record(f.db, 70, HealthCheckResult.Outcome.PASS);
        f.record(f.db, 110, HealthCheckResult.Outcome.PASS);
        check(only(f.evaluate(f.dbOnlyIncident()).getRemainingServices()).getConsecutivePasses() == 1,
                "Checks recorded before recovery began do not count");
        f.record(f.db, 2000, HealthCheckResult.Outcome.PASS);
        f.record(f.db, 2010, HealthCheckResult.Outcome.PASS);
        check(!f.evaluate(f.dbOnlyIncident()).isSatisfied(), "Checks after the evaluation time do not count");
    }

    private static void requiresAffectedDependentsToRecover() {
        Fixture f = new Fixture();
        Incident incident = f.assessedIncident();
        check(incident.getRecoveryRequiredServiceIds().equals(Set.of(f.db.getId(), f.api.getId())),
                "The fixture requires the suspected cause and its affected dependent");
        f.passes(f.db, 110, 3);
        f.record(f.api, 115, HealthCheckResult.Outcome.FAIL);
        VerificationResult result = f.evaluate(incident);
        check(result.getStatus() == VerificationResult.Status.PENDING
                && only(result.getRemainingServices()).getServiceId().equals(f.api.getId()),
                "A recovered cause with a failing dependent leaves only the dependent remaining");
    }

    private static void satisfiedResultResolvesTheIncident() {
        Fixture f = new Fixture();
        Incident incident = f.assessedIncident();
        f.passes(f.db, 110, 3);
        f.passes(f.api, 115, 2);
        VerificationResult result = f.evaluate(incident);
        check(result.isSatisfied() && result.getRemainingServices().isEmpty(), "Both services have recovered");
        Incident resolved = incident.resolve(NOW, WINDOW, result.getHealthByService());
        check(resolved.getStatus() == Incident.Status.RESOLVED && resolved.getResolutionEvidence().size() == 2,
                "The incident entity accepts the verified health as resolution evidence");
    }

    private static void laterFailureMovesTheServiceWindow() {
        Fixture f = new Fixture();
        Incident incident = f.dbOnlyIncident();
        HealthCheckResult lateFailure = f.record(f.db, 150, HealthCheckResult.Outcome.FAIL);
        Incident updated = incident.recordFailure(
                new FailureEvent(UUID.randomUUID(), f.db.getId(), START.plusSeconds(150), List.of(lateFailure)));
        f.passes(f.db, 110, 3);
        check(!f.evaluate(updated).isSatisfied(), "Passes before a later recorded failure do not count");
        f.passes(f.db, 160, 3);
        VerificationResult result = f.evaluate(updated);
        check(result.isSatisfied(), "Passes after the latest failure satisfy verification");
        check(updated.resolve(NOW, WINDOW, result.getHealthByService()).getStatus() == Incident.Status.RESOLVED,
                "The entity agrees the passes are fresh");
    }

    private static void unconfiguredServiceCannotBeVerified() {
        Fixture f = new Fixture();
        Incident incident = f.dbOnlyIncident();
        VerificationResult result = VERIFIER.evaluate(incident, WINDOW, Map.of(), Map.of(), NOW);
        check(result.getStatus() == VerificationResult.Status.MONITORING_ERROR
                && only(result.getRemainingServices()).getExplanation().contains("no longer configured"),
                "A required service missing from the configuration cannot be verified");
    }

    private static void assessmentCountsOnlyChecksAfterTheLatestFailure() {
        Fixture f = new Fixture();
        f.record(f.db, 1, HealthCheckResult.Outcome.PASS);
        f.record(f.db, 2, HealthCheckResult.Outcome.PASS);
        f.record(f.db, 3, HealthCheckResult.Outcome.PASS);
        f.record(f.db, 5, HealthCheckResult.Outcome.FAIL);
        ServiceVerification before = f.assess(f.dbOnlyIncident());
        check(before.getConsecutivePasses() == 0 && before.getHealth().getStatus() == HealthStatus.UNKNOWN
                && before.getExplanation().contains("since its last recorded failure"),
                "Passes before the failure, and the failing check itself, are not counted");
        f.passes(f.db, 40, 3);
        ServiceVerification after = f.assess(f.dbOnlyIncident());
        check(after.isVerified() && after.getConsecutivePasses() == 3
                && after.getExplanation().contains("since its last recorded failure"),
                "Passes after the failure count, without any verification having started");
    }

    private static void assessmentReportsHealthAgainstTheThreshold() {
        Fixture partial = new Fixture();
        partial.passes(partial.db, 40, 2);
        ServiceVerification recovering = partial.assess(partial.dbOnlyIncident());
        check(recovering.getHealth().getStatus() == HealthStatus.UNKNOWN && recovering.getConsecutivePasses() == 2
                && recovering.getRequiredPasses() == 3, "Passes below the threshold leave health unknown");

        Fixture failing = new Fixture();
        failing.passes(failing.db, 40, 3);
        failing.record(failing.db, 50, HealthCheckResult.Outcome.FAIL);
        check(failing.assess(failing.dbOnlyIncident()).getHealth().getStatus() == HealthStatus.UNHEALTHY,
                "A failing latest check is unhealthy even after earlier passes met the threshold");

        Fixture unobserved = new Fixture();
        ServiceVerification none = unobserved.assess(unobserved.dbOnlyIncident());
        check(none.getHealth().getStatus() == HealthStatus.UNKNOWN && none.getHealth().getLatestResult().isEmpty(),
                "Without checks since the failure, health is unknown");
    }

    private static void assessmentFollowsALaterFailure() {
        Fixture f = new Fixture();
        f.passes(f.db, 40, 3);
        HealthCheckResult lateFailure = f.record(f.db, 150, HealthCheckResult.Outcome.FAIL);
        Incident updated = f.dbOnlyIncident().recordFailure(
                new FailureEvent(UUID.randomUUID(), f.db.getId(), START.plusSeconds(150), List.of(lateFailure)));
        check(!f.assess(updated).isVerified(), "Passes before a later recorded failure do not count");
        f.passes(f.db, 160, 3);
        check(f.assess(updated).isVerified(), "Passes after the latest failure count");
    }

    private static final class Fixture {
        final UUID systemId = UUID.randomUUID();
        final MonitoringPolicy dbPolicy = new MonitoringPolicy(Duration.ofSeconds(10), Duration.ofSeconds(2), 2, 3);
        final MonitoringPolicy apiPolicy = new MonitoringPolicy(Duration.ofSeconds(10), Duration.ofSeconds(2), 2, 2);
        final Service db = new Service(UUID.randomUUID(), systemId, "DB", MonitoringTarget.docker("db"), dbPolicy);
        final Service api = new Service(UUID.randomUUID(), systemId, "API",
                MonitoringTarget.http(URI.create("http://localhost:8080/health")), apiPolicy);
        final Map<UUID, List<HealthCheckResult>> observations = new HashMap<>();

        HealthCheckResult record(Service service, long seconds, HealthCheckResult.Outcome outcome) {
            HealthCheckResult result = new HealthCheckResult(UUID.randomUUID(), service.getId(),
                    START.plusSeconds(seconds), outcome, OptionalLong.empty(), outcome.name());
            observations.computeIfAbsent(service.getId(), id -> new ArrayList<>()).add(result);
            return result;
        }

        void passes(Service service, long fromSeconds, int count) {
            for (int i = 0; i < count; i++) {
                record(service, fromSeconds + i, HealthCheckResult.Outcome.PASS);
            }
        }

        Incident dbOnlyIncident() {
            HealthCheckResult failed = new HealthCheckResult(UUID.randomUUID(), db.getId(), START.plusSeconds(5),
                    HealthCheckResult.Outcome.FAIL, OptionalLong.empty(), "down");
            FailureEvent failure = new FailureEvent(UUID.randomUUID(), db.getId(), START.plusSeconds(5), List.of(failed));
            return Incident.open(UUID.randomUUID(), systemId, START.plusSeconds(10), failure)
                    .startInvestigation(START.plusSeconds(20));
        }

        Incident assessedIncident() {
            Incident incident = dbOnlyIncident();
            UUID evidence = incident.getFailures().get(0).getEvidence().get(0).getId();
            DiagnosticFinding finding = new DiagnosticFinding(UUID.randomUUID(), "dependency-failure", api.getId(),
                    START.plusSeconds(30), Optional.of(db.getId()), List.of(evidence),
                    List.of(new Dependency(UUID.randomUUID(), api.getId(), db.getId())),
                    "DB may explain API impact", List.of("Check DB logs"));
            return incident.updateAssessment(Set.of(api.getId()), List.of(finding));
        }

        VerificationResult evaluate(Incident incident) {
            return VERIFIER.evaluate(incident, WINDOW, Map.of(db.getId(), db, api.getId(), api), observations, NOW);
        }

        ServiceVerification assess(Incident incident) {
            return VERIFIER.assessSinceLatestFailure(incident, db, observations.getOrDefault(db.getId(), List.of()),
                    NOW);
        }
    }

    private static ServiceVerification only(List<ServiceVerification> services) {
        check(services.size() == 1, "Expected exactly one service but found " + services.size());
        return services.get(0);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
