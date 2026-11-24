package use_case.investigate_incident;

import data_access.InMemoryIncidentRepository;
import entity.*;
import use_case.shared.ConfigurationSnapshot;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import use_case.shared.IncidentDataException;
import use_case.shared.IncidentRepository;

import static support.Checks.check;
import static support.TestData.T0;
import static support.TestData.failure;
import static support.TestData.healthy;
import static support.TestData.observation;
import static support.TestData.service;

/** Dependency-free checks, following entity.DomainChecks. */
public final class IncidentContractChecks {
    private static final Clock CLOCK = Clock.fixed(T0.plusSeconds(5), ZoneOffset.UTC);

    public static void main(String[] args) {
        verifiesProcessingAndInvestigationPath();
        opensDetailsWithCurrentServiceNames();
        listsSystemHistoryWithoutStartingInvestigation();
        reportsMissingDataAndAccessFailures();
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

        fixture.boundary.openIncident(fixture.assessed.getId());
        IncidentDetails details = fixture.output.details;
        check(details.getObservations().keySet().equals(Set.of(fixture.failed.getId(), fixture.passed.getId())),
                "Details combine retained failure evidence with historical observation lookup");
        check(details.getMissingObservationIds().isEmpty(), "All evidence is available");
        check(fixture.seenServiceIds.equals(Set.of(fixture.serviceId)),
                "Historical lookup is scoped to the services the incident cites");
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
        fixture.boundary.processFailure(new FailureInput(fixture.system.getId(),
                failure(fixture.serviceId, T0.plusSeconds(4)), List.of()));
        check(fixture.seenConfiguration == fixture.configuration
                && fixture.seenUnresolved.equals(List.of(investigating)),
                "A new failure reaches assessment with the configuration and the open investigation");
    }

    private static void opensDetailsWithCurrentServiceNames() {
        Fixture fixture = new Fixture();
        fixture.repository.save(fixture.assessed);
        Service database = fixture.system.getServices().get(0);
        Service api = service(UUID.randomUUID(), fixture.system.getId(), "Orders API", MonitoringTarget.docker("api"));
        MonitoredSystem system = new MonitoredSystem(fixture.system.getId(), "Orders", "",
                List.of(database, api), List.of());
        fixture.configuration = ConfigurationSnapshot.available(system);
        fixture.boundary.openIncident(fixture.assessed.getId());
        IncidentDetails details = fixture.output.details;
        check(details.getServiceNames().equals(Map.of(database.getId(), "Database", api.getId(), "Orders API")),
                "Details include the configured service names even without configured dependencies");
        check(fixture.configurationReads == 1, "Opening an incident reads the configuration once");

        fixture.configuration = null;
        fixture.boundary.openIncident(fixture.assessed.getId());
        IncidentDetails historical = fixture.output.details;
        check(historical.getIncident().getId().equals(fixture.assessed.getId())
                && historical.getServiceNames().isEmpty() && historical.getRecommendedActions().isEmpty()
                && fixture.output.error == null, "Details remain available when the system configuration is missing");
    }

    private static void listsSystemHistoryWithoutStartingInvestigation() {
        Fixture fixture = new Fixture();
        UUID systemId = fixture.system.getId();
        Instant recent = T0.plusSeconds(2);
        Incident resolved = fixture.assessed.resolve(recent, T0, Map.of(fixture.serviceId, healthy(fixture.serviceId, recent)));
        Incident open = Incident.open(new UUID(0, 1), systemId, recent, fixture.failure);
        Incident investigating = Incident.open(new UUID(0, 2), systemId, recent, fixture.failure)
                .startInvestigation(recent);
        fixture.repository.save(resolved);
        fixture.repository.save(investigating);
        fixture.repository.save(open);
        fixture.repository.save(Incident.open(UUID.randomUUID(), UUID.randomUUID(), recent, fixture.failure));

        fixture.boundary.listIncidents(systemId);
        List<IncidentSummary> summaries = fixture.output.summaries;
        List<Incident> expected = List.of(open, investigating, resolved);
        check(summaries.size() == expected.size(), "Only the selected system's history is presented");
        for (int i = 0; i < expected.size(); i++) {
            Incident incident = expected.get(i);
            IncidentSummary summary = summaries.get(i);
            check(summary.getIncidentId().equals(incident.getId()) && summary.getStatus() == incident.getStatus()
                    && summary.getOpenedAt().equals(incident.getOpenedAt())
                    && summary.getFailingServiceIds().equals(incident.getFailingServiceIds()),
                    "Summaries retain incident identity, status, opening time and failing services in display order");
        }
        check(summaries.get(0).getServiceNames().equals(Map.of(fixture.serviceId, "Database")),
                "Summaries carry configured names so the list can show services by name");
        check(fixture.repository.findById(open.getId()).orElseThrow() == open && fixture.output.details == null,
                "Listing an open incident leaves it open");
        fixture.boundary.listIncidents(UUID.randomUUID());
        check(fixture.output.summaries.isEmpty() && fixture.output.error == null,
                "A system without incident history receives an empty list");
        fixture.configuration = null;
        fixture.boundary.listIncidents(systemId);
        check(fixture.output.summaries.size() == expected.size()
                && fixture.output.summaries.get(0).getServiceNames().isEmpty() && fixture.output.error == null,
                "History remains listable without names when the configuration is missing");
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
        UUID removed = UUID.randomUUID();
        FailureEvent removedFailure = new FailureEvent(UUID.randomUUID(), removed, T0,
                List.of(observation(removed, T0, HealthCheckResult.Outcome.FAIL)));
        check(fixture.boundary.processFailure(new FailureInput(fixture.system.getId(), removedFailure, List.of()))
                == FailureProcessingResult.REJECTED, "A failure of a removed service is not retryable");
        check(fixture.output.error.equals("The failed service is no longer part of the monitored system")
                && fixture.output.processed == null, "The rejected failure is presented and cannot report success");

        // A service removed after monitoring read its observations must not discard a configured service's failure.
        int errorsBefore = fixture.output.errors.size();
        HealthCheckResult fromRemoved = observation(removed, T0, HealthCheckResult.Outcome.FAIL);
        check(fixture.boundary.processFailure(new FailureInput(fixture.system.getId(), fixture.failure,
                List.of(fixture.failed, fromRemoved))) == FailureProcessingResult.PROCESSED,
                "A configured service's failure is still processed");
        check(fixture.seenInput.getRecentObservations().equals(List.of(fixture.failed))
                        && fixture.seenInput.getFailure() == fixture.failure
                        && fixture.output.errors.size() == errorsBefore,
                "Observations of the removed service are left out of the diagnosis without an error");
        fixture.output.processed = null; // The storage checks below assert that nothing is published.

        IncidentRepository failingRepository = new IncidentRepository() {
            public Optional<Incident> findById(UUID id) { throw new IncidentDataException("Read failed"); }
            public List<Incident> findBySystemId(UUID id) { throw new IncidentDataException("List failed"); }
            public void save(Incident incident) { throw new IncidentDataException("Save failed"); }
        };
        IncidentInputBoundary boundary = new IncidentInteractor(id -> Optional.of(fixture.configuration),
                (services, ids) -> Map.of(), failingRepository, (input, configuration, unresolved) -> fixture.assessed,
                new RecoveryRecommendationProvider(), CLOCK, fixture.output);
        check(boundary.processFailure(fixture.input()) == FailureProcessingResult.RETRYABLE,
                "A storage failure is retryable");
        check(fixture.output.error.equals("List failed") && fixture.output.processed == null,
                "A history read failure is reported without publishing success");

        InMemoryIncidentRepository untouched = new InMemoryIncidentRepository();
        IncidentInputBoundary invalid = new IncidentInteractor(id -> Optional.of(fixture.configuration),
                (services, ids) -> Map.of(), untouched, (input, configuration, unresolved) -> {
                    throw new IllegalArgumentException("failure is in the future");
                }, new RecoveryRecommendationProvider(), CLOCK, fixture.output);
        check(invalid.processFailure(fixture.input()) == FailureProcessingResult.REJECTED
                && fixture.output.error.contains("failure is in the future")
                && untouched.findUnresolvedBySystemId(fixture.system.getId()).isEmpty(),
                "Invalid assessment input is rejected, presented and not saved instead of escaping");
        boundary.openIncident(fixture.assessed.getId());
        check(fixture.output.error.equals("Read failed"), "Read failure is reported");
        boundary.listIncidents(fixture.system.getId());
        check("List failed".equals(fixture.output.error) && fixture.output.summaries == null,
                "A history read failure presents the storage error without publishing a list");

        IncidentRepository unsavable = new IncidentRepository() {
            public Optional<Incident> findById(UUID id) { return Optional.of(fixture.assessed); }
            public List<Incident> findBySystemId(UUID id) { return List.of(fixture.assessed); }
            public void save(Incident incident) { throw new IncidentDataException("Save failed"); }
        };
        fixture.output.error = null;
        IncidentInputBoundary failingSave = new IncidentInteractor(id -> Optional.of(fixture.configuration),
                (services, ids) -> Map.of(), unsavable,
                (input, configuration, unresolved) -> fixture.assessed, new RecoveryRecommendationProvider(),
                CLOCK, fixture.output);
        FailureInput newFailure = new FailureInput(fixture.system.getId(), failure(fixture.serviceId, T0), List.of());
        check(failingSave.processFailure(newFailure) == FailureProcessingResult.RETRYABLE
                && "Save failed".equals(fixture.output.error) && fixture.output.processed == null,
                "A failed save is retryable and does not publish success");
        fixture.output.error = null;
        check(failingSave.processFailure(fixture.input()) == FailureProcessingResult.PROCESSED
                && fixture.output.processed == fixture.assessed && fixture.output.error == null,
                "An already recorded delivery can be acknowledged without another save");
        failingSave.openIncident(fixture.assessed.getId());
        check("Save failed".equals(fixture.output.error) && fixture.output.details == null,
                "Failing to save the investigating status presents an error instead of details");
    }

    private static final class Fixture {
        private final UUID serviceId = UUID.randomUUID();
        private final MonitoredSystem system;
        private final HealthCheckResult failed = observation(serviceId, T0, HealthCheckResult.Outcome.FAIL);
        private final HealthCheckResult passed = observation(serviceId, T0, HealthCheckResult.Outcome.PASS);
        private final FailureEvent failure = new FailureEvent(UUID.randomUUID(), serviceId, T0, List.of(failed));
        private final Incident assessed;
        private final InMemoryIncidentRepository repository = new InMemoryIncidentRepository();
        private final Map<UUID, HealthCheckResult> retained = new HashMap<>();
        private final RecordingOutput output = new RecordingOutput();
        private ConfigurationSnapshot configuration;
        private int configurationReads;
        private ConfigurationSnapshot seenConfiguration;
        private List<Incident> seenUnresolved;
        private FailureInput seenInput;
        private Set<UUID> seenServiceIds;
        private final IncidentInputBoundary boundary;

        private Fixture() {
            UUID systemId = UUID.randomUUID();
            system = new MonitoredSystem(systemId, "Orders", "",
                    List.of(service(serviceId, systemId, "Database", MonitoringTarget.docker("db"))), List.of());
            configuration = ConfigurationSnapshot.available(system);
            DiagnosticFinding history = new DiagnosticFinding(UUID.randomUUID(), "test-fixture", serviceId,
                    T0, Optional.empty(), List.of(failed.getId(), passed.getId()), List.of(),
                    "Fixture assessment with unknown cause", List.of("Inspect logs"));
            assessed = Incident.open(UUID.randomUUID(), systemId, T0, failure)
                    .updateAssessment(Set.of(), List.of(history)).updateAssessment(Set.of(), List.of());
            retained.put(passed.getId(), passed);
            boundary = new IncidentInteractor(id -> {
                configurationReads++;
                return Optional.ofNullable(configuration);
            }, (services, ids) -> {
                seenServiceIds = services;
                Map<UUID, HealthCheckResult> found = new HashMap<>();
                for (UUID id : ids) {
                    if (retained.containsKey(id) && services.contains(retained.get(id).getServiceId())) {
                        found.put(id, retained.get(id));
                    }
                }
                return found;
            }, repository, (input, snapshot, unresolved) -> {
                seenInput = input;
                seenConfiguration = snapshot;
                seenUnresolved = unresolved;
                return assessed; // Fixed test output; diagnosis/correlation arrive in later stages.
            }, new RecoveryRecommendationProvider(), CLOCK, output);
        }

        private FailureInput input() { return new FailureInput(system.getId(), failure, List.of(failed)); }
    }
}
