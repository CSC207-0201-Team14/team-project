package interface_adapter.investigate_incident;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Display text for one opened incident, one field per section of the details panel.
 * The presenter writes every section, including what is unknown or unavailable, so the view only lays it out.
 */
public final class IncidentDetailsState {
    private final UUID incidentId;
    private final String title;
    private final String status;
    private final String timeline;
    private final String suspectedCause;
    private final String recordedFailures;
    private final String evidence;
    private final String potentialImpact;
    private final String nextSteps;
    private final List<String> recommendedActions;
    private final String recommendationNote;
    private final String history;

    public IncidentDetailsState(UUID incidentId, String title, String status, String timeline,
                                String suspectedCause,
                                String recordedFailures, String evidence, String potentialImpact, String nextSteps,
                                List<String> recommendedActions, String recommendationNote, String history) {
        this.incidentId = Objects.requireNonNull(incidentId, "incidentId");
        this.title = Objects.requireNonNull(title, "title");
        this.status = Objects.requireNonNull(status, "status");
        this.timeline = Objects.requireNonNull(timeline, "timeline");
        this.suspectedCause = Objects.requireNonNull(suspectedCause, "suspectedCause");
        this.recordedFailures = Objects.requireNonNull(recordedFailures, "recordedFailures");
        this.evidence = Objects.requireNonNull(evidence, "evidence");
        this.potentialImpact = Objects.requireNonNull(potentialImpact, "potentialImpact");
        this.nextSteps = Objects.requireNonNull(nextSteps, "nextSteps");
        this.recommendedActions = List.copyOf(recommendedActions);
        this.recommendationNote = Objects.requireNonNull(recommendationNote, "recommendationNote");
        this.history = Objects.requireNonNull(history, "history");
    }

    public UUID getIncidentId() { return incidentId; }
    public String getTitle() { return title; }
    /** The same status name the incident list shows, such as "Investigating". */
    public String getStatus() { return status; }
    /** When the incident was opened, investigated and resolved. */
    public String getTimeline() { return timeline; }
    public String getSuspectedCause() { return suspectedCause; }
    public String getRecordedFailures() { return recordedFailures; }
    public String getEvidence() { return evidence; }
    public String getPotentialImpact() { return potentialImpact; }
    public String getNextSteps() { return nextSteps; }

    /** Descriptions of the containers UC3 recommends recovering; empty when only manual steps apply. */
    public List<String> getRecommendedActions() { return recommendedActions; }

    /** Explains the recommendations, or that no recovery action is supported (extension 5a). */
    public String getRecommendationNote() { return recommendationNote; }

    public String getHistory() { return history; }
}
