package app;

import data_access.configuration.JsonConfigurationRepository;
import data_access.http.HttpHealthChecker;
import data_access.docker.DockerEngineInspector;
import data_access.docker.DockerHealthChecker;
import data_access.docker.DockerRecoveryGateway;
import data_access.monitoring.TargetHealthChecker;
import data_access.persistence.JsonObservationStore;
import data_access.persistence.JsonIncidentRepository;
import data_access.persistence.JsonRecoveryRecordRepository;
import infrastructure.scheduling.ExecutorMonitoringScheduler;
import infrastructure.scheduling.SingleThreadIncidentWorker;
import interface_adapter.configuration.ConfigurationController;
import interface_adapter.configuration.ConfigurationPresenter;
import interface_adapter.investigate_incident.ConfigurationRepositoryReader;
import interface_adapter.investigate_incident.IncidentFailureConnector;
import interface_adapter.investigate_incident.IncidentPresenter;
import interface_adapter.investigate_incident.IncidentViewModel;
import interface_adapter.investigate_incident.InvestigateIncidentController;
import interface_adapter.investigate_incident.ObservationLookupReader;
import interface_adapter.monitor_service_health.MonitoringController;
import interface_adapter.monitor_service_health.MonitoringPresenter;
import interface_adapter.monitor_service_health.MonitoringViewModel;
import interface_adapter.recover_service.RecoveryController;
import interface_adapter.recover_service.RecoveryPresenter;
import entity.MonitoredSystem;
import entity.Service;
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
import use_case.monitor_service_health.MonitoringInteractor;
import use_case.monitor_service_health.MonitoringInputBoundary;
import use_case.monitor_service_health.MonitoringOutputBoundary;
import use_case.monitor_service_health.MonitoringSessionInputBoundary;
import use_case.monitor_service_health.MonitoringSessionInteractor;
import use_case.monitor_service_health.ObservationLookup;
import use_case.monitor_service_health.ObservationStore;
import use_case.recover_service.ContainerRecoveryPolicy;
import use_case.recover_service.RecoveryInteractor;
import use_case.recover_service.RecoveryVerifier;
import use_case.recover_service.VerifiedRecoveryRecommendations;
import use_case.shared.IncidentRepository;
import view.ConfigurationView;
import view.IncidentView;
import view.SentinelWindow;
import view.monitor_service_health.MonitoringView;

import javax.swing.SwingUtilities;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class Sentinel implements AutoCloseable {
    private static final Duration EVIDENCE_WINDOW = Duration.ofSeconds(60);
    private final ConfigurationRepository configurations;
    private final IncidentRepository incidents;
    private final Clock clock;
    /** Owned here: shared by every use case that reads or writes incidents, and closed with the application. */
    private final SingleThreadIncidentWorker incidentWorker = new SingleThreadIncidentWorker();
    private final ExecutorMonitoringScheduler monitoringScheduler = new ExecutorMonitoringScheduler();
    private final ExecutorService dockerWorker = Executors.newSingleThreadExecutor(task ->
            new Thread(task, "recovery-docker-worker"));
    private final DockerEngineInspector dockerInspector = new DockerEngineInspector();
    private final RecoveryVerifier recoveryVerifier = new RecoveryVerifier();
    private final JsonObservationStore observations;
    private final JsonRecoveryRecordRepository recoveryRecords;
    private final ConfigurationRepositoryReader configurationReader;
    private final VerifiedRecoveryRecommendations recommendations;
    private RecoveryController recoveryController;
    private MonitoringApplication monitoring;
    private final AtomicBoolean closed = new AtomicBoolean();

    public Sentinel(Path dataDirectory, Clock clock) {
        this.configurations = new JsonConfigurationRepository(dataDirectory.resolve("systems"));
        this.incidents = new JsonIncidentRepository(dataDirectory.resolve("incidents"));
        this.observations = new JsonObservationStore(dataDirectory.resolve("observations"));
        this.clock = Objects.requireNonNull(clock, "clock");
        this.recoveryRecords = new JsonRecoveryRecordRepository(dataDirectory.resolve("recovery"));
        this.configurationReader = new ConfigurationRepositoryReader(configurations);
        this.recommendations = new VerifiedRecoveryRecommendations(new RecoveryRecommendationProvider(),
                observations, recoveryVerifier, clock);
    }

    public static void main(String[] args) {
        Path dataDirectory = Path.of(System.getProperty("user.home"), ".sentinel");
        SwingUtilities.invokeLater(() -> new Sentinel(dataDirectory, Clock.systemUTC()).open());
    }

    private void open() {
        ExecutorService configurationWorker = Executors.newSingleThreadExecutor();
        ConfigurationView configurationView = new ConfigurationView();

        IncidentViewModel incidentViewModel = new IncidentViewModel();
        RecoveryPresenter recoveryPresenter = new RecoveryPresenter(SwingUtilities::invokeLater);
        DockerRecoveryGateway dockerRecovery = new DockerRecoveryGateway(dockerInspector, Duration.ofSeconds(10));
        RecoveryInteractor recovery = new RecoveryInteractor(incidents, configurationReader, recommendations,
                dockerRecovery, dockerRecovery, recoveryRecords, observations, new ContainerRecoveryPolicy(),
                recoveryVerifier, clock, UUID::randomUUID, recoveryPresenter, incidentWorker, dockerWorker);
        recoveryController = new RecoveryController(recovery, recoveryPresenter);
        IncidentView incidentView = new IncidentView(incidentViewModel, recoveryController);
        MonitoringViewModel monitoringViewModel = new MonitoringViewModel();
        MonitoringPresenter monitoringPresenter = new MonitoringPresenter(monitoringViewModel);
        MonitoringApplication runtime = wireMonitoring(monitoringPresenter, new IncidentPresenter(incidentViewModel));
        MonitoringController monitoringController = new MonitoringController(runtime.getChecks(),
                runtime.getSessions(), monitoringPresenter);
        MonitoringView monitoringView = new MonitoringView(monitoringController, monitoringViewModel);
        incidentView.connect(new InvestigateIncidentController(runtime.getInvestigation().getInvestigation()));

        ConfigurationInteractor configuration = new ConfigurationInteractor(configurations,
                new ConfigurationPresenter(sharedSystemList(configurationView, incidentView, monitoringView)));

        SentinelWindow window = new SentinelWindow(configurationView,
                monitoringView, incidentView,
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
                                                                IncidentView incidentView,
                                                                MonitoringView monitoringView) {
        return new ConfigurationPresenter.View() {
            @Override
            public void showSystems(List<MonitoredSystem> systems, List<String> skippedFiles) {
                configurationView.showSystems(systems, skippedFiles);
                Map<UUID, String> names = new LinkedHashMap<>();
                systems.forEach(system -> names.put(system.getId(), system.getName()));
                incidentView.showSystems(names);
                monitoringView.showSystems(systems);
            }

            @Override
            public void showSaved(MonitoredSystem system) {
                configurationView.showSaved(system);
                incidentView.showSavedSystem(system.getId(), system.getName());
                monitoringView.showSavedSystem(system);
            }

            @Override
            public void showError(String message) {
                configurationView.showError(message);
            }
        };
    }

    /** Builds UC2 and UC3 with one observation store and one clock. Call checks off the EDT. */
    public MonitoringApplication wireMonitoring(MonitoringOutputBoundary monitoringOutput,
                                                IncidentOutputBoundary incidentOutput) {
        if (closed.get()) {
            throw new IllegalStateException("The application is closed");
        }
        if (monitoring != null) {
            throw new IllegalStateException("Monitoring has already been wired");
        }
        IncidentInvestigation investigation = wireInvestigateIncident(observations, observations, incidentOutput);
        TargetHealthChecker checker = new TargetHealthChecker(new HttpHealthChecker(clock),
                new DockerHealthChecker(dockerInspector, clock));
        MonitoringExecution checks = new MonitoringExecution(new MonitoringInteractor(checker, observations,
                monitoringOutput, investigation.getFailureListener()));
        MonitoringSessionInteractor sessions = new MonitoringSessionInteractor(checks, monitoringScheduler,
                monitoringOutput);
        monitoring = new MonitoringApplication(checks, sessions, investigation);
        return monitoring;
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
        IncidentInteractor investigation = new IncidentInteractor(configurationReader,
                new ObservationLookupReader(lookup), incidents, assessment, history == observations ? recommendations
                        : new VerifiedRecoveryRecommendations(new RecoveryRecommendationProvider(), history, recoveryVerifier, clock),
                clock, incidentOutput);
        SerializedIncidentInputBoundary serialized = new SerializedIncidentInputBoundary(investigation, incidentWorker);
        IncidentFailureConnector listener = new IncidentFailureConnector(configurations, history, serialized,
                incidentOutput, clock, EVIDENCE_WINDOW);
        return new IncidentInvestigation(listener, serialized);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        if (monitoring != null) {
            monitoring.checks.stopAccepting();
            monitoring.getSessions().stopAll();
        }
        monitoringScheduler.close();
        CompletionStage<Void> recoveryDrained = recoveryController == null
                ? CompletableFuture.completedFuture(null) : recoveryController.stopAccepting();
        // In-flight monitoring may deliver incidents and accepted recovery may still save its outcome.
        // Keep the shared worker open for both while draining away from the EDT.
        Runnable shutdown = () -> {
            if (monitoring != null) {
                monitoring.checks.awaitCurrentCheck();
            }
            recoveryDrained.handle((ignored, failure) -> null).toCompletableFuture().join();
            dockerWorker.shutdown();
            incidentWorker.close();
        };
        if (monitoring == null && recoveryController == null) {
            shutdown.run();
        } else {
            new Thread(shutdown, "sentinel-shutdown").start();
        }
    }

    private static final class MonitoringExecution implements MonitoringInputBoundary {
        private final MonitoringInputBoundary delegate;
        private volatile boolean accepting = true;

        private MonitoringExecution(MonitoringInputBoundary delegate) {
            this.delegate = delegate;
        }

        @Override
        public synchronized void checkOnce(Service service) {
            if (accepting) {
                delegate.checkOnce(service);
            }
        }

        private void stopAccepting() {
            accepting = false;
        }

        private synchronized void awaitCurrentCheck() {
            // Acquiring this monitor waits for any check that passed the accepting guard.
        }
    }

    /** The monitoring inputs and the investigation inputs belonging to the same application wiring. */
    public static final class MonitoringApplication {
        private final MonitoringExecution checks;
        private final MonitoringSessionInputBoundary sessions;
        private final IncidentInvestigation investigation;

        private MonitoringApplication(MonitoringExecution checks, MonitoringSessionInputBoundary sessions,
                                      IncidentInvestigation investigation) {
            this.checks = checks;
            this.sessions = sessions;
            this.investigation = investigation;
        }

        public MonitoringInputBoundary getChecks() { return checks; }
        public MonitoringSessionInputBoundary getSessions() { return sessions; }
        public IncidentInvestigation getInvestigation() { return investigation; }
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
