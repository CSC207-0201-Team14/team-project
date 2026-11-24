package use_case.recover_service;

import entity.RecoveryAction;
import entity.RecoveryAttempt;
import java.util.Objects;
import java.util.Optional;

/**
 * Step 3 output: the recorded attempt and what it did. The state and operation are empty when the
 * container could not be inspected, in which case no operation was performed.
 */
public final class RecoveryAttemptResult {
    private final RecoveryAction action;
    private final String containerId;
    private final ContainerState stateBefore;
    private final RecoveryOperation operation;
    private final RecoveryAttempt attempt;

    public RecoveryAttemptResult(RecoveryAction action, String containerId, Optional<ContainerState> stateBefore,
                                 Optional<RecoveryOperation> operation, RecoveryAttempt attempt) {
        this.action = Objects.requireNonNull(action, "action");
        this.containerId = Objects.requireNonNull(containerId, "containerId");
        this.stateBefore = stateBefore.orElse(null);
        this.operation = operation.orElse(null);
        this.attempt = Objects.requireNonNull(attempt, "attempt");
        if (!attempt.getActionId().equals(action.getId())) {
            throw new IllegalArgumentException("The attempt must belong to the action");
        }
    }

    public RecoveryAction getAction() { return action; }
    public String getContainerId() { return containerId; }
    public Optional<ContainerState> getStateBefore() { return Optional.ofNullable(stateBefore); }
    public Optional<RecoveryOperation> getOperation() { return Optional.ofNullable(operation); }
    public RecoveryAttempt getAttempt() { return attempt; }

    public boolean isSucceeded() {
        return attempt.getOutcome() == RecoveryAttempt.Outcome.SUCCEEDED;
    }
}
