package entity;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static support.Checks.check;
import static support.Checks.expect;
import static support.TestData.T0;
import static support.TestData.healthy;
import static support.TestData.observation;

/** Run directly with Java to check the persisted incident contract. */
public final class IncidentRestoreChecks {
    private static final Instant OPENED = T0;
    private static final UUID SERVICE = UUID.randomUUID();
    private static final HealthCheckResult FAILED = observation(SERVICE, OPENED.minusSeconds(2),
            HealthCheckResult.Outcome.FAIL);
    private static final FailureEvent FAILURE = new FailureEvent(UUID.randomUUID(), SERVICE,
            FAILED.getTimestamp(), List.of(FAILED));

    public static void main(String[] args) {
        restoresResolvedIncidentWithHistoryAndEvidence();
        restoresOptionalAndEqualTimestamps();
        rejectsOutOfOrderTimestamps();
        rejectsInvalidFailuresAndFindings();
        System.out.println("UC3 incident restore checks passed");
    }

    private static void restoresResolvedIncidentWithHistoryAndEvidence() {
        Instant investigating = OPENED.plusSeconds(1);
        Instant resolved = OPENED.plusSeconds(2);
        UUID dependent = UUID.randomUUID();
        DiagnosticFinding previous = finding(UUID.randomUUID());
        DiagnosticFinding current = finding(UUID.randomUUID());
        Incident original = Incident.open(UUID.randomUUID(), UUID.randomUUID(), OPENED, FAILURE)
                .startInvestigation(investigating)
                .updateAssessment(Set.of(dependent), List.of(previous))
                .updateAssessment(Set.of(dependent), List.of(current))
                .resolve(resolved, investigating, Map.of(SERVICE, healthy(SERVICE, resolved),
                        dependent, healthy(dependent, resolved)));

        Incident restored = Incident.restore(original.getId(), original.getSystemId(), original.getOpenedAt(),
                investigating, resolved, original.getFailures(), original.getPotentiallyAffectedServiceIds(),
                List.of(current.getId()), original.getFindingHistory(), original.getResolutionEvidence());
        check(restored.getId().equals(original.getId()) && restored.getSystemId().equals(original.getSystemId()),
                "Restoration must retain the incident and system IDs");
        check(restored.getOpenedAt().equals(OPENED) && restored.getInvestigatingAt().equals(Optional.of(investigating))
                && restored.getResolvedAt().equals(Optional.of(resolved))
                && restored.getStatus() == Incident.Status.RESOLVED,
                "Restoration must retain the lifecycle timestamps and status");
        check(restored.getFailures().equals(List.of(FAILURE))
                && restored.getPotentiallyAffectedServiceIds().equals(Set.of(dependent)), "Failures and impact must survive");
        check(restored.getActiveFindings().equals(List.of(current)),
                "The active finding must reference its entry in history");
        check(restored.getFindingHistory().equals(List.of(previous, current)), "History must retain both assessments");
        check(restored.getResolutionEvidence().equals(original.getResolutionEvidence()),
                "Resolution evidence must survive");
    }

    private static void restoresOptionalAndEqualTimestamps() {
        Incident open = restoreTimeline(null, null);
        check(open.getStatus() == Incident.Status.OPEN && open.getInvestigatingAt().isEmpty()
                && open.getResolvedAt().isEmpty(), "An open incident has no later timestamps");
        check(restoreTimeline(OPENED, null).getStatus() == Incident.Status.INVESTIGATING,
                "Investigation can start when the incident opens");
        Incident directlyResolved = restoreTimeline(null, OPENED);
        check(directlyResolved.getStatus() == Incident.Status.RESOLVED && directlyResolved.getInvestigatingAt().isEmpty(),
                "Resolution can skip investigation");
        check(restoreTimeline(OPENED, OPENED).getStatus() == Incident.Status.RESOLVED,
                "Equal lifecycle timestamps are valid");
    }

    private static void rejectsOutOfOrderTimestamps() {
        rejects(() -> restoreTimeline(OPENED.minusSeconds(1), null), "investigation before opening");
        rejects(() -> restoreTimeline(OPENED.plusSeconds(1), OPENED), "resolution before investigation");
        rejects(() -> restoreTimeline(null, OPENED.minusSeconds(1)), "direct resolution before opening");
    }

    private static void rejectsInvalidFailuresAndFindings() {
        rejects(() -> restore(OPENED, null, List.of(), List.of(), List.of(), List.of()), "empty failure list");
        FailureEvent late = new FailureEvent(UUID.randomUUID(), SERVICE, OPENED.plusSeconds(1),
                List.of(observation(SERVICE, OPENED.plusSeconds(1), HealthCheckResult.Outcome.FAIL)));
        rejects(() -> restore(OPENED, null, List.of(late), List.of(), List.of(), List.of()),
                "first failure after opening");
        rejects(() -> restore(OPENED, null, List.of(FAILURE, FAILURE), List.of(), List.of(), List.of()),
                "duplicate failure ID");
        DiagnosticFinding known = finding(UUID.randomUUID());
        rejects(() -> restore(OPENED, null, List.of(FAILURE), List.of(known.getId(), UUID.randomUUID()),
                List.of(known), List.of()), "active finding absent from history");
        rejects(() -> restore(OPENED, null, List.of(FAILURE), List.of(known.getId()),
                List.of(known, finding(known.getId())), List.of()), "duplicate history ID");
        rejects(() -> restore(OPENED, OPENED, List.of(FAILURE), List.of(), List.of(), List.of()),
                "resolved incident without evidence");
        rejects(() -> restore(OPENED, null, List.of(FAILURE), List.of(), List.of(),
                List.of(observation(SERVICE, OPENED, HealthCheckResult.Outcome.PASS))), "evidence without resolution");
    }

    private static Incident restore(Instant opened, Instant resolved, List<FailureEvent> failures,
                                    List<UUID> activeIds, List<DiagnosticFinding> history,
                                    List<HealthCheckResult> evidence) {
        return Incident.restore(UUID.randomUUID(), UUID.randomUUID(), opened, null, resolved, failures, Set.of(),
                activeIds, history, evidence);
    }

    private static Incident restoreTimeline(Instant investigating, Instant resolved) {
        List<HealthCheckResult> evidence = resolved == null ? List.of()
                : List.of(observation(SERVICE, resolved, HealthCheckResult.Outcome.PASS));
        return Incident.restore(UUID.randomUUID(), UUID.randomUUID(), OPENED, investigating, resolved,
                List.of(FAILURE), Set.of(), List.of(), List.of(), evidence);
    }

    private static DiagnosticFinding finding(UUID id) {
        return new DiagnosticFinding(id, "observed-failure", SERVICE, OPENED, Optional.empty(),
                List.of(FAILED.getId()), List.of(), "Health check failed", List.of("Inspect the service"));
    }

    private static void rejects(Runnable action, String invalidState) {
        expect(IllegalArgumentException.class, action, "restore accepted " + invalidState);
    }
}
