package use_case.recover_service;

import java.util.Objects;
import java.util.UUID;

/**
 * Input data for executing a recommended recovery action, built by the controller after the developer responds.
 * Only IDs are accepted: the interactor re-derives the action from the incident's current recommendations and
 * chooses the container operation from live state, so a stale or altered action cannot be executed.
 * An unconfirmed request is rejected without contacting the container engine.
 */
public final class RecoveryRequest {
    private final UUID incidentId;
    private final UUID actionId;
    private final boolean confirmed;

    public RecoveryRequest(UUID incidentId, UUID actionId, boolean confirmed) {
        this.incidentId = Objects.requireNonNull(incidentId, "incidentId");
        this.actionId = Objects.requireNonNull(actionId, "actionId");
        this.confirmed = confirmed;
    }

    public UUID getIncidentId() { return incidentId; }
    public UUID getActionId() { return actionId; }
    /** True only when the developer explicitly confirmed this action. */
    public boolean isConfirmed() { return confirmed; }
}
