package use_case.investigate_incident;

import data_access.InMemoryIncidentRepository;
import entity.*;
import java.time.Clock;
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
import use_case.shared.IncidentDataException;
import use_case.shared.IncidentRepository;

/** Dependency-free checks, following entity.DomainChecks. */
public final class IncidentContractChecks {
    private static final Instant TIME = Instant.parse("2026-01-01T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(TIME.plusSeconds(5), java.time.ZoneOffset.UTC);

    public static void main(String[] args) {
        verifiesProcessingAndInvestigationPath();
        reportsMissingDataAndAccessFailures();
        verifiesRepositoryScopeAndReplacement();
        System.out.println("UC3 contract checks passed");
    }

    private static void verifiesProcessingAndInvestigationPath() {
        Fixture fixture = new Fixture();
        List<HealthCheckResult> recent = new ArrayList<>(List.of(fixture.failed));
        FailureInput input = new FailureInput(fixture.system.getId(), fixture.failure, recent);
        recent.clear();
        check(input.getRecentObservations().size() == 1, "Input must copy the observation list");
        check(fixture.boundary.processFailure(input) == FailureProcessingResult.PROCESSED,
                "A saved assessment reports PROCESSED");
        check(fixture.output.processed == fixture.assessed, "Assessment output reaches presenter");
        check(fixture.repository.findById(fixture.assessed.getId()).orElseThrow() == fixture.assessed,
                "Assessment must be saved before presenting success");
        check(fixture.boundary.processFailure(input) == FailureProcessingResult.PROCESSED,
                "Redelivering the same event reports PROCESSED");
        check(fixture.seenUnresolved.size() == 1, "Next call receives the saved unresolved incident");
        check(fixture.repository.findUnresolvedBySystemId(fixture.system.getId()).size() == 1,
                "Saving the same incident ID replaces it");

        fixture.boundary.openIncident(fixture.assessed.getId());
        IncidentDetails details = fixture.output.details;
        check(details.getObservations().keySet().equals(Set.of(fixture.failed.getId(), fixture.passed.getId())),
                "Details combine retained failure evidence with historical observation lookup");
        check(details.getMissingObservationIds().isEmpty(), "All evidence is available");
        check(details.getIncident().getStatus() == Incident.Status.INVESTIGATING
                && details.getIncident().getInvestigatingAt().orElseThrow().equals(CLOCK.instant()),
                "Opening an open incident starts investigation at the current time");
        check(fixture.repository.findById(fixture.assessed.getId()).orElseThrow().getStatus()
                == Incident.Status.INVESTIGATING, "The investigating status is saved");
        check(details.getRecommendedActions().size() == 1
                && details.getRecommendedActions().get(0).getTargetServiceId().equals(fixture.serviceId)
                && details.getRecommendedActions().get(0).getActionType()
                == RecoveryAction.ActionType.RECOVER_DOCKER_CONTAINER,
                "Details include the container recommendation for the failing Docker service");
        check(details.getIncident().getActiveFindings().isEmpty()
                && details.getIncident().getFindingHistory().size() == 1, "Historical findings remain inspectable");

        Incident investigating = fixture.repository.findById(fixture.assessed.getId()).orElseThrow();
        fixture.retained.clear();
        fixture.boundary.openIncident(fixture.assessed.getId());
        check(fixture.output.details.getIncident() == investigating,
                "Reopening an investigating incident leaves it unchanged");
        check(fixture.output.details.getMissingObservationIds().equals(Set.of(fixture.passed.getId())),
                "Missing historical evidence is explicit");
        fixture.configuration = ConfigurationSnapshot.dependenciesUnavailable(fixture.system, "Graph unavailable");
        fixture.boundary.processFailure(input);
        check(fixture.seenConfiguration.getDependencyUnavailableReason().isPresent(),
                "Unavailable graph reaches the assessment distinctly from a known empty graph");
    }

    private static void reportsMissingDataAndAccessFailures() {
        Fixture fixture = new Fixture();
        fixture.boundary.openIncident(UUID.randomUUID());
        check(fixture.output.error.equals("Incident was not found"), "Missing incident is presented");
        fixture.configuration = null;
        check(fixture.boundary.processFailure(fixture.input()) == FailureProcessingResult.REJECTED,
                "A missing system is not retryable");
        check(fixture.output.error.equals("Monitored system was not found"), "Missing system is presented");
        fixture.configuration = ConfigurationSnapshot.available(fixture.system);
        HealthCheckResult foreign = result(UUID.randomUUID(), HealthCheckResult.Outcome.FAIL);
        check(fixture.boundary.processFailure(new FailureInput(fixture.system.getId(), fixture.failure,
                List.of(foreign))) == FailureProcessingResult.REJECTED, "Foreign services are not retryable");
        check(fixture.output.error.contains("outside"), "Foreign observations are rejected");
        check(fixture.output.processed == null, "Invalid input cannot report success");

        IncidentRepository failingRepository = new IncidentRepository() {
            public Optional<Incident> findById(UUID id) { throw new IncidentDataException("Read failed"); }
            public List<Incident> findUnresolvedBySystemId(UUID id) { return List.of(); }
            public void save(Incident incident) { throw new IncidentDataException("Save failed"); }
        };
        IncidentInputBoundary boundary = new IncidentInteractor(id -> Optional.of(fixture.configuration),
                ids -> Map.of(), failingRepository, (input, configuration, unresolved) -> fixture.assessed,
                new RecoveryRecommendationProvider(), CLOCK, fixture.output);
        check(boundary.processFailure(fixture.input()) == FailureProcessingResult.RETRYABLE,
                "A storage failure is retryable");
        check(fixture.output.error.equals("Save failed") && fixture.output.processed == null,
                "Save failure is reported without publishing success");

        InMemoryIncidentRepository untouched = new InMemoryIncidentRepository();
        IncidentInputBoundary invalid = new IncidentInteractor(id -> Optional.of(fixture.configuration),
                ids -> Map.of(), untouched, (input, configuration, unresolved) -> {
                    throw new IllegalArgumentException("failure is in the future");
                }, new RecoveryRecommendationProvider(), CLOCK, fixture.output);
        check(invalid.processFailure(fixture.input()) == FailureProcessingResult.REJECTED
                && fixture.output.error.contains("failure is in the future")
                && untouched.findUnresolvedBySystemId(fixture.system.getId()).isEmpty(),
                "Invalid assessment input is rejected, presented and not saved instead of escaping");
        boundary.openIncident(fixture.assessed.getId());
        check(fixture.output.error.equals("Read failed"), "Read failure is reported");

        IncidentRepository unsavable = new IncidentRepository() {
            public Optional<Incident> findById(UUID id) { return Optional.of(fixture.assessed); }
            public List<Incident> findUnresolvedBySystemId(UUID id) { return List.of(); }
            public void save(Incident incident) { throw new IncidentDataException("Save failed"); }
        };
        fixture.output.error = null;
        new IncidentInteractor(id -> Optional.of(fixture.configuration), ids -> Map.of(), unsavable,
                (input, configuration, unresolved) -> fixture.assessed, new RecoveryRecommendationProvider(),
                CLOCK, fixture.output).openIncident(fixture.assessed.getId());
        check("Save failed".equals(fixture.output.error) && fixture.output.details == null,
                "Failing to save the investigating status presents an error instead of details");
    }

    private static void verifiesRepositoryScopeAndReplacement() {
        Fixture fixture = new Fixture();
        fixture.repository.save(fixture.assessed);
        Incident investigating = fixture.assessed.startInvestigation(TIME.plusSeconds(1));
        fixture.repository.save(investigating);
        check(fixture.repository.findById(investigating.getId()).orElseThrow() == investigating,
                "Updates replace the immutable value");
        check(fixture.repository.findUnresolvedBySystemId(UUID.randomUUID()).isEmpty(), "System queries are scoped");
        HealthCheckResult recovered = new HealthCheckResult(UUID.randomUUID(), fixture.serviceId,
                TIME.plusSeconds(3), HealthCheckResult.Outcome.PASS, OptionalLong.empty(), "Recovered");
        Incident resolved = investigating.resolve(TIME.plusSeconds(3), TIME.plusSeconds(2),
                Map.of(fixture.serviceId, ServiceHealth.evaluated(HealthStatus.HEALTHY, recovered)));
        fixture.repository.save(resolved);
        check(fixture.repository.findUnresolvedBySystemId(fixture.system.getId()).isEmpty(),
                "Resolved incidents are excluded from active correlation");
        check(fixture.repository.findById(resolved.getId()).isPresent(), "Resolved history remains readable");
    }

    private static final class Fixture {
        private final UUID serviceId = UUID.randomUUID();
        private final MonitoredSystem system;
        private final HealthCheckResult failed = result(serviceId, HealthCheckResult.Outcome.FAIL);
        private final HealthCheckResult passed = result(serviceId, HealthCheckResult.Outcome.PASS);
        private final FailureEvent failure = new FailureEvent(UUID.randomUUID(), serviceId, TIME, List.of(failed));
        private final Incident assessed;
        private final InMemoryIncidentRepository repository = new InMemoryIncidentRepository();
        private final Map<UUID, HealthCheckResult> retained = new HashMap<>();
        private final CapturingOutput output = new CapturingOutput();
        private ConfigurationSnapshot configuration;
        private ConfigurationSnapshot seenConfiguration;
        private List<Incident> seenUnresolved;
        private final IncidentInputBoundary boundary;

        private Fixture() {
            UUID systemId = UUID.randomUUID();
            Service service = new Service(serviceId, systemId, "Database", MonitoringTarget.docker("db"),
                    new MonitoringPolicy(Duration.ofSeconds(10), Duration.ofSeconds(2), 2, 2));
            system = new MonitoredSystem(systemId, "Orders", "", List.of(service), List.of());
            configuration = ConfigurationSnapshot.available(system);
            DiagnosticFinding history = new DiagnosticFinding(UUID.randomUUID(), "test-fixture", serviceId,
                    TIME, Optional.empty(), List.of(failed.getId(), passed.getId()), List.of(),
                    "Fixture assessment with unknown cause", List.of("Inspect logs"));
            assessed = Incident.open(UUID.randomUUID(), systemId, TIME, failure)
                    .updateAssessment(Set.of(), List.of(history)).updateAssessment(Set.of(), List.of());
            retained.put(passed.getId(), passed);
            boundary = new IncidentInteractor(id -> Optional.ofNullable(configuration), ids -> {
                Map<UUID, HealthCheckResult> found = new HashMap<>();
                for (UUID id : ids) {
                    if (retained.containsKey(id)) {
                        found.put(id, retained.get(id));
                    }
                }
                return found;
            }, repository, (input, snapshot, unresolved) -> {
                seenConfiguration = snapshot;
                seenUnresolved = unresolved;
                return assessed; // Fixed test output; diagnosis/correlation arrive in later stages.
            }, new RecoveryRecommendationProvider(), CLOCK, output);
        }

        private FailureInput input() { return new FailureInput(system.getId(), failure, List.of(failed)); }
    }

    private static final class CapturingOutput implements IncidentOutputBoundary {
        private Incident processed;
        private IncidentDetails details;
        private String error;

        public void presentProcessed(Incident incident) { processed = incident; }
        public void presentDetails(IncidentDetails result) { details = result; }
        public void presentError(String message) { error = message; }
    }

    private static HealthCheckResult result(UUID serviceId, HealthCheckResult.Outcome outcome) {
        return new HealthCheckResult(UUID.randomUUID(), serviceId, TIME, outcome, OptionalLong.empty(), "");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
