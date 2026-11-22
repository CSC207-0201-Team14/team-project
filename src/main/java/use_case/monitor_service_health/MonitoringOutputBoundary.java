package use_case.monitor_service_health;

import entity.HealthCheckResult;
import entity.ServiceHealth;

public interface MonitoringOutputBoundary {

    void presentCheckResult(HealthCheckResult result);

    void presentHealth(ServiceHealth health);

    void presentError(String message);
}