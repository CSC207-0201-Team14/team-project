package use_case.investigate_incident;

import entity.Incident;
import java.util.List;

/** Presenters marshal Swing updates onto the EDT. */
public interface IncidentOutputBoundary {
    void presentProcessed(Incident incident);

    void presentIncidents(List<IncidentSummary> incidents);

    void presentDetails(IncidentDetails details);

    void presentError(String message);
}
