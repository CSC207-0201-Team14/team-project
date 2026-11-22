package use_case.investigate_incident;

import entity.Incident;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** The information needed to identify and select an incident in a system's history. */
public final class IncidentSummary {
    private final UUID incidentId;
    private final Instant openedAt;
    private final Incident.Status status;
    private final Set<UUID> failingServiceIds;
    private final Map<UUID, String> serviceNames;

    /** Names come from the current roster; services no longer configured have no entry. */
    public IncidentSummary(Incident incident, Map<UUID, String> serviceNames) {
        this.incidentId = incident.getId();
        this.openedAt = incident.getOpenedAt();
        this.status = incident.getStatus();
        this.failingServiceIds = incident.getFailingServiceIds();
        this.serviceNames = Map.copyOf(serviceNames);
    }

    public UUID getIncidentId() { return incidentId; }
    public Instant getOpenedAt() { return openedAt; }
    public Incident.Status getStatus() { return status; }

    /** Services with a recorded failure in this incident, including those that have since recovered. */
    public Set<UUID> getFailingServiceIds() { return failingServiceIds; }

    /** Configured names keyed by service ID; look up failing services here and fall back to the ID. */
    public Map<UUID, String> getServiceNames() { return serviceNames; }
}
