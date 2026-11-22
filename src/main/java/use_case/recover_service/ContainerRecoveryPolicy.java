package use_case.recover_service;

import java.util.Objects;

/**
 * Chooses the container operation from live state. Pure and deterministic; it never contacts the engine.
 * States that suggest a deeper problem (restart loops, memory kills, missing or dead containers)
 * are left to the developer rather than recovered automatically.
 */
public final class ContainerRecoveryPolicy {

    public RecoveryDecision select(ContainerState state) {
        Objects.requireNonNull(state, "state");
        if (state.getStatus() == ContainerState.Status.MISSING) {
            return RecoveryDecision.unsuitable("The container does not exist, so there is nothing to recover.");
        }
        if (state.isOomKilled()) {
            return RecoveryDecision.unsuitable("The container was killed for exceeding its memory limit; "
                    + "restarting it would likely fail again until its memory use or limit changes.");
        }
        switch (state.getStatus()) {
            case CREATED:
            case EXITED:
                return RecoveryDecision.perform(RecoveryOperation.START,
                        "The container is stopped, so it will be started.");
            case PAUSED:
                return RecoveryDecision.perform(RecoveryOperation.UNPAUSE,
                        "The container is paused, so it will be unpaused.");
            case RUNNING:
                return RecoveryDecision.perform(RecoveryOperation.RESTART,
                        "The container is running but its service is failing, so it will be restarted.");
            case RESTARTING:
                return RecoveryDecision.unsuitable("The container is already restarting; "
                        + "another operation could interfere with it.");
            case DEAD:
                return RecoveryDecision.unsuitable("The container is dead and cannot be restarted safely.");
            case REMOVING:
                return RecoveryDecision.unsuitable("The container is being removed.");
            default:
                return RecoveryDecision.unsuitable("The container state is not supported for automatic recovery.");
        }
    }
}
