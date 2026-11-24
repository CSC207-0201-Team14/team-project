package interface_adapter.investigate_incident;

import java.util.Objects;
import java.util.UUID;

/** One row of the incident list: the ID to open, plus display text prepared by the presenter. */
public final class IncidentListItem {
    private final UUID incidentId;
    private final String status;
    private final String label;

    public IncidentListItem(UUID incidentId, String status, String label) {
        this.incidentId = Objects.requireNonNull(incidentId, "incidentId");
        this.status = Objects.requireNonNull(status, "status");
        this.label = Objects.requireNonNull(label, "label");
    }

    public UUID getIncidentId() { return incidentId; }
    public String getStatus() { return status; }
    public String getLabel() { return label; }

    @Override
    public String toString() {
        return label;
    }
}
