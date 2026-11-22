package use_case.monitor_service_health;

import entity.Service;

import java.util.UUID;

public interface MonitoringSessionInputBoundary {

    /** Starts monitoring. Starting an already monitored service has no effect. */
    void startMonitoring(Service service);

    /** Cancels future checks for the service. */
    void stopMonitoring(UUID serviceId);

    /** Cancels future checks for all monitored services. */
    void stopAll();

    boolean isMonitoring(UUID serviceId);
}