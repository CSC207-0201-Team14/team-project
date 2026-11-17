package use_case.investigate_incident;

import data_access.InMemoryIncidentRepository;
import entity.Dependency;
import entity.DiagnosticFinding;
import entity.FailureEvent;
import entity.HealthCheckResult;
import entity.Incident;
import entity.MonitoredSystem;
import entity.MonitoringPolicy;
import entity.MonitoringTarget;
import entity.Service;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;

/** Run explicitly after mvn test-compile, following the existing test convention. */
public final class IncidentDiagnosisChecks {
    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(START.plusSeconds(100), ZoneOffset.UTC);
    private static final Duration WINDOW = Duration.ofSeconds(60);

    public static void main(String[] args) {
        explainsDependencyAndPotentialImpact();
        handlesUnknownUnavailableAndStaleEvidence();
        traversesCyclesAndRetainsAlternatives();
        correlatesConservatively();
        verifiesInteractorWithRealStrategies();
        System.out.println("UC3 diagnosis checks passed");
    }

    private static void explainsDependencyAndPotentialImpact() {
        Fixture fixture = new Fixture();
        FailureEvent db = failure(fixture.db, 70);
        FailureEvent api = failure(fixture.api, 80);
        DiagnosticSnapshot snapshot = fixture.snapshot(List.of(db, api), List.of());
        List<DiagnosticFinding> findings = new DependencyFailureRule(UUID::randomUUID).evaluate(snapshot);
        check(findings.size() == 1, "Dependent failure has one supported cause");
        DiagnosticFinding finding = findings.get(0);
        check(finding.getAffectedServiceId().equals(fixture.api)
                && finding.getSuspectedCauseServiceId().orElseThrow().equals(fixture.db), "Direction is dependent to required");
        check(Set.copyOf(finding.getObservationIds()).equals(Set.of(
                db.getEvidence().get(0).getId(), api.getEvidence().get(0).getId())), "Both failed checks are retained");
        check(finding.getDependencyEvidence().equals(List.of(fixture.apiToDb)), "Dependency edge is retained");
        check(!finding.getNextSteps().isEmpty(), "Diagnosis includes troubleshooting guidance");
        List<DiagnosticFinding> impacts = new PotentialImpactRule(UUID::randomUUID).evaluate(snapshot);
        check(impacts.size() == 2 && impacts.stream().allMatch(item -> item.getAffectedServiceId().equals(fixture.web)),
                "Web is potentially affected through API and DB, without becoming an observed failure");
        List<DiagnosticFinding> reordered = new DependencyFailureRule(UUID::randomUUID)
                .evaluate(fixture.snapshot(List.of(api, db), List.of()));
        check(reordered.size() == findings.size()
                && reordered.get(0).getObservationIds().equals(finding.getObservationIds()),
                "Input order preserves the diagnosis and evidence order");
    }

    private static void handlesUnknownUnavailableAndStaleEvidence() {
        Fixture fixture = new Fixture();
        FailureEvent api = failure(fixture.api, 80);
        FailureEvent db = failure(fixture.db, 70);
        DiagnosticRule dependencyRule = new DependencyFailureRule(UUID::randomUUID);
        DiagnosticSnapshot alone = fixture.snapshot(List.of(api), List.of());
        check(dependencyRule.evaluate(alone).isEmpty(), "Missing dependency observations cannot establish a cause");
        DiagnosticFinding unknown = new ObservedFailureRule(UUID::randomUUID).evaluate(alone).get(0);
        check(unknown.getSuspectedCauseServiceId().isEmpty() && !unknown.getObservationIds().isEmpty(),
                "Unknown cause retains the observed failure evidence");
        for (HealthCheckResult.Outcome outcome : List.of(HealthCheckResult.Outcome.PASS,
                HealthCheckResult.Outcome.MONITORING_ERROR)) {
            DiagnosticSnapshot recovered = fixture.snapshot(List.of(api, db), List.of(result(fixture.db, 90, outcome)));
            check(dependencyRule.evaluate(recovered).isEmpty(), "Latest pass/error removes the dependency hypothesis");
        }
        check(dependencyRule.evaluate(fixture.snapshot(List.of(api, failure(fixture.db, 10)), List.of())).isEmpty(),
                "Stale dependency evidence cannot establish a current cause");
        check(dependencyRule.evaluate(fixture.snapshot(List.of(api, failure(fixture.db, 90)), List.of())).isEmpty(),
                "A later dependency failure cannot explain an earlier dependent check");
        DiagnosticSnapshot conflict = fixture.snapshot(List.of(api, db),
                List.of(result(fixture.db, 70, HealthCheckResult.Outcome.PASS)));
        check(dependencyRule.evaluate(conflict).isEmpty(), "Conflicting same-time outcomes leave the cause uncertain");
        MonitoredSystem roster = new MonitoredSystem(fixture.systemId, "Orders", "", fixture.services, List.of());
        DiagnosticSnapshot unavailable = new DiagnosticSnapshot(
                ConfigurationSnapshot.dependenciesUnavailable(roster, "Configuration read failed"),
                List.of(api, db), List.of(), CLOCK.instant(), WINDOW);
        check(dependencyRule.evaluate(unavailable).isEmpty()
                && new PotentialImpactRule(UUID::randomUUID).evaluate(unavailable).isEmpty(),
                "Unavailable dependencies disable graph-based conclusions");
        check(new ObservedFailureRule(UUID::randomUUID).evaluate(unavailable).get(0).getExplanation()
                .contains("Configuration read failed"), "Unavailable analysis is explained with observed facts retained");
    }

    private static void traversesCyclesAndRetainsAlternatives() {
        Fixture fixture = new Fixture();
        List<Dependency> edges = new ArrayList<>(fixture.edges);
        edges.add(new Dependency(UUID.randomUUID(), fixture.db, fixture.web));
        ConfigurationSnapshot cyclic = ConfigurationSnapshot.available(new MonitoredSystem(fixture.systemId,
                "Cycle", "", fixture.services, edges));
        DependencyGraph graph = new DependencyGraph(edges);
        check(graph.path(fixture.web, fixture.db).orElseThrow().size() == 2, "Transitive path retains both edges");
        check(graph.path(fixture.api, fixture.other).isEmpty(), "Cycle traversal terminates for unreachable targets");
        DiagnosticSnapshot snapshot = new DiagnosticSnapshot(cyclic, List.of(failure(fixture.web, 90),
                failure(fixture.api, 80), failure(fixture.db, 70)), List.of(), CLOCK.instant(), WINDOW);
        List<DiagnosticFinding> findings = new DependencyFailureRule(UUID::randomUUID).evaluate(snapshot);
        check(findings.stream().filter(item -> item.getAffectedServiceId().equals(fixture.web)).count() == 2,
                "Multiple supported hypotheses remain visible instead of selecting a proven root cause");
    }

    private static void correlatesConservatively() {
        Fixture fixture = new Fixture();
        IncidentAssessment assessment = fixture.assessment();
        FailureEvent dbFailure = failure(fixture.db, 70);
        Incident db = assessment.assess(fixture.input(dbFailure), fixture.configuration, List.of());
        FailureEvent apiFailure = failure(fixture.api, 80);
        Incident grouped = assessment.assess(fixture.input(apiFailure), fixture.configuration, List.of(db));
        check(grouped.getId().equals(db.getId()) && grouped.getFailures().size() == 2,
                "Directed dependency plus current failed evidence supports grouping");
        check(grouped.getFindingHistory().size() > db.getFindingHistory().size()
                && grouped.getFindingHistory().containsAll(db.getFindingHistory()), "Reassessment preserves history");
        check(assessment.assess(fixture.input(apiFailure), fixture.configuration, List.of(grouped)) == grouped,
                "Retry of an event is idempotent, including finding history");
        Incident unrelated = assessment.assess(fixture.input(failure(fixture.other, 80)), fixture.configuration, List.of(db));
        check(!unrelated.getId().equals(db.getId()), "Simultaneous unrelated failures stay separate");
        Incident anotherDb = Incident.open(UUID.randomUUID(), fixture.systemId, CLOCK.instant(), failure(fixture.db, 75));
        Incident ambiguous = assessment.assess(fixture.input(apiFailure), fixture.configuration, List.of(db, anotherDb));
        check(!ambiguous.getId().equals(db.getId()) && !ambiguous.getId().equals(anotherDb.getId()),
                "Ambiguous matches do not arbitrarily choose an incident");
        FailureInput afterRecovery = new FailureInput(fixture.systemId, apiFailure,
                List.of(result(fixture.db, 90, HealthCheckResult.Outcome.PASS)));
        check(!assessment.assess(afterRecovery, fixture.configuration, List.of(db)).getId().equals(db.getId()),
                "Partially recovered dependencies cannot support grouping");
        FailureInput flapping = new FailureInput(fixture.systemId, failure(fixture.db, 90),
                List.of(result(fixture.db, 80, HealthCheckResult.Outcome.PASS)));
        check(!assessment.assess(flapping, fixture.configuration, List.of(db)).getId().equals(db.getId()),
                "A recovery between same-service failures starts a separate incident");
        Incident stale = Incident.open(UUID.randomUUID(), fixture.systemId, START.plusSeconds(10), failure(fixture.db, 10));
        check(!assessment.assess(fixture.input(apiFailure), fixture.configuration, List.of(stale)).getId().equals(stale.getId()),
                "Old unresolved evidence cannot support grouping");
        Incident foreign = Incident.open(UUID.randomUUID(), UUID.randomUUID(), CLOCK.instant(), dbFailure);
        check(!assessment.assess(fixture.input(apiFailure), fixture.configuration, List.of(foreign)).getId().equals(foreign.getId()),
                "Correlation is scoped to the configured system");
        MonitoredSystem roster = new MonitoredSystem(fixture.systemId, "Orders", "", fixture.services, List.of());
        ConfigurationSnapshot unavailable = ConfigurationSnapshot.dependenciesUnavailable(roster, "Graph unavailable");
        check(!assessment.assess(fixture.input(apiFailure), unavailable, List.of(db)).getId().equals(db.getId()),
                "Missing graph cannot support cross-service grouping");
        FailureInput errorThenFailure = new FailureInput(fixture.systemId, failure(fixture.db, 90),
                List.of(result(fixture.db, 80, HealthCheckResult.Outcome.MONITORING_ERROR)));
        check(!assessment.assess(errorThenFailure, fixture.configuration, List.of(db)).getId().equals(db.getId()),
                "Monitoring error interrupts evidence of continuous failure");
    }

    private static void verifiesInteractorWithRealStrategies() {
        Fixture fixture = new Fixture();
        InMemoryIncidentRepository repository = new InMemoryIncidentRepository();
        CapturingOutput output = new CapturingOutput();
        IncidentInputBoundary boundary = new IncidentInteractor(id -> java.util.Optional.of(fixture.configuration),
                ids -> java.util.Map.of(), repository, fixture.assessment(),
                new RecoveryRecommendationProvider(), output);
        FailureInput db = fixture.input(failure(fixture.db, 70));
        boundary.processFailure(db);
        UUID incidentId = output.incident.getId();
        check(output.incident.getPotentiallyAffectedServiceIds().equals(Set.of(fixture.api, fixture.web)),
                "Potential impact is derived separately from observed failures");
        boundary.processFailure(fixture.input(failure(fixture.api, 80)));
        boundary.processFailure(db);
        boundary.openIncident(incidentId);
        check(output.details.getIncident().getFailingServiceIds().equals(Set.of(fixture.db, fixture.api)),
                "Real strategies work through the normal input boundary and repository");
        check(output.details.getMissingObservationIds().isEmpty(), "Failure evidence supports diagnosis detail lookup");
        check(output.details.getIncident().getStatus() == Incident.Status.OPEN, "Diagnosis and reading do not resolve incidents");
        check(repository.findUnresolvedBySystemId(fixture.systemId).size() == 1, "Repeated delivery does not duplicate incidents");
    }

    private static final class Fixture {
        private final UUID systemId = UUID.randomUUID();
        private final UUID api = UUID.randomUUID();
        private final UUID db = UUID.randomUUID();
        private final UUID web = UUID.randomUUID();
        private final UUID other = UUID.randomUUID();
        private final Dependency apiToDb = new Dependency(UUID.randomUUID(), api, db);
        private final List<Dependency> edges = List.of(apiToDb, new Dependency(UUID.randomUUID(), web, api));
        private final List<Service> services = List.of(service(api, "API"), service(db, "DB"),
                service(web, "Web"), service(other, "Other"));
        private final ConfigurationSnapshot configuration = ConfigurationSnapshot.available(
                new MonitoredSystem(systemId, "Orders", "", services, edges));

        private Service service(UUID id, String name) {
            return new Service(id, systemId, name, MonitoringTarget.docker(name),
                    new MonitoringPolicy(Duration.ofSeconds(10), Duration.ofSeconds(2), 2, 2));
        }

        private FailureInput input(FailureEvent failure) { return new FailureInput(systemId, failure, List.of()); }

        private DiagnosticSnapshot snapshot(List<FailureEvent> failures, List<HealthCheckResult> observations) {
            return new DiagnosticSnapshot(configuration, failures, observations, CLOCK.instant(), WINDOW);
        }

        private IncidentAssessment assessment() {
            return new RuleBasedIncidentAssessment(new DependencyIncidentCorrelationPolicy(), List.of(
                    new ObservedFailureRule(UUID::randomUUID), new DependencyFailureRule(UUID::randomUUID),
                    new PotentialImpactRule(UUID::randomUUID)), CLOCK, UUID::randomUUID, WINDOW);
        }
    }

    private static final class CapturingOutput implements IncidentOutputBoundary {
        private Incident incident;
        private IncidentDetails details;
        public void presentProcessed(Incident result) { incident = result; }
        public void presentDetails(IncidentDetails result) { details = result; }
        public void presentError(String message) { throw new AssertionError(message); }
    }

    private static FailureEvent failure(UUID service, long second) {
        return new FailureEvent(UUID.randomUUID(), service, START.plusSeconds(second),
                List.of(result(service, second, HealthCheckResult.Outcome.FAIL)));
    }

    private static HealthCheckResult result(UUID service, long second, HealthCheckResult.Outcome outcome) {
        return new HealthCheckResult(UUID.randomUUID(), service, START.plusSeconds(second), outcome,
                OptionalLong.empty(), "Test observation");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
