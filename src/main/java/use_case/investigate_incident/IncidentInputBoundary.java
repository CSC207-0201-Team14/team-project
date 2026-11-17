package use_case.investigate_incident;

import java.util.UUID;

/** Invoke off the Swing EDT. Calls are synchronous and must be serialized by the caller. */
public interface IncidentInputBoundary {
    void processFailure(FailureInput input);

    /**
     * Moves an OPEN incident to INVESTIGATING and saves it before presenting details.
     * Other statuses are unchanged. Never resolves the incident or executes recovery.
     */
    void openIncident(UUID incidentId);
}
