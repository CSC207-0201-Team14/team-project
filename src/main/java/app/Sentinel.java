package app;

import data_access.configuration.JsonConfigurationRepository;
import data_access.persistence.JsonIncidentRepository;
import infrastructure.scheduling.SingleThreadIncidentWorker;
import interface_adapter.configuration.ConfigurationController;
import interface_adapter.configuration.ConfigurationPresenter;
import interface_adapter.investigate_incident.ConfigurationRepositoryReader;
import interface_adapter.investigate_incident.IncidentFailureConnector;
import interface_adapter.investigate_incident.IncidentPresenter;
import interface_adapter.investigate_incident.IncidentViewModel;
import interface_adapter.investigate_incident.InvestigateIncidentController;
import interface_adapter.investigate_incident.ObservationLookupReader;
import entity.HealthCheckResult;
import entity.MonitoredSystem;
import use_case.configure_monitored_system.ConfigurationInteractor;
import use_case.configure_monitored_system.ConfigurationRepository;
import use_case.investigate_incident.DependencyFailureRule;
import use_case.investigate_incident.DependencyIncidentCorrelationPolicy;
import use_case.investigate_incident.DiagnosticRule;
import use_case.investigate_incident.IncidentInputBoundary;
import use_case.investigate_incident.IncidentInteractor;
import use_case.investigate_incident.IncidentOutputBoundary;
import use_case.investigate_incident.ObservedFailureRule;
import use_case.investigate_incident.PotentialImpactRule;
import use_case.investigate_incident.RecoveryRecommendationProvider;
import use_case.investigate_incident.RuleBasedIncidentAssessment;
import use_case.investigate_incident.SerializedIncidentInputBoundary;
import use_case.monitor_service_health.FailureEventListener;
import use_case.monitor_service_health.ObservationLookup;
import use_case.monitor_service_health.ObservationStore;
import use_case.shared.IncidentRepository;
import view.ConfigurationView;
import view.IncidentView;
import view.PlaceholderPanel;
import view.SentinelWindow;

import javax.swing.SwingUtilities;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class Sentinel implements AutoCloseable {
    private static final Duration EVIDENCE_WINDOW = Duration.ofSeconds(60);
    private final ConfigurationRepository configurations;
    private final IncidentRepository incidents;
    private final Clock clock;
    /** Owned here: shared by every use case that reads or writes incidents, and closed with the application. */
    private final SingleThreadIncidentWorker incidentWorker = new SingleThreadIncidentWorker();

    public Sentinel(Path dataDirectory, Clock clock) {
        this.configurations = new JsonConfigurationRepository(dataDirectory.resolve("systems"));
        this.incidents = new JsonIncidentRepository(dataDirectory.resolve("incidents"));
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public static void main(String[] args) {
        Path dataDirectory = Path.of(System.getProperty("user.home"), ".sentinel");
        SwingUtilities.invokeLater(() -> new Sentinel(dataDirectory, Clock.systemUTC()).open());
    }

    private void open() {
        ExecutorService configurationWorker = Executors.newSingleThreadExecutor();
        ConfigurationView configurationView = new ConfigurationView();

        IncidentViewModel incidentViewModel = new IncidentViewModel();
        IncidentView incidentView = new IncidentView(incidentViewModel);
        // UC2's observation store is not part of main yet, so nothing creates incidents in the desktop app and
        // evidence outside each failure event is reported as unavailable rather than invented.
        IncidentInvestigation investigation = wireInvestigateIncident(MonitoringNotConnected.INSTANCE,
                MonitoringNotConnected.INSTANCE, new IncidentPresenter(incidentViewModel));
        incidentView.connect(new InvestigateIncidentController(investigation.getInvestigation()));

        ConfigurationInteractor configuration = new ConfigurationInteractor(configurations,
                new ConfigurationPresenter(sharedSystemList(configurationView, incidentView)));

        SentinelWindow window = new SentinelWindow(configurationView,
                new PlaceholderPanel("Monitor service health"), incidentView,
                new PlaceholderPanel("Recover a service and verify resolution"),
                configurationView::confirmClose,
                () -> {
                    configurationWorker.shutdown();
                    close();
                });
        configurationView.connect(new ConfigurationController(configuration, configurationWorker));
        window.setVisible(true);
    }

    /**
     * UC1 stays the source of the system list: its presenter output also refreshes the incident panel's
     * system picker, so UC3 does not need its own query for configured systems.
     */
    private static ConfigurationPresenter.View sharedSystemList(ConfigurationView configurationView,
                                                                IncidentView incidentView) {
        return new ConfigurationPresenter.View() {
            @Override
            public void showSystems(List<MonitoredSystem> systems, List<String> skippedFiles) {
                configurationView.showSystems(systems, skippedFiles);
                Map<UUID, String> names = new LinkedHashMap<>();
                systems.forEach(system -> names.put(system.getId(), system.getName()));
                incidentView.showSystems(names);
            }

            @Override
            public void showSaved(MonitoredSystem system) {
                configurationView.showSaved(system);
                incidentView.showSavedSystem(system.getId(), system.getName());
            }

            @Override
            public void showError(String message) {
                configurationView.showError(message);
            }
        };
    }

    /**
     * Builds the investigation use case and the listener that UC2's wiring passes to its MonitoringInteractor.
     * UC2 keeps ownership of its interactor; its checker must timestamp observations with this application's
     * clock, which UC3 also uses to select recent evidence. Call deliver off the EDT: it waits for the result.
     */
    public IncidentInvestigation wireInvestigateIncident(ObservationStore history, ObservationLookup lookup,
            IncidentOutputBoundary incidentOutput) {
        List<DiagnosticRule> rules = List.of(new ObservedFailureRule(UUID::randomUUID),
                new DependencyFailureRule(UUID::randomUUID), new PotentialImpactRule(UUID::randomUUID));
        RuleBasedIncidentAssessment assessment = new RuleBasedIncidentAssessment(
                new DependencyIncidentCorrelationPolicy(), rules, clock, UUID::randomUUID, EVIDENCE_WINDOW);
        IncidentInteractor investigation = new IncidentInteractor(new ConfigurationRepositoryReader(configurations),
                new ObservationLookupReader(lookup), incidents, assessment, new RecoveryRecommendationProvider(),
                clock, incidentOutput);
        SerializedIncidentInputBoundary serialized = new SerializedIncidentInputBoundary(investigation, incidentWorker);
        IncidentFailureConnector listener = new IncidentFailureConnector(configurations, history, serialized,
                incidentOutput, clock, EVIDENCE_WINDOW);
        return new IncidentInvestigation(listener, serialized);
    }

    @Override
    public void close() {
        incidentWorker.close();
    }

    /**
     * Stands in for UC2's observation store until its outer layers are wired: no history, so the incident
     * panel lists stored incidents and marks evidence it cannot load as unavailable.
     */
    private enum MonitoringNotConnected implements ObservationStore, ObservationLookup {
        INSTANCE;

        @Override
        public void save(HealthCheckResult result) {
            throw new UnsupportedOperationException("Monitoring is not connected in this version");
        }

        @Override
        public List<HealthCheckResult> findByServiceId(UUID serviceId) {
            return List.of();
        }

        @Override
        public Map<UUID, HealthCheckResult> findByIds(Set<UUID> allowedServiceIds, Set<UUID> observationIds) {
            return Map.of();
        }
    }

    /** UC3's entry points: the failure listener for UC2's wiring and the input boundary for the incident UI. */
    public static final class IncidentInvestigation {
        private final FailureEventListener failureListener;
        private final IncidentInputBoundary investigation;

        private IncidentInvestigation(FailureEventListener failureListener, IncidentInputBoundary investigation) {
            this.failureListener = failureListener;
            this.investigation = investigation;
        }

        public FailureEventListener getFailureListener() { return failureListener; }
        public IncidentInputBoundary getInvestigation() { return investigation; }
    }
}
