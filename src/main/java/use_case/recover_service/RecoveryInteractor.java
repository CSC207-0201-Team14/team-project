package use_case.recover_service;

import entity.HealthCheckResult;
import entity.Incident;
import entity.IncidentRecoveryRecord;
import entity.MonitoringTarget;
import entity.RecoveryAction;
import entity.RecoveryAttempt;
import entity.Service;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import use_case.shared.ConfigurationReader;
import use_case.shared.ConfigurationSnapshot;
import use_case.monitor_service_health.ObservationStore;
import use_case.monitor_service_health.ObservationStoreException;
import use_case.shared.IncidentDataException;
import use_case.shared.IncidentRepository;

/**
 * Executes confirmed container recovery for an incident and resolves it once fresh checks verify recovery.
 * Coordinates ports only: operation choice lives in ContainerRecoveryPolicy and threshold evaluation in
 * RecoveryVerifier. Actions are re-derived from UC3's current recommendations before anything runs.
 * Observations come from UC2's ObservationStore, which monitoring keeps writing while recovery is verified;
 * UC4 only reads from it.
 */
public final class RecoveryInteractor implements RecoveryInputBoundary {
    private final IncidentRepository incidents;
    private final ConfigurationReader configurations;
    private final VerifiedRecoveryRecommendations recommendations;
    private final ContainerStateReader containerStates;
    private final ContainerOperator containers;
    private final RecoveryRecordRepository records;
    private final ObservationStore observations;
    private final ContainerRecoveryPolicy policy;
    private final RecoveryVerifier verifier;
    private final Clock clock;
    private final Supplier<UUID> attemptIds;
    private final RecoveryOutputBoundary output;
    private final Executor incidentWorker;
    private final Executor dockerWorker;
    private final Set<UUID> executing = new HashSet<>();
    private RecoveryPreview pendingPreview;

    public RecoveryInteractor(IncidentRepository incidents, ConfigurationReader configurations,
                              VerifiedRecoveryRecommendations recommendations, ContainerStateReader containerStates,
                              ContainerOperator containers, RecoveryRecordRepository records,
                              ObservationStore observations, ContainerRecoveryPolicy policy,
                              RecoveryVerifier verifier, Clock clock, Supplier<UUID> attemptIds,
                              RecoveryOutputBoundary output, Executor incidentWorker, Executor dockerWorker) {
        this.incidentWorker = Objects.requireNonNull(incidentWorker, "incidentWorker");
        this.dockerWorker = Objects.requireNonNull(dockerWorker, "dockerWorker");
        this.incidents = Objects.requireNonNull(incidents, "incidents");
        this.configurations = Objects.requireNonNull(configurations, "configurations");
        this.recommendations = Objects.requireNonNull(recommendations, "recommendations");
        this.containerStates = Objects.requireNonNull(containerStates, "containerStates");
        this.containers = Objects.requireNonNull(containers, "containers");
        this.records = Objects.requireNonNull(records, "records");
        this.observations = Objects.requireNonNull(observations, "observations");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.verifier = Objects.requireNonNull(verifier, "verifier");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.attemptIds = Objects.requireNonNull(attemptIds, "attemptIds");
        this.output = Objects.requireNonNull(output, "output");
    }

    @Override
    public CompletableFuture<Void> previewRecovery(UUID incidentId, UUID actionId) {
        return submit(() -> {
            pendingPreview = null;
            return preview(incidentId, actionId);
        });
    }

    private CompletableFuture<Void> preview(UUID incidentId, UUID actionId) {
        Optional<Target> found = findTarget(incidentId, actionId);
        if (found.isEmpty()) {
            return done();
        }
        Target target = found.get();
        return inspect(target).handleAsync((state, error) -> {
            if (error != null) {
                output.presentError("Could not inspect container " + target.containerId + ": " + cause(error).getMessage());
                output.presentManualSteps(guidance(target.incident, "Docker state is unavailable."));
            } else {
                showPreview(target, state, assessTarget(target));
            }
            return null;
        }, incidentWorker);
    }

    @Override
    public CompletableFuture<Void> executeRecovery(RecoveryRequest request) {
        return submit(() -> execute(request));
    }

    private CompletableFuture<Void> execute(RecoveryRequest request) {
        if (request.getPreview() != pendingPreview) {
            output.presentError("Confirm a current recovery preview before running a Docker operation.");
            return done();
        }
        UUID incidentId = request.getIncidentId();
        if (executing.contains(incidentId)) {
            output.presentError("Recovery is already running for this incident.");
            return done();
        }
        RecoveryPreview confirmed = pendingPreview;
        pendingPreview = null;
        Optional<Target> found = findTarget(incidentId, request.getActionId());
        if (found.isEmpty()) {
            return done();
        }
        // Surface unreadable history before sending a mutation to Docker.
        records.findByIncidentId(incidentId);
        Target target = found.get();
        Instant startedAt = clock.instant();
        executing.add(incidentId);
        return inspect(target).handleAsync((state, error) -> {
            Optional<Target> current = findTarget(incidentId, request.getActionId());
            if (current.isEmpty()) {
                return done();
            }
            Target latest = current.get();
            if (!latest.containerId.equals(target.containerId)) {
                return preview(incidentId, request.getActionId());
            }
            if (error != null) {
                Throwable failure = cause(error);
                record(latest, startedAt, Optional.empty(), Optional.empty(), outcomeOf(failure),
                        "Could not inspect container " + latest.containerId + "; no operation was performed: "
                                + failure.getMessage());
                return done();
            }
            ServiceVerification targetHealth = assessTarget(latest);
            RecoveryDecision decision = policy.select(state, targetHealth);
            if (decision.getOperation().isEmpty()) {
                output.presentManualSteps(guidance(latest.incident, decision.getExplanation()));
                return done();
            }
            RecoveryOperation operation = decision.getOperation().orElseThrow();
            if (!matches(confirmed, latest, state, operation)) {
                showPreview(latest, state, targetHealth);
                return done();
            }
            return operate(latest, state, operation, startedAt);
        }, incidentWorker).thenCompose(Function.identity())
                .whenCompleteAsync((ignored, failure) -> executing.remove(incidentId), incidentWorker);
    }

    private CompletableFuture<ContainerState> inspect(Target target) {
        return CompletableFuture.supplyAsync(() -> containerStates.inspect(target.containerId), dockerWorker);
    }

    private CompletableFuture<Void> operate(Target target, ContainerState state, RecoveryOperation operation,
                                            Instant startedAt) {
        String identity = state.getContainerIdentity().orElseThrow();
        String description = describe(operation, identity, state);
        return CompletableFuture.runAsync(() -> run(operation, identity), dockerWorker)
                .handleAsync((ignored, error) -> {
                    Throwable failure = error == null ? null : cause(error);
                    RecoveryAttempt.Outcome outcome = failure == null ? RecoveryAttempt.Outcome.SUCCEEDED
                            : outcomeOf(failure);
                    String message = failure == null ? description + " succeeded."
                            : description + " failed. " + failure.getMessage();
                    boolean saved = record(target, startedAt, Optional.of(state), Optional.of(operation), outcome,
                            message);
                    if (saved && failure == null) {
                        verify(target.incident.getId());
                    }
                    return null;
                }, incidentWorker);
    }

    private CompletableFuture<Void> submit(Supplier<CompletableFuture<Void>> work) {
        return CompletableFuture.supplyAsync(work, incidentWorker).thenCompose(Function.identity())
                .exceptionallyAsync(error -> {
                    Throwable failure = cause(error);
                    output.presentError(failure.getMessage() == null ? "Recovery could not complete." : failure.getMessage());
                    return null;
                }, incidentWorker);
    }

    private static CompletableFuture<Void> done() {
        return CompletableFuture.completedFuture(null);
    }

    private static Throwable cause(Throwable error) {
        return error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
    }

    private void showPreview(Target target, ContainerState state, ServiceVerification targetHealth) {
        RecoveryDecision decision = policy.select(state, targetHealth);
        if (decision.isSupported() && state.getContainerIdentity().isEmpty()) {
            output.presentManualSteps(guidance(target.incident, "Docker did not report a container identity."));
            return;
        }
        RecoveryPreview preview = new RecoveryPreview(target.action, target.service.getName(), target.containerId,
                state, targetHealth, decision, manualSteps(target.incident));
        pendingPreview = preview;
        output.presentPreview(preview);
    }

    private static boolean matches(RecoveryPreview approved, Target target, ContainerState state,
                                   RecoveryOperation operation) {
        return approved.getAction().getTargetServiceId().equals(target.service.getId())
                && approved.getContainerId().equals(target.containerId)
                && state.getContainerIdentity().isPresent()
                && approved.getState().getContainerIdentity().equals(state.getContainerIdentity())
                && approved.getDecision().getOperation().filter(operation::equals).isPresent();
    }

    @Override
    public CompletableFuture<Void> beginManualVerification(UUID incidentId) {
        return submit(() -> {
            if (availableForVerification(incidentId) && findUnresolved(incidentId).isPresent()) {
                IncidentRecoveryRecord record = records.findByIncidentId(incidentId)
                        .orElse(IncidentRecoveryRecord.empty(incidentId));
                records.save(record.beginManualVerification(clock.instant()));
                verify(incidentId);
            }
            return done();
        });
    }

    @Override
    public CompletableFuture<Void> verifyResolution(UUID incidentId) {
        return submit(() -> {
            if (availableForVerification(incidentId)) {
                verify(incidentId);
            }
            return done();
        });
    }

    @Override
    public CompletableFuture<Void> loadHistory(UUID incidentId) {
        return submit(() -> {
            output.presentHistory(records.findByIncidentId(incidentId)
                    .orElseGet(() -> IncidentRecoveryRecord.empty(incidentId)));
            return done();
        });
    }

    private boolean availableForVerification(UUID incidentId) {
        if (executing.contains(incidentId)) {
            output.presentError("Wait for the current recovery attempt to finish before verifying recovery.");
            return false;
        }
        return true;
    }

    private void verify(UUID incidentId) {
        Optional<Incident> found = findUnresolved(incidentId);
        if (found.isEmpty()) {
            return;
        }
        Incident incident = found.get();
        Optional<IncidentRecoveryRecord> record = records.findByIncidentId(incidentId);
        Optional<Instant> windowStart = record.flatMap(IncidentRecoveryRecord::getVerificationStartedAt);
        if (windowStart.isEmpty()) {
            output.presentError("No recovery is awaiting verification for this incident.");
            return;
        }
        Optional<ConfigurationSnapshot> configuration = configurations.findBySystemId(incident.getSystemId());
        if (configuration.isEmpty()) {
            output.presentError("Monitored system was not found");
            return;
        }
        Map<UUID, Service> services = configuration.get().getSystem().getServices().stream()
                .collect(Collectors.toMap(Service::getId, Function.identity()));
        Map<UUID, List<HealthCheckResult>> fresh = new HashMap<>();
        for (UUID serviceId : incident.getRecoveryRequiredServiceIds()) {
            if (services.containsKey(serviceId)) {
                fresh.put(serviceId, history(serviceId));
            }
        }
        Instant now = clock.instant();
        VerificationResult result = verifier.evaluate(incident, windowStart.get(), services, fresh, now);
        if (!result.isSatisfied()) {
            output.presentVerification(result);
            return;
        }
        Incident resolved;
        try {
            resolved = incident.resolve(now, windowStart.get(), result.getHealthByService());
        } catch (IllegalStateException | IllegalArgumentException exception) {
            output.presentError("Recovery could not be confirmed: " + exception.getMessage());
            return;
        }
        incidents.save(resolved);
        output.presentResolved(new ResolutionResult(resolved, record.get().getAttempts(), result));
    }

    /** The target's health from checks since its latest recorded failure; decides whether a restart is warranted. */
    private ServiceVerification assessTarget(Target target) {
        return recommendations.assess(target.incident, target.service, clock.instant());
    }

    /**
     * The service's full observation history, oldest first. RecoveryVerifier selects the checks inside its
     * window, so no filtering happens here. Storage failures become IncidentDataException, like other data access.
     */
    private List<HealthCheckResult> history(UUID serviceId) {
        try {
            return observations.findByServiceId(serviceId);
        } catch (ObservationStoreException exception) {
            throw new IncidentDataException("Observations could not be read: " + exception.getMessage(), exception);
        }
    }

    /** Saves the attempt and presents it. Returns false if the attempt could not be saved. */
    private boolean record(Target target, Instant startedAt, Optional<ContainerState> state,
                           Optional<RecoveryOperation> operation, RecoveryAttempt.Outcome outcome, String message) {
        Instant completedAt = clock.instant();
        if (completedAt.isBefore(startedAt)) {
            completedAt = startedAt;
        }
        RecoveryAttempt attempt = new RecoveryAttempt(attemptIds.get(), target.action.getId(), startedAt,
                completedAt, outcome, message);
        try {
            IncidentRecoveryRecord record = records.findByIncidentId(target.incident.getId())
                    .orElse(IncidentRecoveryRecord.empty(target.incident.getId()))
                    .recordAttempt(attempt);
            records.save(record);
        } catch (IncidentDataException | IllegalArgumentException exception) {
            output.presentError("The recovery attempt (" + outcome + ": " + message
                    + ") could not be recorded: " + exception.getMessage());
            return false;
        }
        output.presentAttempt(new RecoveryAttemptResult(target.action, target.containerId, state, operation, attempt));
        return true;
    }

    /** Validates that the action is currently recommended for an unresolved incident and targets a container. */
    private Optional<Target> findTarget(UUID incidentId, UUID actionId) {
        Optional<Incident> found = findUnresolved(incidentId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        Incident incident = found.get();
        Optional<ConfigurationSnapshot> configuration = configurations.findBySystemId(incident.getSystemId());
        if (configuration.isEmpty()) {
            output.presentError("Monitored system was not found");
            return Optional.empty();
        }
        List<RecoveryAction> recommended = recommendations.recommend(incident, configuration.get());
        Optional<RecoveryAction> action = recommended.stream()
                .filter(candidate -> candidate.getId().equals(actionId)).findFirst();
        if (action.isEmpty()) {
            if (recommended.isEmpty()) {
                output.presentManualSteps(guidance(incident,
                        "No supported recovery action is available for this incident."));
            } else {
                output.presentError("The selected recovery action is no longer recommended for this incident.");
            }
            return Optional.empty();
        }
        Optional<Service> service = configuration.get().getSystem().getServices().stream()
                .filter(candidate -> candidate.getId().equals(action.get().getTargetServiceId())).findFirst();
        Optional<String> containerId = service.filter(candidate ->
                        candidate.getMonitoringTarget().getMethod() == MonitoringTarget.Method.DOCKER)
                .flatMap(candidate -> candidate.getMonitoringTarget().getContainerId());
        if (containerId.isEmpty()) {
            output.presentManualSteps(guidance(incident, "The recovery target is not a configured container."));
            return Optional.empty();
        }
        return Optional.of(new Target(incident, action.get(), service.get(), containerId.get()));
    }

    private Optional<Incident> findUnresolved(UUID incidentId) {
        Optional<Incident> incident = incidents.findById(incidentId);
        if (incident.isEmpty()) {
            output.presentError("Incident was not found");
            return Optional.empty();
        }
        if (incident.get().getStatus() == Incident.Status.RESOLVED) {
            output.presentError("The incident is already resolved");
            return Optional.empty();
        }
        return incident;
    }

    private void run(RecoveryOperation operation, String containerId) {
        switch (operation) {
            case START:
                containers.start(containerId);
                break;
            case UNPAUSE:
                containers.unpause(containerId);
                break;
            case RESTART:
                containers.restart(containerId);
                break;
            default:
                throw new IllegalStateException("Unsupported operation " + operation);
        }
    }

    private static RecoveryAttempt.Outcome outcomeOf(Throwable exception) {
        return exception instanceof ContainerAccessException
                && ((ContainerAccessException) exception).getKind() == ContainerAccessException.Kind.PERMISSION_DENIED
                ? RecoveryAttempt.Outcome.PERMISSION_DENIED
                : RecoveryAttempt.Outcome.FAILED;
    }

    private static String describe(RecoveryOperation operation, String containerId, ContainerState state) {
        return operation + " of container " + containerId + " (was " + state + ")";
    }

    private static ManualRecoveryGuidance guidance(Incident incident, String reason) {
        return new ManualRecoveryGuidance(incident.getId(), reason, manualSteps(incident));
    }

    /** Distinct next steps from the incident's active findings, in finding order. */
    private static List<String> manualSteps(Incident incident) {
        Set<String> steps = new LinkedHashSet<>();
        incident.getActiveFindings().forEach(finding -> steps.addAll(finding.getNextSteps()));
        return List.copyOf(steps);
    }

    private static final class Target {
        private final Incident incident;
        private final RecoveryAction action;
        private final Service service;
        private final String containerId;

        private Target(Incident incident, RecoveryAction action, Service service, String containerId) {
            this.incident = incident;
            this.action = action;
            this.service = service;
            this.containerId = containerId;
        }
    }
}
