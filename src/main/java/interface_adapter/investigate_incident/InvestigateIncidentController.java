package interface_adapter.investigate_incident;

import use_case.investigate_incident.IncidentInputBoundary;

import java.util.Objects;
import java.util.UUID;

/**
 * Maps incident panel actions to UC3 requests. The serialized input boundary queues each request on the
 * incident worker and returns at once, so calling these methods from the EDT never blocks the UI.
 */
public final class InvestigateIncidentController {
    private final IncidentInputBoundary input;

    public InvestigateIncidentController(IncidentInputBoundary input) {
        this.input = Objects.requireNonNull(input, "input");
    }

    public void listIncidents(UUID systemId) {
        input.listIncidents(Objects.requireNonNull(systemId, "systemId"));
    }

    public void openIncident(UUID incidentId) {
        input.openIncident(Objects.requireNonNull(incidentId, "incidentId"));
    }
}
