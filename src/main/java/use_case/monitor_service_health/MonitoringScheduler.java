package use_case.monitor_service_health;

import java.time.Duration;

public interface MonitoringScheduler {

    /**
     * Schedules the first check immediately.
     * Subsequent checks wait for the interval after the previous check finishes.
     */
    MonitoringTask schedule(Runnable check, Duration interval);
}