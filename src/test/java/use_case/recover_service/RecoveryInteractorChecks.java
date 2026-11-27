package use_case.recover_service;

import data_access.persistence.JsonIncidentRepository;
import data_access.persistence.JsonRecoveryRecordRepository;
import entity.Dependency;
import entity.DiagnosticFinding;
import entity.FailureEvent;
import entity.HealthCheckResult;
import entity.Incident;
import entity.IncidentRecoveryRecord;
import entity.MonitoredSystem;
import entity.MonitoringPolicy;
import entity.MonitoringTarget;
import entity.RecoveryAction;
import entity.RecoveryAttempt;
import entity.Service;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import use_case.shared.ConfigurationReader;
import use_case.shared.ConfigurationSnapshot;
import use_case.investigate_incident.RecoveryRecommendationProvider;
import use_case.monitor_service_health.ObservationStore;
import use_case.monitor_service_health.ObservationStoreException;
import use_case.shared.IncidentDataException;
import use_case.shared.IncidentRepository;

/** Run explicitly after mvn test-compile, following the existing test convention. */
public final class RecoveryInteractorChecks {
    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

    public static void main(String[] args) throws Exception {
        rejectsUnconfirmedRequests();
        rejectsActionsThatAreNotRecommended();
        rejectsResolvedIncidents();
        httpOnlyIncidentGetsManualSteps();
        previewShowsOperationWithoutChangingAnything();
        choosesOperationFromLiveStateAtExecution();
        changedDockerTargetAndIdentityRequireConfirmation();
        unsuitableStateRecordsNoAttempt();
        permissionDeniedIsRecordedAndIncidentStaysUnresolved();
        failedOperationIsRecorded();
        unavailableEngineIsRecordedWithoutOperation();
        successThenFreshPassesResolvesTheIncident();
        remainingFailuresKeepTheIncidentUnresolved();
        storageErrorsArePresented();
        manualVerificationResolvesTheIncident();
        verificationRequiresAStartedRecovery();
        recoveredTargetIsNotRestarted();
        targetThatRecoversAfterThePreviewIsNotRestarted();
        dependentIsRecoveredOnceItsDependencyRecovers();
        failingTargetIsStillRestarted();
        unreadableObservationsRunNoOperation();
        asynchronousRecoveryPreservesIncidentUpdates();
        persistedRecoverySurvivesRestart();
        System.out.println("UC4 recovery interactor checks passed");
    }

    private static void rejectsUnconfirmedRequests() {
        Fixture f = new Fixture();
        Fixture other = new Fixture();
        other.preview();
        f.interactor.executeRecovery(new RecoveryRequest(only(other.output.previews)));
        check(f.output.errors.size() == 1 && f.containers.inspections == 0 && f.containers.operations.isEmpty(),
                "A preview issued by a different session cannot authorize a Docker operation");
    }

    private static void rejectsActionsThatAreNotRecommended() {
        Fixture f = new Fixture();
        f.interactor.previewRecovery(f.incident.getId(), UUID.randomUUID());
        check(f.output.errors.size() == 1 && f.output.errors.get(0).contains("no longer recommended"),
                "An unknown or stale action is rejected");
        check(f.containers.operations.isEmpty(), "No operation runs for an unrecommended action");
    }

    private static void rejectsResolvedIncidents() {
        Fixture f = new Fixture();
        RecoveryAction action = f.dbAction();
        f.preview();
        f.incidents.save(f.resolvedIncident());
        f.interactor.executeRecovery(new RecoveryRequest(only(f.output.previews)));
        check(f.output.errors.equals(List.of("The incident is already resolved")) && f.containers.operations.isEmpty(),
                "A resolved incident cannot be recovered again");
        f.interactor.previewRecovery(UUID.randomUUID(), action.getId());
        check(f.output.errors.get(1).equals("Incident was not found"), "A missing incident is reported");
    }

    private static void httpOnlyIncidentGetsManualSteps() {
        Fixture f = new Fixture();
        Incident httpOnly = f.incidentFailing(f.api);
        f.incidents.save(httpOnly);
        f.interactor.previewRecovery(httpOnly.getId(), UUID.randomUUID());
        ManualRecoveryGuidance guidance = only(f.output.manual);
        check(guidance.getReason().contains("No supported recovery action")
                && guidance.getNextSteps().equals(List.of("Check the API logs")),
                "Without a supported action, the finding's manual next steps are shown");
        check(f.containers.inspections == 0, "No container is inspected for an HTTP-only incident");
    }

    private static void previewShowsOperationWithoutChangingAnything() {
        Fixture f = new Fixture();
        f.containers.state = new ContainerState(ContainerState.Status.EXITED, false);
        f.interactor.previewRecovery(f.incident.getId(), f.dbAction().getId());
        RecoveryPreview preview = only(f.output.previews);
        check(preview.getContainerId().equals("db-container") && preview.getServiceName().equals("DB")
                && preview.getDecision().getOperation().equals(Optional.of(RecoveryOperation.START))
                && preview.getState().getStatus() == ContainerState.Status.EXITED,
                "The preview names the container, its state and the operation it determines");
        check(f.containers.operations.isEmpty() && f.records.stored.isEmpty(), "Previewing changes nothing");

        f.containers.state = new ContainerState(ContainerState.Status.RESTARTING, false);
        f.interactor.previewRecovery(f.incident.getId(), f.dbAction().getId());
        RecoveryPreview unsuitable = f.output.previews.get(1);
        check(!unsuitable.getDecision().isSupported() && !unsuitable.getManualSteps().isEmpty(),
                "An unsuitable state previews the manual steps instead of an operation");
    }

    private static void choosesOperationFromLiveStateAtExecution() {
        expectOperation(ContainerState.Status.EXITED, "start");
        expectOperation(ContainerState.Status.PAUSED, "unpause");
        expectOperation(ContainerState.Status.RUNNING, "restart");

        Fixture f = new Fixture();
        f.containers.state = new ContainerState(ContainerState.Status.RUNNING, false);
        f.interactor.previewRecovery(f.incident.getId(), f.dbAction().getId());
        f.containers.state = new ContainerState(ContainerState.Status.EXITED, false);
        f.confirm();
        check(f.containers.operations.isEmpty(), "A changed operation requires a fresh confirmation");
        f.confirm();
        check(f.containers.operations.equals(List.of("start:db-container")), "The fresh preview authorizes START");
    }

    private static void changedDockerTargetAndIdentityRequireConfirmation() {
        Fixture f = new Fixture();
        f.preview();
        RecoveryRequest original = new RecoveryRequest(only(f.output.previews));
        Service updated = new Service(f.db.getId(), f.systemId, f.db.getName(),
                MonitoringTarget.docker("new-db"), f.dbPolicy);
        f.configuration = ConfigurationSnapshot.available(
                new MonitoredSystem(f.systemId, "Shop", "", List.of(f.api, updated), List.of(f.edge)));
        f.interactor.executeRecovery(original);
        RecoveryPreview refreshed = f.output.previews.get(1);
        check(refreshed.getContainerId().equals("new-db") && f.containers.operations.isEmpty(),
                "An edited Docker target refreshes the preview even when the action ID stays the same");

        f.containers.identity = "replacement-id";
        f.interactor.executeRecovery(new RecoveryRequest(refreshed));
        check(f.containers.operations.isEmpty(), "Reassigning the container name requires confirmation again");
        RecoveryRequest replacement = new RecoveryRequest(f.output.previews.get(2));
        f.interactor.executeRecovery(replacement);
        f.interactor.executeRecovery(replacement);
        check(f.containers.operations.equals(List.of("restart:replacement-id")),
                "The authorized canonical container is operated on exactly once");
    }

    private static void expectOperation(ContainerState.Status status, String operation) {
        Fixture f = new Fixture();
        f.containers.state = new ContainerState(status, false);
        f.confirm();
        check(f.containers.operations.equals(List.of(operation + ":db-container")),
                status + " should run " + operation);
        RecoveryAttemptResult result = only(f.output.attempts);
        check(result.isSucceeded() && result.getStateBefore().orElseThrow().getStatus() == status
                && result.getAttempt().getActionId().equals(f.dbAction().getId()),
                "The successful attempt records the state it acted on");
    }

    private static void unsuitableStateRecordsNoAttempt() {
        Fixture f = new Fixture();
        f.containers.state = new ContainerState(ContainerState.Status.EXITED, true);
        f.preview();
        check(f.containers.operations.isEmpty() && f.records.stored.isEmpty() && f.output.attempts.isEmpty(),
                "No operation runs and no attempt is recorded for an unsuitable state");
        check(only(f.output.previews).getDecision().getExplanation().contains("memory"),
                "The preview explains the unsuitable state");
        check(f.storedIncident().getStatus() != Incident.Status.RESOLVED, "The incident remains unresolved");
    }

    private static void permissionDeniedIsRecordedAndIncidentStaysUnresolved() {
        Fixture f = new Fixture();
        f.containers.operationFailure = new ContainerAccessException(
                ContainerAccessException.Kind.PERMISSION_DENIED, "permission denied");
        f.confirm();
        RecoveryAttempt attempt = only(f.output.attempts).getAttempt();
        check(attempt.getOutcome() == RecoveryAttempt.Outcome.PERMISSION_DENIED
                && attempt.getMessage().contains("permission denied"), "A denied operation is recorded as denied");
        IncidentRecoveryRecord record = f.records.stored.get(f.incident.getId());
        check(record.getAttempts().equals(List.of(attempt)) && record.getVerificationStartedAt().isEmpty(),
                "A denied attempt is retained and does not start verification");
        check(f.output.verifications.isEmpty() && f.storedIncident().getStatus() != Incident.Status.RESOLVED,
                "The incident remains unresolved and no verification runs");

        f.containers.operationFailure = null;
        f.clock.advance(5);
        f.confirm();
        check(f.records.stored.get(f.incident.getId()).getAttempts().size() == 2,
                "The developer can retry, and both attempts are retained");
    }

    private static void failedOperationIsRecorded() {
        Fixture f = new Fixture();
        f.containers.operationFailure = new ContainerAccessException(ContainerAccessException.Kind.FAILED, "boom");
        f.confirm();
        check(only(f.output.attempts).getAttempt().getOutcome() == RecoveryAttempt.Outcome.FAILED,
                "A failed operation is recorded as failed");

        Fixture unexpected = new Fixture();
        unexpected.containers.unexpectedFailure = new IllegalStateException("unexpected");
        unexpected.confirm();
        check(only(unexpected.output.attempts).getAttempt().getOutcome() == RecoveryAttempt.Outcome.FAILED,
                "An unexpected gateway exception is recorded as a failure rather than crashing");
    }

    private static void unavailableEngineIsRecordedWithoutOperation() {
        Fixture f = new Fixture();
        f.preview();
        f.containers.inspectFailure = new ContainerAccessException(
                ContainerAccessException.Kind.UNAVAILABLE, "engine unreachable");
        f.confirm();
        RecoveryAttemptResult result = only(f.output.attempts);
        check(result.getAttempt().getOutcome() == RecoveryAttempt.Outcome.FAILED
                && result.getOperation().isEmpty() && result.getStateBefore().isEmpty()
                && result.getAttempt().getMessage().contains("no operation was performed"),
                "An unreachable engine is recorded as a failed attempt that performed no operation");
        check(f.containers.operations.isEmpty(), "Nothing runs when the state is unknown");

        Fixture preview = new Fixture();
        preview.containers.inspectFailure = f.containers.inspectFailure;
        preview.interactor.previewRecovery(preview.incident.getId(), preview.dbAction().getId());
        check(preview.output.errors.size() == 1 && preview.output.manual.size() == 1
                && preview.records.stored.isEmpty(), "A preview reports the error and the manual steps only");
    }

    private static void successThenFreshPassesResolvesTheIncident() {
        Fixture f = new Fixture();
        f.confirm();
        VerificationResult pending = only(f.output.verifications);
        check(pending.getStatus() == VerificationResult.Status.PENDING
                && Set.copyOf(ids(pending.getRemainingServices())).equals(Set.of(f.db.getId(), f.api.getId())),
                "Right after recovery, both the target and its affected dependent await fresh checks");
        check(f.storedIncident().getStatus() == Incident.Status.INVESTIGATING,
                "Execution alone does not resolve the incident");

        Instant window = f.records.stored.get(f.incident.getId()).getVerificationStartedAt().orElseThrow();
        f.observations.passes(f.db, window, 3);
        f.observations.passes(f.api, window, 2);
        f.clock.advance(60);
        f.interactor.verifyResolution(f.incident.getId());

        ResolutionResult resolution = only(f.output.resolutions);
        Incident stored = f.storedIncident();
        check(stored.getStatus() == Incident.Status.RESOLVED && resolution.getIncident() == stored,
                "Satisfied recovery criteria resolve and save the incident");
        check(stored.getResolutionEvidence().size() == 2 && resolution.getAttempts().size() == 1
                && resolution.getAttempts().get(0).getOutcome() == RecoveryAttempt.Outcome.SUCCEEDED,
                "The resolution retains the passing checks and the recovery attempt");
        check(f.incidents.findUnresolvedBySystemId(f.systemId).isEmpty(), "The incident moves to history");
    }

    private static void remainingFailuresKeepTheIncidentUnresolved() {
        Fixture f = new Fixture();
        f.confirm();
        Instant window = f.records.stored.get(f.incident.getId()).getVerificationStartedAt().orElseThrow();
        f.observations.passes(f.db, window, 3);
        f.observations.add(f.api, window.plusSeconds(10), HealthCheckResult.Outcome.FAIL);
        f.clock.advance(60);
        f.interactor.verifyResolution(f.incident.getId());
        VerificationResult result = f.output.verifications.get(1);
        check(result.getStatus() == VerificationResult.Status.PENDING
                && ids(result.getRemainingServices()).equals(List.of(f.api.getId())),
                "A dependent that is still failing is displayed as remaining");
        check(f.storedIncident().getStatus() != Incident.Status.RESOLVED && f.output.resolutions.isEmpty(),
                "The incident remains unresolved");

        f.observations.add(f.api, window.plusSeconds(20), HealthCheckResult.Outcome.MONITORING_ERROR);
        f.interactor.verifyResolution(f.incident.getId());
        check(f.output.verifications.get(2).getStatus() == VerificationResult.Status.MONITORING_ERROR,
                "A monitoring error is reported instead of a confirmed failure");
    }

    private static void storageErrorsArePresented() {
        Fixture f = new Fixture();
        f.incidents.failure = new IncidentDataException("Incident storage is unavailable");
        f.confirm();
        check(f.output.errors.equals(List.of("Incident storage is unavailable")) && f.containers.operations.isEmpty(),
                "Incident storage errors are presented and nothing runs");

        Fixture saving = new Fixture();
        saving.records.failure = new IncidentDataException("disk full");
        saving.confirm();
        check(saving.containers.operations.size() == 1 && saving.output.errors.size() == 1
                && saving.output.errors.get(0).contains("could not be recorded")
                && saving.output.errors.get(0).contains("SUCCEEDED"),
                "If the attempt cannot be saved, the developer still learns what ran");
        check(saving.output.verifications.isEmpty(), "Verification does not start without a saved window");

        Fixture observing = new Fixture();
        observing.confirm();
        observing.observations.failure = new ObservationStoreException("disk unreadable", null);
        observing.interactor.verifyResolution(observing.incident.getId());
        check(observing.output.errors.equals(List.of("Observations could not be read: disk unreadable")),
                "Observation store errors are presented");
        check(observing.storedIncident().getStatus() != Incident.Status.RESOLVED,
                "The incident stays unresolved when observations cannot be read");
    }

    private static void manualVerificationResolvesTheIncident() {
        Fixture f = new Fixture();
        Incident httpOnly = f.incidentFailing(f.api);
        f.incidents.save(httpOnly);
        f.interactor.beginManualVerification(httpOnly.getId());
        check(only(f.output.verifications).getStatus() == VerificationResult.Status.PENDING,
                "Manual verification starts and waits for fresh checks");
        Instant window = f.records.stored.get(httpOnly.getId()).getVerificationStartedAt().orElseThrow();
        f.observations.passes(f.api, window, 2);
        f.clock.advance(60);
        f.interactor.verifyResolution(httpOnly.getId());
        ResolutionResult resolution = only(f.output.resolutions);
        check(resolution.getIncident().getStatus() == Incident.Status.RESOLVED && resolution.getAttempts().isEmpty(),
                "An externally recovered incident resolves after fresh passes, with no attempts recorded");
        check(f.containers.operations.isEmpty(), "Manual recovery never touches containers");
    }

    private static void verificationRequiresAStartedRecovery() {
        Fixture f = new Fixture();
        f.observations.passes(f.db, START.plusSeconds(500), 5);
        f.observations.passes(f.api, START.plusSeconds(500), 5);
        f.interactor.verifyResolution(f.incident.getId());
        check(f.output.errors.size() == 1 && f.output.errors.get(0).contains("No recovery is awaiting verification"),
                "Without a recovery attempt or manual verification, the incident is not resolved");
        check(f.storedIncident().getStatus() != Incident.Status.RESOLVED, "Natural recovery alone does not resolve");
    }

    private static void recoveredTargetIsNotRestarted() {
        Fixture f = new Fixture();
        f.observations.passes(f.db, START.plusSeconds(50), 3);
        f.preview();
        check(f.output.previews.isEmpty() && f.containers.inspections == 0,
                "A service that meets its recovery threshold is no longer offered");
        check(!only(f.output.manual).getNextSteps().isEmpty(), "The incident retains its manual steps");
    }

    private static void dependentIsRecoveredOnceItsDependencyRecovers() {
        Fixture f = new Fixture(true);
        RecoveryAction apiAction = f.provider.recommend(f.incident, f.configuration, Set.of(f.db.getId())).stream()
                .filter(action -> action.getTargetServiceId().equals(f.api.getId())).findFirst().orElseThrow();

        f.interactor.previewRecovery(f.incident.getId(), apiAction.getId());
        check(f.output.previews.isEmpty() && f.output.errors.size() == 1,
                "While DB is still failing, API is held back and its action is not offered");

        f.observations.passes(f.db, START.plusSeconds(50), 3);
        f.observations.add(f.api, START.plusSeconds(55), HealthCheckResult.Outcome.FAIL);
        VerifiedRecoveryRecommendations recommendations = new VerifiedRecoveryRecommendations(
                f.provider, f.observations, new RecoveryVerifier(), f.clock);
        check(recommendations.recommend(f.incident, f.configuration).stream()
                        .map(RecoveryAction::getTargetServiceId).collect(Collectors.toList()).equals(List.of(f.api.getId())),
                "The incident recommendation query skips the recovered DB and promotes the failing API");
        f.interactor.previewRecovery(f.incident.getId(), apiAction.getId());
        RecoveryPreview preview = only(f.output.previews);
        check(preview.getContainerId().equals("api-container")
                        && preview.getDecision().getOperation().equals(Optional.of(RecoveryOperation.RESTART)),
                "Once DB has recovered, the still-failing API is offered for recovery");
        check(preview.getAction().getDescription().contains("have recovered"),
                "The recommendation explains why API became the target");

        f.interactor.executeRecovery(new RecoveryRequest(preview));
        check(f.containers.operations.equals(List.of("restart:api-container")) && only(f.output.attempts).isSucceeded(),
                "Confirming restarts API, not the recovered DB");
    }

    private static void targetThatRecoversAfterThePreviewIsNotRestarted() {
        Fixture f = new Fixture();
        f.interactor.previewRecovery(f.incident.getId(), f.dbAction().getId());
        check(only(f.output.previews).getDecision().getOperation().equals(Optional.of(RecoveryOperation.RESTART)),
                "Without checks since the failure, the preview offers a restart");
        f.observations.passes(f.db, START.plusSeconds(50), 3);
        f.confirm();
        check(f.containers.operations.isEmpty() && f.output.attempts.isEmpty(),
                "Health is re-assessed at execution, so a target that recovered meanwhile is not restarted");
    }

    private static void failingTargetIsStillRestarted() {
        Fixture f = new Fixture();
        f.observations.passes(f.db, START.plusSeconds(40), 3);
        f.observations.add(f.db, START.plusSeconds(50), HealthCheckResult.Outcome.FAIL);
        f.interactor.previewRecovery(f.incident.getId(), f.dbAction().getId());
        check(only(f.output.previews).getDecision().getExplanation().contains("latest check failed"),
                "The preview explains the restart with the failing check");
        f.confirm();
        check(f.containers.operations.equals(List.of("restart:db-container")) && only(f.output.attempts).isSucceeded(),
                "A target whose latest check failed is restarted");
    }

    private static void unreadableObservationsRunNoOperation() {
        Fixture f = new Fixture();
        f.observations.failure = new ObservationStoreException("disk unreadable", null);
        f.confirm();
        check(f.output.errors.equals(List.of("Observations could not be read: disk unreadable")),
                "The store error is presented");
        check(f.containers.inspections == 0 && f.containers.operations.isEmpty() && f.records.stored.isEmpty(),
                "Without the target's checks, nothing is inspected, run or recorded");

        f.interactor.previewRecovery(f.incident.getId(), f.dbAction().getId());
        check(f.output.errors.size() == 2 && f.output.previews.isEmpty(), "The preview reports the error instead");
    }

    /** API (HTTP) depends on DB (container db-container). Both fail; DB is the suspected cause. */
    private static void asynchronousRecoveryPreservesIncidentUpdates() {
        Fixture f = new Fixture();
        Queue<Runnable> incidentTasks = new ArrayDeque<>();
        Queue<Runnable> dockerTasks = new ArrayDeque<>();
        RecoveryInteractor recovery = f.interactor(f.incidents, f.records, incidentTasks::add, dockerTasks::add);
        CompletionStage<Void> preview = recovery.previewRecovery(f.incident.getId(), f.dbAction().getId());
        drain(incidentTasks);
        check(!preview.toCompletableFuture().isDone(), "Preview waits on Docker without locking the incident worker");
        drain(dockerTasks);
        drain(incidentTasks);
        RecoveryRequest request = new RecoveryRequest(only(f.output.previews));

        CompletionStage<Void> execution = recovery.executeRecovery(request);
        recovery.executeRecovery(request);
        drain(incidentTasks);
        drain(dockerTasks);
        drain(incidentTasks);
        check(!execution.toCompletableFuture().isDone() && dockerTasks.size() == 1,
                "Confirmation can dispatch only one Docker command");
        Incident updated = f.storedIncident().updateAssessment(Set.of(), List.of());
        incidentTasks.add(() -> f.incidents.save(updated));
        drain(incidentTasks);
        check(f.storedIncident() == updated && f.records.stored.isEmpty(),
                "An incident update can finish while Docker work is outstanding");
        drain(dockerTasks);
        drain(incidentTasks);
        check(execution.toCompletableFuture().isDone() && f.storedIncident() == updated
                        && f.records.stored.get(updated.getId()).getAttempts().size() == 1,
                "The completion waits for recording and preserves the latest incident");
    }

    private static void persistedRecoverySurvivesRestart() throws Exception {
        Fixture f = new Fixture();
        Path directory = Files.createTempDirectory("sentinel-uc4-flow");
        Path incidentsPath = directory.resolve("incidents");
        Path recoveryPath = directory.resolve("recovery");
        JsonIncidentRepository incidents = new JsonIncidentRepository(incidentsPath);
        incidents.save(f.incident);
        RecoveryInteractor recovery = f.interactor(incidents, new JsonRecoveryRecordRepository(recoveryPath),
                Runnable::run, Runnable::run);
        recovery.previewRecovery(f.incident.getId(), f.dbAction().getId());
        recovery.executeRecovery(new RecoveryRequest(only(f.output.previews)));
        Instant window = new JsonRecoveryRecordRepository(recoveryPath).findByIncidentId(f.incident.getId())
                .orElseThrow().getVerificationStartedAt().orElseThrow();
        f.observations.passes(f.db, window, 3);
        f.observations.passes(f.api, window, 2);
        f.clock.advance(60);
        RecoveryInteractor reopened = f.interactor(new JsonIncidentRepository(incidentsPath),
                new JsonRecoveryRecordRepository(recoveryPath), Runnable::run, Runnable::run);
        reopened.verifyResolution(f.incident.getId());
        check(new JsonIncidentRepository(incidentsPath).findById(f.incident.getId()).orElseThrow().getStatus()
                        == Incident.Status.RESOLVED && only(f.output.resolutions).getAttempts().size() == 1,
                "A reopened app can resolve using fresh observations and the persisted recovery window");
    }

    private static void drain(Queue<Runnable> tasks) {
        while (!tasks.isEmpty()) {
            tasks.remove().run();
        }
    }

    private static final class Fixture {
        final UUID systemId = UUID.randomUUID();
        final MonitoringPolicy dbPolicy = new MonitoringPolicy(Duration.ofSeconds(10), Duration.ofSeconds(2), 2, 3);
        final MonitoringPolicy apiPolicy = new MonitoringPolicy(Duration.ofSeconds(10), Duration.ofSeconds(2), 2, 2);
        final Service db = new Service(UUID.randomUUID(), systemId, "DB",
                MonitoringTarget.docker("db-container"), dbPolicy);
        final Service api;
        final Dependency edge;
        ConfigurationSnapshot configuration;
        final RecoveryRecommendationProvider provider = new RecoveryRecommendationProvider();

        final FakeIncidents incidents = new FakeIncidents();
        final FakeContainers containers = new FakeContainers();
        final FakeRecords records = new FakeRecords();
        final FakeObservations observations = new FakeObservations();
        final MutableClock clock = new MutableClock(START.plusSeconds(100));
        final RecordingOutput output = new RecordingOutput();
        final Incident incident;
        final RecoveryInteractor interactor;

        Fixture() {
            this(false);
        }

        /** API requires DB; API is an HTTP service unless dockerApi gives it a container too. */
        Fixture(boolean dockerApi) {
            api = new Service(UUID.randomUUID(), systemId, "API", dockerApi ? MonitoringTarget.docker("api-container")
                    : MonitoringTarget.http(URI.create("http://localhost:8080/health")), apiPolicy);
            edge = new Dependency(UUID.randomUUID(), api.getId(), db.getId());
            configuration = ConfigurationSnapshot.available(
                    new MonitoredSystem(systemId, "Shop", "", List.of(api, db), List.of(edge)));
            incident = incidentFailing(db, api);
            incidents.save(incident);
            interactor = interactor(incidents, records, Runnable::run, Runnable::run);
        }

        RecoveryInteractor interactor(IncidentRepository incidentRepository, RecoveryRecordRepository recordRepository,
                                      Executor incidentWorker, Executor dockerWorker) {
            ConfigurationReader configurations = systemId -> systemId.equals(this.systemId)
                    ? Optional.of(configuration) : Optional.empty();
            RecoveryVerifier verifier = new RecoveryVerifier();
            VerifiedRecoveryRecommendations recommendations = new VerifiedRecoveryRecommendations(
                    provider, observations, verifier, clock);
            return new RecoveryInteractor(incidentRepository, configurations, recommendations, containers, containers,
                    recordRepository, observations, new ContainerRecoveryPolicy(), verifier, clock, UUID::randomUUID,
                    output, incidentWorker, dockerWorker);
        }

        Incident incidentFailing(Service... services) {
            Incident result = null;
            List<UUID> evidence = new ArrayList<>();
            for (int index = 0; index < services.length; index++) {
                Instant at = START.plusSeconds(index);
                HealthCheckResult failed = new HealthCheckResult(UUID.randomUUID(), services[index].getId(), at,
                        HealthCheckResult.Outcome.FAIL, OptionalLong.empty(), "down");
                evidence.add(failed.getId());
                FailureEvent failure = new FailureEvent(UUID.randomUUID(), services[index].getId(), at,
                        List.of(failed));
                result = result == null
                        ? Incident.open(UUID.randomUUID(), systemId, START.plusSeconds(10), failure)
                        : result.recordFailure(failure);
            }
            result = result.startInvestigation(START.plusSeconds(20));
            boolean dbFailing = List.of(services).contains(db);
            DiagnosticFinding finding = new DiagnosticFinding(UUID.randomUUID(), "fixture", api.getId(),
                    START.plusSeconds(30), dbFailing ? Optional.of(db.getId()) : Optional.empty(), evidence,
                    dbFailing ? List.of(edge) : List.of(), "Fixture finding",
                    dbFailing ? List.of("Check the DB container logs") : List.of("Check the API logs"));
            return result.updateAssessment(Set.of(), List.of(finding));
        }

        Incident resolvedIncident() {
            Instant at = START.plusSeconds(90);
            Map<UUID, entity.ServiceHealth> health = new HashMap<>();
            for (UUID serviceId : incident.getRecoveryRequiredServiceIds()) {
                health.put(serviceId, entity.ServiceHealth.evaluated(entity.HealthStatus.HEALTHY,
                        new HealthCheckResult(UUID.randomUUID(), serviceId, at, HealthCheckResult.Outcome.PASS,
                                OptionalLong.empty(), "")));
            }
            return incident.resolve(at, START.plusSeconds(80), health);
        }

        RecoveryAction dbAction() {
            return provider.recommend(incident, configuration).get(0);
        }

        void preview() {
            interactor.previewRecovery(incident.getId(), dbAction().getId());
        }

        void confirm() {
            if (output.previews.isEmpty()) {
                preview();
            }
            if (!output.previews.isEmpty()) {
                RecoveryPreview preview = output.previews.remove(output.previews.size() - 1);
                interactor.executeRecovery(new RecoveryRequest(preview));
            }
        }

        Incident storedIncident() {
            return incidents.incidents.get(incident.getId());
        }
    }

    private static final class FakeIncidents implements IncidentRepository {
        final Map<UUID, Incident> incidents = new HashMap<>();
        IncidentDataException failure;

        @Override
        public Optional<Incident> findById(UUID incidentId) {
            failIfConfigured();
            return Optional.ofNullable(incidents.get(incidentId));
        }

        @Override
        public List<Incident> findBySystemId(UUID systemId) {
            failIfConfigured();
            return incidents.values().stream().filter(incident -> incident.getSystemId().equals(systemId))
                    .collect(Collectors.toList());
        }

        @Override
        public void save(Incident incident) {
            failIfConfigured();
            incidents.put(incident.getId(), incident);
        }

        private void failIfConfigured() {
            if (failure != null) {
                throw failure;
            }
        }
    }

    private static final class FakeContainers implements ContainerStateReader, ContainerOperator {
        ContainerState state = new ContainerState(ContainerState.Status.RUNNING, false);
        ContainerAccessException inspectFailure;
        ContainerAccessException operationFailure;
        RuntimeException unexpectedFailure;
        int inspections;
        String identity;
        final List<String> operations = new ArrayList<>();

        @Override
        public ContainerState inspect(String containerId) {
            inspections++;
            if (inspectFailure != null) {
                throw inspectFailure;
            }
            return new ContainerState(state.getStatus(), state.isOomKilled(), identity == null ? containerId : identity);
        }

        @Override
        public void start(String containerId) { operate("start", containerId); }

        @Override
        public void unpause(String containerId) { operate("unpause", containerId); }

        @Override
        public void restart(String containerId) { operate("restart", containerId); }

        private void operate(String operation, String containerId) {
            operations.add(operation + ":" + containerId);
            if (operationFailure != null) {
                throw operationFailure;
            }
            if (unexpectedFailure != null) {
                throw unexpectedFailure;
            }
        }
    }

    private static final class FakeRecords implements RecoveryRecordRepository {
        final Map<UUID, IncidentRecoveryRecord> stored = new HashMap<>();
        IncidentDataException failure;

        @Override
        public Optional<IncidentRecoveryRecord> findByIncidentId(UUID incidentId) {
            return Optional.ofNullable(stored.get(incidentId));
        }

        @Override
        public void save(IncidentRecoveryRecord record) {
            if (failure != null) {
                throw failure;
            }
            stored.put(record.getIncidentId(), record);
        }
    }

    /** In-memory UC2 store: full history per service, oldest first, as ObservationStore documents. */
    private static final class FakeObservations implements ObservationStore {
        final List<HealthCheckResult> results = new ArrayList<>();
        ObservationStoreException failure;

        void add(Service service, Instant at, HealthCheckResult.Outcome outcome) {
            results.add(new HealthCheckResult(UUID.randomUUID(), service.getId(), at, outcome,
                    OptionalLong.empty(), outcome.name()));
        }

        void passes(Service service, Instant from, int count) {
            for (int i = 1; i <= count; i++) {
                add(service, from.plusSeconds(i), HealthCheckResult.Outcome.PASS);
            }
        }

        @Override
        public void save(HealthCheckResult result) {
            results.add(result);
        }

        @Override
        public List<HealthCheckResult> findByServiceId(UUID serviceId) {
            if (failure != null) {
                throw failure;
            }
            return results.stream().filter(result -> result.getServiceId().equals(serviceId))
                    .sorted(Comparator.comparing(HealthCheckResult::getTimestamp))
                    .collect(Collectors.toList());
        }
    }

    private static final class RecordingOutput implements RecoveryOutputBoundary {
        final List<RecoveryPreview> previews = new ArrayList<>();
        final List<RecoveryAttemptResult> attempts = new ArrayList<>();
        final List<ManualRecoveryGuidance> manual = new ArrayList<>();
        final List<VerificationResult> verifications = new ArrayList<>();
        final List<ResolutionResult> resolutions = new ArrayList<>();
        final List<String> errors = new ArrayList<>();

        @Override
        public void presentPreview(RecoveryPreview preview) { previews.add(preview); }

        @Override
        public void presentAttempt(RecoveryAttemptResult result) { attempts.add(result); }

        @Override
        public void presentManualSteps(ManualRecoveryGuidance guidance) { manual.add(guidance); }

        @Override
        public void presentVerification(VerificationResult result) { verifications.add(result); }

        @Override
        public void presentResolved(ResolutionResult result) { resolutions.add(result); }

        @Override
        public void presentHistory(IncidentRecoveryRecord record) { }

        @Override
        public void presentError(String message) { errors.add(message); }
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(long seconds) {
            now = now.plusSeconds(seconds);
        }

        @Override
        public ZoneId getZone() { return ZoneOffset.UTC; }

        @Override
        public Clock withZone(ZoneId zone) { return this; }

        @Override
        public Instant instant() { return now; }
    }

    private static List<UUID> ids(List<ServiceVerification> services) {
        return services.stream().map(ServiceVerification::getServiceId).collect(Collectors.toList());
    }

    private static <T> T only(List<T> values) {
        check(values.size() == 1, "Expected exactly one value but found " + values.size());
        return values.get(0);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
