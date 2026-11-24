package app;

import data_access.configuration.JsonConfigurationRepository;
import data_access.persistence.JsonIncidentRepository;
import entity.Dependency;
import entity.DiagnosticFinding;
import entity.HealthCheckResult;
import entity.Incident;
import entity.MonitoredSystem;
import entity.MonitoringTarget;
import entity.Service;
import entity.ServiceHealth;
import support.TestData;
import use_case.investigate_incident.IncidentDetails;
import use_case.investigate_incident.IncidentOutputBoundary;
import use_case.investigate_incident.IncidentSummary;
import use_case.monitor_service_health.HealthChecker;
import use_case.monitor_service_health.MonitoringInteractor;
import use_case.monitor_service_health.MonitoringOutputBoundary;
import use_case.monitor_service_health.ObservationLookup;
import use_case.monitor_service_health.ObservationStore;
import use_case.monitor_service_health.ObservationStoreException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static entity.HealthCheckResult.Outcome.FAIL;
import static entity.HealthCheckResult.Outcome.PASS;
import static support.Checks.check;

/** Exercises the UC2 to UC3 delivery path and reopens its persisted incident after restart. */
public final class IncidentMonitoringChecks {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory(Files.createDirectories(Path.of("target")), "incident-monitoring-");
        try {
            retryAndReopen(root);
        } finally {
            List<Path> created;
            try (Stream<Path> paths = Files.walk(root)) {
                created = paths.sorted(Comparator.reverseOrder()).collect(Collectors.toList());
            }
            for (Path path : created) {
                Files.delete(path);
            }
        }
        System.out.println("UC2 to UC3 incident monitoring checks passed");
    }

    private static void retryAndReopen(Path root) throws Exception {
        UUID systemId = UUID.randomUUID();
        Service api = TestData.service(UUID.randomUUID(), systemId, "API", MonitoringTarget.docker("api"));
        Service db = TestData.service(UUID.randomUUID(), systemId, "Database", MonitoringTarget.docker("db"));
        Dependency edge = new Dependency(UUID.randomUUID(), api.getId(), db.getId());
        new JsonConfigurationRepository(root.resolve("systems")).save(
                new MonitoredSystem(systemId, "Orders", "", List.of(api, db), List.of(edge)));
        JsonIncidentRepository incidents = new JsonIncidentRepository(root.resolve("incidents"));
        Observations history = new Observations();
        HealthCheckResult dbFailure = TestData.observation(db.getId(), TestData.at(-2), FAIL);
        history.save(dbFailure);
        history.save(TestData.observation(api.getId(), TestData.at(-1), FAIL));
        Clock clock = Clock.fixed(TestData.T0, ZoneOffset.UTC);
        Incident stored;
        try (Sentinel app = new Sentinel(root, clock)) {
            Output output = new Output();
            Sentinel.IncidentInvestigation inputs = app.wireInvestigateIncident(history, history, output);
            MonitoringInteractor monitoring = monitoring(service -> TestData.observation(service.getId(),
                    clock.instant(), service.getId().equals(api.getId()) ? FAIL : PASS), history, output, inputs);
            history.unreadableService = db.getId();
            monitoring.checkOnce(api);
            check(incidents.findBySystemId(systemId).isEmpty() && output.processedIds.isEmpty(),
                    "An unavailable dependency history leaves the threshold crossing pending");

            history.unreadableService = null;
            monitoring.checkOnce(db);
            List<Incident> saved = incidents.findBySystemId(systemId);
            check(saved.size() == 1, "The next poll of a different service retries the API failure");
            stored = saved.get(0);
            check(output.processedIds.equals(List.of(stored.getId())) && stored.getFailures().size() == 1
                    && stored.getFailures().get(0).getServiceId().equals(api.getId()), "One API failure was persisted");
            DiagnosticFinding suspected = stored.getFindingHistory().stream()
                    .filter(finding -> finding.getSuspectedCauseServiceId().filter(db.getId()::equals).isPresent())
                    .findFirst().orElseThrow();
            check(suspected.getAffectedServiceId().equals(api.getId())
                    && suspected.getObservationIds().contains(dbFailure.getId())
                    && suspected.getDependencyEvidence().stream().anyMatch(item -> item.getId().equals(edge.getId())),
                    "The stored hypothesis cites the DB observation and configured directed edge");
        }

        Clock later = Clock.offset(clock, Duration.ofMinutes(2));
        try (Sentinel restarted = new Sentinel(root, later)) {
            Output output = new Output();
            Sentinel.IncidentInvestigation inputs = restarted.wireInvestigateIncident(history, history, output);
            monitoring(service -> TestData.observation(service.getId(), later.instant(), PASS), history, output, inputs)
                    .checkOnce(api);
            check(output.processedIds.equals(List.of(stored.getId())) && incidents.findBySystemId(systemId).size() == 1,
                    "Replaying UC2 history after restart acknowledges the original incident");
            inputs.getInvestigation().openIncident(stored.getId());
            IncidentDetails details = output.details.get(5, TimeUnit.SECONDS);
            check(details.getIncident().getStatus() == Incident.Status.INVESTIGATING
                    && details.getIncident().getFailures().size() == 1, "Reopening preserves the single failure");
            check(details.getObservations().get(dbFailure.getId()) == dbFailure
                    && details.getMissingObservationIds().isEmpty(), "Details load historical DB evidence by ID");
        }
    }

    /** Stands in for UC2's wiring, which owns its interactor and passes it UC3's failure listener. */
    private static MonitoringInteractor monitoring(HealthChecker checker, Observations history, Output output,
                                                   Sentinel.IncidentInvestigation inputs) {
        return new MonitoringInteractor(checker, history, output, inputs.getFailureListener());
    }

    private static final class Observations implements ObservationStore, ObservationLookup {
        private final List<HealthCheckResult> saved = new ArrayList<>();
        private UUID unreadableService;

        @Override
        public void save(HealthCheckResult result) {
            saved.add(result);
        }

        @Override
        public List<HealthCheckResult> findByServiceId(UUID serviceId) {
            if (serviceId.equals(unreadableService)) {
                throw new ObservationStoreException("History temporarily unavailable", null);
            }
            return saved.stream().filter(result -> result.getServiceId().equals(serviceId))
                    .collect(Collectors.toList());
        }

        @Override
        public Map<UUID, HealthCheckResult> findByIds(Set<UUID> serviceIds, Set<UUID> ids) {
            return saved.stream()
                    .filter(result -> serviceIds.contains(result.getServiceId()) && ids.contains(result.getId()))
                    .collect(Collectors.toMap(HealthCheckResult::getId, result -> result));
        }
    }

    private static final class Output implements MonitoringOutputBoundary, IncidentOutputBoundary {
        private final List<UUID> processedIds = new ArrayList<>();
        private final CompletableFuture<IncidentDetails> details = new CompletableFuture<>();

        @Override public void presentCheckResult(HealthCheckResult result) { }
        @Override public void presentHealth(ServiceHealth health) { }
        @Override public void presentIncidents(List<IncidentSummary> incidents) { }
        @Override public void presentProcessed(Incident incident) { processedIds.add(incident.getId()); }
        @Override public void presentDetails(IncidentDetails value) { details.complete(value); }
        @Override public void presentError(String message) { throw new AssertionError(message); }
    }
}
