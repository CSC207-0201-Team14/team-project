package use_case.recover_service;

import entity.RecoveryAction;
import java.util.List;
import java.util.Objects;

/**
 * Step 1 output: the target container, its current state, and the operation that state determines.
 * When the decision is unsupported, the manual next steps apply instead.
 */
public final class RecoveryPreview {
    private final RecoveryAction action;
    private final String serviceName;
    private final String containerId;
    private final ContainerState state;
    private final RecoveryDecision decision;
    private final List<String> manualSteps;

    public RecoveryPreview(RecoveryAction action, String serviceName, String containerId, ContainerState state,
                           RecoveryDecision decision, List<String> manualSteps) {
        this.action = Objects.requireNonNull(action, "action");
        this.serviceName = Objects.requireNonNull(serviceName, "serviceName");
        this.containerId = Objects.requireNonNull(containerId, "containerId");
        this.state = Objects.requireNonNull(state, "state");
        this.decision = Objects.requireNonNull(decision, "decision");
        this.manualSteps = List.copyOf(manualSteps);
    }

    public RecoveryAction getAction() { return action; }
    public String getServiceName() { return serviceName; }
    public String getContainerId() { return containerId; }
    public ContainerState getState() { return state; }
    public RecoveryDecision getDecision() { return decision; }
    public List<String> getManualSteps() { return manualSteps; }
}
