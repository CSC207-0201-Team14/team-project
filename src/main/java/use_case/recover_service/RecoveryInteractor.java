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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import use_case.investigate_incident.ConfigurationReader;
import use_case.investigate_incident.ConfigurationSnapshot;
import use_case.investigate_incident.RecoveryRecommendationProvider;
import use_case.shared.IncidentDataException;
import use_case.shared.IncidentRepository;

/**
 * Executes confirmed container recovery for an incident and resolves it once fresh checks verify recovery.
 * Coordinates ports only: operation choice lives in ContainerRecoveryPolicy and threshold evaluation in
 * RecoveryVerifier. Actions are re-derived from UC3's current recommendations before anything runs.
 */
public final class RecoveryInteractor implements RecoveryInputBoundary {
    private final IncidentRepository incidents;
    private final ConfigurationReader configurations;
    private final RecoveryRecommendationProvider recommendations;
    private final ContainerStateReader containerStates;
    private final ContainerOperator containers;
    private final RecoveryRecordRepository records;
    private final RecoveryObservationReader observations;
    private final ContainerRecoveryPolicy policy;
    private final RecoveryVerifier verifier;
    private final Clock clock;
    private final Supplier<UUID> attemptIds;
    private final RecoveryOutputBoundary output;

    public RecoveryInteractor(IncidentRepository incidents, ConfigurationReader configurations,
                              RecoveryRecommendationProvider recommendations, ContainerStateReader containerStates,
                              ContainerOperator containers, RecoveryRecordRepository records,
                              RecoveryObservationReader observations, ContainerRecoveryPolicy policy,
                              RecoveryVerifier verifier, Clock clock, Supplier<UUID> attemptIds,
                              RecoveryOutputBoundary output) {
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
    public void previewRecovery(UUID incidentId, UUID actionId) {
        Objects.requireNonNull(incidentId, "incidentId");
        Objects.requireNonNull(actionId, "actionId");
        try {
            Optional<Target> found = findTarget(incidentId, actionId);
            if (found.isEmpty()) {
                return;
            }
            Target target = found.get();
            ContainerState state;
            try {
                state = containerStates.inspect(target.containerId);
            } catch (ContainerAccessException exception) {
                output.presentError("Could not inspect container " + target.containerId + ": "
                        + exception.getMessage());
                output.presentManualSteps(guidance(target.incident,
                        "The container state is unavailable, so Sentinel cannot choose a recovery operation."));
                return;
            }
            output.presentPreview(new RecoveryPreview(target.action, target.service.getName(), target.containerId,
                    state, policy.select(state), manualSteps(target.incident)));
        } catch (IncidentDataException exception) {
            output.presentError(exception.getMessage());
        }
    }

    @Override
    public void executeRecovery(RecoveryRequest request) {
        Objects.requireNonNull(request, "request");
        if (!request.isConfirmed()) {
            output.presentError("Recovery was not confirmed, so no action was taken.");
            return;
        }
        try {
            Optional<Target> found = findTarget(request.getIncidentId(), request.getActionId());
            if (found.isEmpty()) {
                return;
            }
            Target target = found.get();
            Instant startedAt = clock.instant();

            ContainerState state;
            try {
                state = containerStates.inspect(target.containerId);
            } catch (ContainerAccessException exception) {
                record(target, startedAt, Optional.empty(), Optional.empty(), outcomeOf(exception),
                        "Could not inspect container " + target.containerId + "; no operation was performed: "
                                + exception.getMessage());
                return;
            }

            RecoveryDecision decision = policy.select(state);
            if (decision.getOperation().isEmpty()) {
                output.presentManualSteps(guidance(target.incident, decision.getExplanation()));
                return;
            }
            RecoveryOperation operation = decision.getOperation().get();
            RecoveryAttempt.Outcome outcome;
            String message;
            try {
                run(operation, target.containerId);
                outcome = RecoveryAttempt.Outcome.SUCCEEDED;
                message = describe(operation, target.containerId, state) + " succeeded.";
            } catch (ContainerAccessException exception) {
                outcome = outcomeOf(exception);
                message = describe(operation, target.containerId, state) + " failed: " + exception.getMessage();
            } catch (RuntimeException exception) {
                outcome = RecoveryAttempt.Outcome.FAILED;
                message = describe(operation, target.containerId, state) + " failed unexpectedly: "
                        + exception.getMessage();
            }
            boolean saved = record(target, startedAt, Optional.of(state), Optional.of(operation), outcome, message);
            if (saved && outcome == RecoveryAttempt.Outcome.SUCCEEDED) {
                verify(target.incident.getId());
            }
        } catch (IncidentDataException exception) {
            output.presentError(exception.getMessage());
        }
    }

    @Override
    public void beginManualVerification(UUID incidentId) {
        Objects.requireNonNull(incidentId, "incidentId");
        try {
            Optional<Incident> incident = findUnresolved(incidentId);
            if (incident.isEmpty()) {
                return;
            }
            IncidentRecoveryRecord record = records.findByIncidentId(incidentId)
                    .orElse(IncidentRecoveryRecord.empty(incidentId));
            try {
                record = record.beginManualVerification(clock.instant());
            } catch (IllegalArgumentException exception) {
                output.presentError("Verification could not be started: " + exception.getMessage());
                return;
            }
            records.save(record);
            verify(incidentId);
        } catch (IncidentDataException exception) {
            output.presentError(exception.getMessage());
        }
    }

    @Override
    public void verifyResolution(UUID incidentId) {
        Objects.requireNonNull(incidentId, "incidentId");
        try {
            verify(incidentId);
        } catch (IncidentDataException exception) {
            output.presentError(exception.getMessage());
        }
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
                fresh.put(serviceId, observations.findByServiceSince(serviceId, windowStart.get()));
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

    private static RecoveryAttempt.Outcome outcomeOf(ContainerAccessException exception) {
        return exception.getKind() == ContainerAccessException.Kind.PERMISSION_DENIED
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
