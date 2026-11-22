package use_case.recover_service;

import java.util.Optional;

/** Run explicitly after mvn test-compile, following the existing test convention. */
public final class ContainerRecoveryPolicyChecks {
    private static final ContainerRecoveryPolicy POLICY = new ContainerRecoveryPolicy();

    public static void main(String[] args) {
        choosesOperationFromState();
        refusesUnsuitableStates();
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
        RecoveryDecision missing = POLICY.select(ContainerState.missing());
        check(!missing.isSupported() && missing.getExplanation().contains("does not exist"),
                "A missing container is not recovered and the reason is explained");
        check(POLICY.select(new ContainerState(ContainerState.Status.EXITED, true)).getExplanation().contains("memory"),
                "A memory kill is explained");
    }

    private static void expect(ContainerState.Status status, boolean oomKilled, RecoveryOperation operation) {
        RecoveryDecision decision = POLICY.select(new ContainerState(status, oomKilled));
        check(decision.getOperation().equals(Optional.ofNullable(operation)),
                status + (oomKilled ? " (OOM)" : "") + " should map to " + operation);
        check(!decision.getExplanation().isBlank(), "Every decision is explained");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
