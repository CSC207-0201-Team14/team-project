package use_case.investigate_incident;

import entity.HealthCheckResult;
import entity.Incident;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Read-only output using immutable domain values. Incident carries status, failures,
 * current/history findings, dependency evidence, potential impact and manual next steps.
 * Missing observations are explicit so a presenter cannot imply complete evidence.
 */
public final class IncidentDetails {
    private final Incident incident;
    private final Map<UUID, HealthCheckResult> observations;
    private final Set<UUID> missingObservationIds;

    public IncidentDetails(Incident incident, Map<UUID, HealthCheckResult> observations,
                           Set<UUID> missingObservationIds) {
        this.incident = Objects.requireNonNull(incident, "incident");
        this.observations = Map.copyOf(observations);
        this.missingObservationIds = Set.copyOf(missingObservationIds);
    }

    public Incident getIncident() { return incident; }
    public Map<UUID, HealthCheckResult> getObservations() { return observations; }
    public Set<UUID> getMissingObservationIds() { return missingObservationIds; }
}
