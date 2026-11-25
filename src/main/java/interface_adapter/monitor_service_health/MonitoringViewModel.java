package interface_adapter.monitor_service_health;

import entity.HealthCheckResult;
import entity.HealthStatus;
import entity.ServiceHealth;

import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Monitoring display state, accessed only on the Swing EDT. Results are kept per service. */
public final class MonitoringViewModel {
    private final PropertyChangeSupport changes = new PropertyChangeSupport(this);
    private final Map<UUID, List<HealthCheckResult>> results = new HashMap<>();
    private final Map<UUID, ServiceHealth> healthByService = new HashMap<>();
    private UUID selectedServiceId;
    private String errorMessage = "";

    public void selectService(UUID serviceId) {
        selectedServiceId = serviceId;
        changed();
    }

    public List<HealthCheckResult> getResults() {
        return List.copyOf(results.getOrDefault(selectedServiceId, List.of()));
    }

    public HealthStatus getHealthStatus() {
        ServiceHealth health = healthByService.get(selectedServiceId);
        return health == null ? HealthStatus.UNKNOWN : health.getStatus();
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void showResult(HealthCheckResult result) {
        Objects.requireNonNull(result, "result");
        results.computeIfAbsent(result.getServiceId(), id -> new ArrayList<>()).add(result);
        changed();
    }

    public void showHealth(ServiceHealth health) {
        Objects.requireNonNull(health, "health");
        healthByService.put(health.getServiceId(), health);
        changed();
    }

    /** Errors are application-wide because MonitoringOutputBoundary does not supply a service ID. */
    public void showError(String message) {
        errorMessage = Objects.requireNonNull(message, "message");
        changed();
    }

    public void addPropertyChangeListener(PropertyChangeListener listener) {
        changes.addPropertyChangeListener(listener);
    }

    private void changed() {
        changes.firePropertyChange("display", null, null);
    }
}
