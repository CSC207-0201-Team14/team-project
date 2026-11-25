package interface_adapter.investigate_incident;

import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * State of the incident panel. The presenter updates it on the EDT and the view listens for changes
 * (Observer), so neither the presenter nor the use case knows about Swing components.
 */
public final class IncidentViewModel {
    public static final String INCIDENTS = "incidents";
    public static final String DETAILS = "details";
    public static final String ERROR = "error";
    /** Fired with the system ID when monitoring has created or updated one of its incidents. */
    public static final String PROCESSED = "processed";

    private final PropertyChangeSupport support = new PropertyChangeSupport(this);
    private List<IncidentListItem> incidents = List.of();
    private IncidentDetailsState details;
    private String error = "";

    public List<IncidentListItem> getIncidents() { return incidents; }
    public Optional<IncidentDetailsState> getDetails() { return Optional.ofNullable(details); }
    public String getError() { return error; }

    public void setIncidents(List<IncidentListItem> incidents) {
        this.incidents = List.copyOf(incidents);
        support.firePropertyChange(INCIDENTS, null, this.incidents);
    }

    public void setDetails(IncidentDetailsState details) {
        this.details = Objects.requireNonNull(details, "details");
        support.firePropertyChange(DETAILS, null, details);
    }

    public void setError(String error) {
        this.error = Objects.requireNonNull(error, "error");
        support.firePropertyChange(ERROR, null, error);
    }

    public void announceProcessed(UUID systemId) {
        support.firePropertyChange(PROCESSED, null, Objects.requireNonNull(systemId, "systemId"));
    }

    public void addPropertyChangeListener(PropertyChangeListener listener) {
        support.addPropertyChangeListener(listener);
    }

    public void removePropertyChangeListener(PropertyChangeListener listener) {
        support.removePropertyChangeListener(listener);
    }
}
