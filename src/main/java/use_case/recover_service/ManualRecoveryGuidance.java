package use_case.recover_service;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Output when Sentinel will not recover automatically: why, and the next steps recorded in the incident's
 * active findings. An empty list means the findings recorded no steps. The incident remains unresolved;
 * after acting externally the developer can begin manual verification.
 */
public final class ManualRecoveryGuidance {
    private final UUID incidentId;
    private final String reason;
    private final List<String> nextSteps;

    public ManualRecoveryGuidance(UUID incidentId, String reason, List<String> nextSteps) {
        this.incidentId = Objects.requireNonNull(incidentId, "incidentId");
        this.reason = Objects.requireNonNull(reason, "reason");
        this.nextSteps = List.copyOf(nextSteps);
    }

    public UUID getIncidentId() { return incidentId; }
    public String getReason() { return reason; }
    public List<String> getNextSteps() { return nextSteps; }
}
