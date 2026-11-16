package entity;

import java.util.Objects;
import java.util.UUID;

public final class RecoveryAction {
    private final UUID id;
    private final UUID incidentId;
    private final UUID targetServiceId;
    private final ActionType actionType;
    private final String description;

    public RecoveryAction(UUID id, UUID incidentId, UUID targetServiceId, ActionType actionType, String description) {
        this.id = Objects.requireNonNull(id, "id");
        this.incidentId = Objects.requireNonNull(incidentId, "incidentId");
        this.targetServiceId = Objects.requireNonNull(targetServiceId, "targetServiceId");
        this.actionType = Objects.requireNonNull(actionType, "actionType");
        this.description = Objects.requireNonNull(description, "description");
        if (description.isBlank()) {
            throw new IllegalArgumentException("Action description must not be blank");
        }
    }

    public enum ActionType { START_DOCKER_CONTAINER }

    public boolean requiresConfirmation() {
        return true;
    }

    public UUID getId() {
        return id;
    }

    public UUID getIncidentId() {
        return incidentId;
    }

    public UUID getTargetServiceId() {
        return targetServiceId;
    }

    public ActionType getActionType() {
        return actionType;
    }

    public String getDescription() {
        return description;
    }
}
