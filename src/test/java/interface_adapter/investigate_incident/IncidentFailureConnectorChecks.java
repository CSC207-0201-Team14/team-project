package interface_adapter.investigate_incident;

import entity.FailureEvent;
import entity.HealthCheckResult;
import entity.Incident;
import entity.MonitoredSystem;
import entity.MonitoringTarget;
import entity.Service;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import support.TestData;
import use_case.configure_monitored_system.ConfigurationLoadResult;
import use_case.configure_monitored_system.ConfigurationRepository;
import use_case.configure_monitored_system.ConfigurationStoreException;
import use_case.investigate_incident.FailureInput;
import use_case.investigate_incident.FailureProcessingResult;
import use_case.investigate_incident.IncidentDetails;
import use_case.investigate_incident.IncidentInputBoundary;
import use_case.investigate_incident.IncidentOutputBoundary;
import use_case.investigate_incident.IncidentSummary;
import use_case.monitor_service_health.ObservationStore;
import use_case.monitor_service_health.ObservationStoreException;

import static support.Checks.check;
import static support.Checks.expect;
import static support.TestData.at;
import static support.TestData.observation;

public final class IncidentFailureConnectorChecks {
    private final UUID systemId = UUID.randomUUID();
    private final Service api = service(systemId, "api");
    private final Service db = service(systemId, "db");
    private final MonitoredSystem system = new MonitoredSystem(systemId, "Shop", "", List.of(api, db), List.of());
    private final FailureEvent event = TestData.failure(api.getId(), at(3));
    private final Configurations configurations = new Configurations();
    private final Histories histories = new Histories();
    private final Incidents incidents = new Incidents();
    private final Errors errors = new Errors();
    private final Clock clock = Clock.fixed(at(20), ZoneOffset.UTC);
    private final IncidentFailureConnector connector = new IncidentFailureConnector(configurations, histories,
            incidents, errors, clock, Duration.ofSeconds(15));

    public static void main(String[] args) {
        new IncidentFailureConnectorChecks().checksInputAndAcknowledgement();
        new IncidentFailureConnectorChecks().checksReadFailuresAndRouting();
        new IncidentFailureConnectorChecks().rejectsMissingCollaborators();
        System.out.println("IncidentFailureConnectorChecks passed");
    }

    private void checksInputAndAcknowledgement() {
        UUID otherId = UUID.randomUUID();
        Service other = service(otherId, "Other API");
        configurations.loaded = new ConfigurationLoadResult(List.of(
                new MonitoredSystem(otherId, "Other", "", List.of(other), List.of()), system), List.of("bad.json"));
        HealthCheckResult oldest = observation(api.getId(), at(5), HealthCheckResult.Outcome.PASS);
        HealthCheckResult latest = observation(db.getId(), at(20), HealthCheckResult.Outcome.MONITORING_ERROR);
        HealthCheckResult failure = observation(db.getId(), at(10), HealthCheckResult.Outcome.FAIL);
        histories.results.put(api.getId(), List.of(event.getEvidence().get(0), oldest));
        histories.results.put(db.getId(), List.of(failure, latest,
                observation(db.getId(), at(21), HealthCheckResult.Outcome.FAIL)));

        check(connector.deliver(event), "Processed events are acknowledged");
        check(histories.reads.equals(List.of(api.getId(), db.getId())), "Only the owner's services are read");
        check(incidents.inputs.size() == 1, "A readable owner is used despite an unrelated skipped file");
        FailureInput input = incidents.inputs.get(0);
        check(input.getSystemId().equals(systemId) && input.getFailure() == event,
                "The owner and original threshold evidence must survive delivery");
        check(input.getRecentObservations().equals(List.of(oldest, failure, latest)),
                "All outcomes inside the inclusive clock-based window should be delivered");
        incidents.result = FailureProcessingResult.RETRYABLE;
        check(!connector.deliver(event), "Retryable events remain pending");
        incidents.result = FailureProcessingResult.REJECTED;
        check(connector.deliver(event), "Rejected events are acknowledged");
    }

    private void checksReadFailuresAndRouting() {
        configurations.fail = true;
        check(!connector.deliver(event), "A listing failure requires redelivery");
        configurations.fail = false;
        configurations.loaded = new ConfigurationLoadResult(List.of(), List.of("bad.json"));
        check(!connector.deliver(event), "An unreadable system could still own the service");
        configurations.loaded = new ConfigurationLoadResult(List.of(), List.of());
        check(connector.deliver(event), "A complete listing confirms the service is absent");
        UUID otherId = UUID.randomUUID();
        Service duplicate = TestData.service(api.getId(), otherId, "Duplicate", MonitoringTarget.docker("dup"));
        configurations.loaded = new ConfigurationLoadResult(List.of(system,
                new MonitoredSystem(otherId, "Other", "", List.of(duplicate), List.of())), List.of());
        check(connector.deliver(event), "A configuration conflict is acknowledged because redelivery cannot fix it");
        check(histories.reads.isEmpty() && incidents.inputs.isEmpty(),
                "An ambiguous event must not be assigned to either system");
        check(errors.messages.size() == 1 && errors.messages.get(0).contains("Other, Shop"),
                "The conflict is reported with the systems that share the service");

        configurations.loaded = new ConfigurationLoadResult(List.of(system), List.of());
        histories.unreadable = db.getId();
        check(!connector.deliver(event), "A failure reading any service history requires redelivery");
        check(incidents.inputs.isEmpty(), "UC3 should never receive a partial input");
        histories.unreadable = null;
        check(connector.deliver(event) && incidents.inputs.size() == 1,
                "Restoring access permits the same threshold crossing to be processed");
    }

    private void rejectsMissingCollaborators() {
        Duration window = Duration.ofSeconds(15);
        expect(NullPointerException.class, () -> new IncidentFailureConnector(null, histories, incidents, errors,
                clock, window), "missing configurations");
        expect(NullPointerException.class, () -> new IncidentFailureConnector(configurations, histories, incidents,
                null, clock, window), "missing output");
        expect(IllegalArgumentException.class, () -> new IncidentFailureConnector(configurations, histories,
                incidents, errors, clock, Duration.ZERO), "an empty evidence window");
    }

    private static Service service(UUID owner, String name) {
        return TestData.service(UUID.randomUUID(), owner, name, MonitoringTarget.docker(name));
    }

    private static final class Configurations implements ConfigurationRepository {
        private ConfigurationLoadResult loaded;
        private boolean fail;

        @Override
        public ConfigurationLoadResult findAll() {
            if (fail) {
                throw new ConfigurationStoreException("Cannot list systems", null);
            }
            return loaded;
        }

        @Override public Optional<MonitoredSystem> findById(UUID id) { throw new AssertionError(); }
        @Override public void save(MonitoredSystem system) { throw new AssertionError(); }
    }

    private static final class Histories implements ObservationStore {
        private final Map<UUID, List<HealthCheckResult>> results = new HashMap<>();
        private final List<UUID> reads = new ArrayList<>();
        private UUID unreadable;

        @Override
        public List<HealthCheckResult> findByServiceId(UUID id) {
            reads.add(id);
            if (id.equals(unreadable)) {
                throw new ObservationStoreException("Cannot read history", null);
            }
            return results.getOrDefault(id, List.of());
        }

        @Override public void save(HealthCheckResult result) { throw new AssertionError(); }
    }

    private static final class Incidents implements IncidentInputBoundary {
        private final List<FailureInput> inputs = new ArrayList<>();
        private FailureProcessingResult result = FailureProcessingResult.PROCESSED;

        @Override
        public FailureProcessingResult processFailure(FailureInput input) {
            inputs.add(input);
            return result;
        }

        @Override public void listIncidents(UUID systemId) { throw new AssertionError(); }
        @Override public void openIncident(UUID incidentId) { throw new AssertionError(); }
    }

    private static final class Errors implements IncidentOutputBoundary {
        private final List<String> messages = new ArrayList<>();

        @Override public void presentError(String message) { messages.add(message); }
        @Override public void presentProcessed(Incident incident) { throw new AssertionError(); }
        @Override public void presentIncidents(List<IncidentSummary> incidents) { throw new AssertionError(); }
        @Override public void presentDetails(IncidentDetails details) { throw new AssertionError(); }
    }
}
