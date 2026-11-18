package use_case.monitor_service_health;

import entity.HealthCheckResult;

public interface MonitoringOutputBoundary {

    void presentCheckResult(HealthCheckResult result);

    void presentError(String message);
}