package use_case.monitor_service_health;

import entity.HealthCheckResult;
import entity.Service;

/** Checks a service once using its configured target and timeout. */
public interface HealthChecker {

    /**
     * Performs one health check.
     *
     * <p>Expected operational failures, including timeouts, connection errors,
     * and responses that cannot be interpreted, are reported as
     * MONITORING_ERROR results.
     *
     * <p>A valid observation indicating an unhealthy service is reported as FAIL.
     *
     * @param service the service to check
     * @return a non-null observation identifying the checked service
     */
    HealthCheckResult check(Service service);
}