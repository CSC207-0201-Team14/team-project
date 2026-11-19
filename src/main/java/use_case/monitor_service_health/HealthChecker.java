package use_case.monitor_service_health;

import entity.HealthCheckResult;
import entity.Service;

/** Checks a service once using its configured target and timeout. */
public interface HealthChecker {
    HealthCheckResult check(Service service);
}