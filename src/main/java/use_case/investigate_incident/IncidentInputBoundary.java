package use_case.investigate_incident;

import java.util.UUID;

/** Invoke off the Swing EDT. Calls are synchronous and must be serialized by the caller. */
public interface IncidentInputBoundary {
    void processFailure(FailureInput input);

    /** Reads details without changing incident status or executing recovery. */
    void openIncident(UUID incidentId);
}
