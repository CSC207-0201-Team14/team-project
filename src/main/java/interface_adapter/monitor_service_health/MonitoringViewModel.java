package interface_adapter.monitor_service_health;

import entity.HealthCheckResult;

import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import entity.HealthStatus;
import entity.ServiceHealth;

/** Holds monitoring display state. Access this model on the Swing EDT. */
public final class MonitoringViewModel {

    private final PropertyChangeSupport changes =
            new PropertyChangeSupport(this);

    private final List<HealthCheckResult> results = new ArrayList<>();
    private String errorMessage = "";
    private HealthStatus healthStatus = HealthStatus.UNKNOWN;

    public List<HealthCheckResult> getResults() {
        return List.copyOf(results);
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void showResult(HealthCheckResult result) {
        Objects.requireNonNull(result, "result");

        List<HealthCheckResult> previousResults = List.copyOf(results);
        results.add(result);
        showError("");

        changes.firePropertyChange(
                "results",
                previousResults,
                List.copyOf(results)
        );
    }

    public void showError(String message) {
        Objects.requireNonNull(message, "message");

        String previousMessage = errorMessage;
        errorMessage = message;

        changes.firePropertyChange(
                "errorMessage",
                previousMessage,
                errorMessage
        );
    }

    public void addPropertyChangeListener(PropertyChangeListener listener) {
        changes.addPropertyChangeListener(listener);
    }

    public HealthStatus getHealthStatus() {
        return healthStatus;
    }

    public void showHealth(ServiceHealth health) {
        Objects.requireNonNull(health, "health");

        HealthStatus previousStatus = healthStatus;
        healthStatus = health.getStatus();

        changes.firePropertyChange(
                "healthStatus",
                previousStatus,
                healthStatus
        );
    }
}