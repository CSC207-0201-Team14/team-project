package use_case.investigate_incident;

import entity.Incident;

import java.util.ArrayList;
import java.util.List;

/** Records the latest value of each presentation, plus every error, for UC3 checks. */
final class RecordingOutput implements IncidentOutputBoundary {
    Incident processed;
    List<IncidentSummary> summaries;
    IncidentDetails details;
    String error;
    final List<String> errors = new ArrayList<>();

    @Override
    public void presentProcessed(Incident incident) { processed = incident; }

    @Override
    public void presentIncidents(List<IncidentSummary> incidents) { summaries = incidents; }

    @Override
    public void presentDetails(IncidentDetails result) { details = result; }

    @Override
    public void presentError(String message) {
        error = message;
        errors.add(message);
    }
}
