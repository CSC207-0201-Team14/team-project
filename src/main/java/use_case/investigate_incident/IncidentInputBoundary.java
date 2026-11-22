package use_case.investigate_incident;

import java.util.UUID;

/**
 * Invoke off the Swing EDT. Calls are synchronous and must be serialized by the caller;
 * wrap the interactor in SerializedIncidentInputBoundary when several threads share it.
 */
public interface IncidentInputBoundary {
    /**
     * Correlates, diagnoses and saves the failure, then notifies the output boundary.
     * Returns RETRYABLE on storage access failures; redelivering the same event ID is safe
     * because a stored event ID reuses its incident.
     */
    FailureProcessingResult processFailure(FailureInput input);

    /** Presents the system's incident history, newest opening first, without changing incident status. */
    void listIncidents(UUID systemId);

    /**
     * Moves an OPEN incident to INVESTIGATING and saves it before presenting details.
     * Other statuses are unchanged. Never resolves the incident or executes recovery.
     */
    void openIncident(UUID incidentId);
}
