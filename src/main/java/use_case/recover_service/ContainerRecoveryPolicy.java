package use_case.recover_service;

import entity.HealthCheckResult;
import java.util.Objects;

/**
 * Chooses the container operation from live state and, for a running container, the target service's health.
 * Pure and deterministic; it never contacts the engine or reads observations.
 * States that suggest a deeper problem (restart loops, memory kills, missing or dead containers)
 * are left to the developer rather than recovered automatically.
 * <p>
 * A stopped or paused container is itself evidence of a problem, so health does not affect START or UNPAUSE.
 * A running container is not: UC3 recommends targets from recorded failures, which remain after a service
 * recovers. RESTART is therefore skipped when the target already meets its recovery threshold. When its health
 * is unknown or only partly recovered, RESTART is still offered, and the explanation says what the checks show
 * rather than claiming the service is failing.
 */
public final class ContainerRecoveryPolicy {

    /**
     * @param state the container's live state
     * @param targetHealth the target's health since its latest recorded failure
     *                     (see RecoveryVerifier.assessSinceLatestFailure)
     */
    public RecoveryDecision select(ContainerState state, ServiceVerification targetHealth) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(targetHealth, "targetHealth");
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
                return selectForRunning(targetHealth);
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

    private static RecoveryDecision selectForRunning(ServiceVerification health) {
        int passes = health.getConsecutivePasses();
        int required = health.getRequiredPasses();
        if (health.isVerified()) {
            return RecoveryDecision.notNeeded("The container is running and its service has passed " + passes
                    + " consecutive checks since its last recorded failure, meeting its recovery threshold of "
                    + required + ", so it will not be restarted. Other services in this incident may still need "
                    + "attention.");
        }
        HealthCheckResult.Outcome latest = health.getHealth().getLatestResult()
                .map(HealthCheckResult::getOutcome).orElse(null);
        if (latest == HealthCheckResult.Outcome.FAIL) {
            return RecoveryDecision.perform(RecoveryOperation.RESTART,
                    "The container is running but its latest check failed, so it will be restarted.");
        }
        if (latest == HealthCheckResult.Outcome.PASS) {
            return RecoveryDecision.perform(RecoveryOperation.RESTART, "The container is running and its service "
                    + "has passed its latest " + passes + " consecutive checks, fewer than the " + required
                    + " needed to confirm recovery, so it will be restarted.");
        }
        if (latest == HealthCheckResult.Outcome.MONITORING_ERROR) {
            return RecoveryDecision.perform(RecoveryOperation.RESTART, "The container is running but its latest "
                    + "check could not be completed, so its current health is unknown; it will be restarted.");
        }
        return RecoveryDecision.perform(RecoveryOperation.RESTART, "The container is running but no check has been "
                + "recorded since its last recorded failure, so its current health is unknown; it will be restarted.");
    }
}
