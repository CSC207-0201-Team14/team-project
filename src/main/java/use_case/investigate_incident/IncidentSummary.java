package use_case.investigate_incident;

import entity.Incident;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** The information needed to identify and select an incident in a system's history. */
public final class IncidentSummary {
    private final UUID incidentId;
    private final Instant openedAt;
    private final Incident.Status status;
    private final Set<UUID> failingServiceIds;

    public IncidentSummary(Incident incident) {
        this.incidentId = incident.getId();
        this.openedAt = incident.getOpenedAt();
        this.status = incident.getStatus();
        this.failingServiceIds = incident.getFailingServiceIds();
    }

    public UUID getIncidentId() { return incidentId; }
    public Instant getOpenedAt() { return openedAt; }
    public Incident.Status getStatus() { return status; }

    /** Services with a recorded failure in this incident, including those that have since recovered. */
    public Set<UUID> getFailingServiceIds() { return failingServiceIds; }
}
