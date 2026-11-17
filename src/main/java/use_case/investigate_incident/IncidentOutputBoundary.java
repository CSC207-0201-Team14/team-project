package use_case.investigate_incident;

import entity.Incident;

/** Presenters marshal Swing updates onto the EDT. */
public interface IncidentOutputBoundary {
    void presentProcessed(Incident incident);

    void presentDetails(IncidentDetails details);

    void presentError(String message);
}
