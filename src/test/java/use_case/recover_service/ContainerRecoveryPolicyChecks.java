package use_case.recover_service;

import entity.HealthCheckResult;
import entity.HealthStatus;
import entity.ServiceHealth;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

/** Run explicitly after mvn test-compile, following the existing test convention. */
public final class ContainerRecoveryPolicyChecks {
    private static final ContainerRecoveryPolicy POLICY = new ContainerRecoveryPolicy();
    private static final UUID SERVICE = UUID.randomUUID();

    public static void main(String[] args) {
        choosesOperationFromState();
        refusesUnsuitableStates();
        skipsRestartWhenTheTargetHasRecovered();
        restartsWhenRecoveryIsNotConfirmed();
        healthDoesNotAffectStoppedOrUnsuitableContainers();
        System.out.println("UC4 container recovery policy checks passed");
    }

    private static void choosesOperationFromState() {
        expect(ContainerState.Status.EXITED, false, RecoveryOperation.START);
        expect(ContainerState.Status.CREATED, false, RecoveryOperation.START);
        expect(ContainerState.Status.PAUSED, false, RecoveryOperation.UNPAUSE);
        expect(ContainerState.Status.RUNNING, false, RecoveryOperation.RESTART);
    }

    private static void refusesUnsuitableStates() {
        expect(ContainerState.Status.RESTARTING, false, null);
        expect(ContainerState.Status.DEAD, false, null);
        expect(ContainerState.Status.REMOVING, false, null);
        expect(ContainerState.Status.EXITED, true, null);
        expect(ContainerState.Status.RUNNING, true, null);
        RecoveryDecision missing = POLICY.select(ContainerState.missing(), failing());
        check(!missing.isSupported() && missing.getKind() == RecoveryDecision.Kind.UNSUITABLE
                && missing.getExplanation().contains("does not exist"),
                "A missing container is not recovered and the reason is explained");
        check(POLICY.select(new ContainerState(ContainerState.Status.EXITED, true), failing())
                .getExplanation().contains("memory"), "A memory kill is explained");
    }

    private static void skipsRestartWhenTheTargetHasRecovered() {
        RecoveryDecision decision = POLICY.select(running(), recovered());
        check(decision.getKind() == RecoveryDecision.Kind.NOT_NEEDED && decision.getOperation().isEmpty(),
                "A running container whose service met its recovery threshold is not restarted");
        check(decision.getExplanation().contains("3 consecutive checks")
                && decision.getExplanation().contains("will not be restarted"),
                "The explanation cites the passing checks");
    }

    private static void restartsWhenRecoveryIsNotConfirmed() {
        RecoveryDecision failing = POLICY.select(running(), failing());
        check(failing.getOperation().equals(Optional.of(RecoveryOperation.RESTART))
                && failing.getExplanation().contains("latest check failed"), "A failing service is restarted");

        RecoveryDecision partial = POLICY.select(running(), health(HealthStatus.UNKNOWN,
                HealthCheckResult.Outcome.PASS, 2));
        check(partial.getOperation().equals(Optional.of(RecoveryOperation.RESTART))
                && partial.getExplanation().contains("latest 2 consecutive checks, fewer than the 3"),
                "Passes below the threshold still restart, and say how far recovery got");

        RecoveryDecision error = POLICY.select(running(), health(HealthStatus.UNKNOWN,
                HealthCheckResult.Outcome.MONITORING_ERROR, 0));
        check(error.getOperation().equals(Optional.of(RecoveryOperation.RESTART))
                && error.getExplanation().contains("could not be completed")
                && !error.getExplanation().contains("is failing"),
                "A monitoring error restarts without claiming the service is failing");

        RecoveryDecision unobserved = POLICY.select(running(),
                new ServiceVerification(SERVICE, ServiceHealth.unobserved(SERVICE), 0, 3, "none"));
        check(unobserved.getOperation().equals(Optional.of(RecoveryOperation.RESTART))
                && unobserved.getExplanation().contains("health is unknown")
                && !unobserved.getExplanation().contains("is failing"),
                "No checks restarts without claiming the service is failing");
    }

    private static void healthDoesNotAffectStoppedOrUnsuitableContainers() {
        check(POLICY.select(new ContainerState(ContainerState.Status.EXITED, false), recovered()).getOperation()
                .equals(Optional.of(RecoveryOperation.START)), "A stopped container is started whatever its checks say");
        check(POLICY.select(new ContainerState(ContainerState.Status.PAUSED, false), recovered()).getOperation()
                .equals(Optional.of(RecoveryOperation.UNPAUSE)), "A paused container is unpaused whatever its checks say");
        check(POLICY.select(new ContainerState(ContainerState.Status.RUNNING, true), recovered()).getKind()
                == RecoveryDecision.Kind.UNSUITABLE, "A memory kill stays unsuitable even after passing checks");
    }

    private static void expect(ContainerState.Status status, boolean oomKilled, RecoveryOperation operation) {
        RecoveryDecision decision = POLICY.select(new ContainerState(status, oomKilled), failing());
        check(decision.getOperation().equals(Optional.ofNullable(operation)),
                status + (oomKilled ? " (OOM)" : "") + " should map to " + operation);
        check(decision.getKind() == (operation == null ? RecoveryDecision.Kind.UNSUITABLE
                : RecoveryDecision.Kind.PERFORM), "The kind matches whether an operation runs");
        check(!decision.getExplanation().isBlank(), "Every decision is explained");
    }

    private static ContainerState running() {
        return new ContainerState(ContainerState.Status.RUNNING, false);
    }

    private static ServiceVerification recovered() {
        return health(HealthStatus.HEALTHY, HealthCheckResult.Outcome.PASS, 3);
    }

    private static ServiceVerification failing() {
        return health(HealthStatus.UNHEALTHY, HealthCheckResult.Outcome.FAIL, 0);
    }

    private static ServiceVerification health(HealthStatus status, HealthCheckResult.Outcome latest, int passes) {
        HealthCheckResult result = new HealthCheckResult(UUID.randomUUID(), SERVICE,
                Instant.parse("2026-01-01T00:00:00Z"), latest, OptionalLong.empty(), latest.name());
        return new ServiceVerification(SERVICE, ServiceHealth.evaluated(status, result), passes, 3, "fixture");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
