package use_case.investigate_incident;

import entity.HealthCheckResult;
import entity.Incident;
import entity.RecoveryAction;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Read-only output using immutable domain values. Incident carries status, failures,
 * current/history findings, dependency evidence, potential impact and manual next steps.
 * Missing observations are explicit so a presenter cannot imply complete evidence.
 * Recommended actions are suggestions only; an empty list means manual next steps apply.
 */
public final class IncidentDetails {
    private final Incident incident;
    private final Map<UUID, HealthCheckResult> observations;
    private final Set<UUID> missingObservationIds;
    private final List<RecoveryAction> recommendedActions;
    private final Map<UUID, String> serviceNames;

    public IncidentDetails(Incident incident, Map<UUID, HealthCheckResult> observations,
                           Set<UUID> missingObservationIds, List<RecoveryAction> recommendedActions,
                           Map<UUID, String> serviceNames) {
        this.incident = Objects.requireNonNull(incident, "incident");
        this.observations = Map.copyOf(observations);
        this.missingObservationIds = Set.copyOf(missingObservationIds);
        this.recommendedActions = List.copyOf(recommendedActions);
        this.serviceNames = Map.copyOf(serviceNames);
    }

    public Incident getIncident() { return incident; }
    public Map<UUID, HealthCheckResult> getObservations() { return observations; }
    public Set<UUID> getMissingObservationIds() { return missingObservationIds; }
    public List<RecoveryAction> getRecommendedActions() { return recommendedActions; }

    /** Names from the system's service roster at the time the details were opened. */
    public Map<UUID, String> getServiceNames() { return serviceNames; }
}
