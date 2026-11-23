package use_case.recover_service;

import entity.RecoveryAction;
import java.util.List;
import java.util.Objects;

/**
 * Step 1 output: the target container, its current state, the target service's health since its latest
 * recorded failure, and the decision they determine. When no operation is chosen, the decision explains why
 * and the manual next steps apply instead.
 */
public final class RecoveryPreview {
    private final RecoveryAction action;
    private final String serviceName;
    private final String containerId;
    private final ContainerState state;
    private final ServiceVerification targetHealth;
    private final RecoveryDecision decision;
    private final List<String> manualSteps;

    public RecoveryPreview(RecoveryAction action, String serviceName, String containerId, ContainerState state,
                           ServiceVerification targetHealth, RecoveryDecision decision, List<String> manualSteps) {
        this.action = Objects.requireNonNull(action, "action");
        this.serviceName = Objects.requireNonNull(serviceName, "serviceName");
        this.containerId = Objects.requireNonNull(containerId, "containerId");
        this.state = Objects.requireNonNull(state, "state");
        this.targetHealth = Objects.requireNonNull(targetHealth, "targetHealth");
        this.decision = Objects.requireNonNull(decision, "decision");
        this.manualSteps = List.copyOf(manualSteps);
        if (!targetHealth.getServiceId().equals(action.getTargetServiceId())) {
            throw new IllegalArgumentException("The target health must describe the action's target service");
        }
    }

    public RecoveryAction getAction() { return action; }
    public String getServiceName() { return serviceName; }
    public String getContainerId() { return containerId; }
    public ContainerState getState() { return state; }
    /** Checks since the target's latest recorded failure, against its recovery threshold. */
    public ServiceVerification getTargetHealth() { return targetHealth; }
    public RecoveryDecision getDecision() { return decision; }
    public List<String> getManualSteps() { return manualSteps; }
}
