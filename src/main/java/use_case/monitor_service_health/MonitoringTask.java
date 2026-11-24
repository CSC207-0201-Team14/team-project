package use_case.monitor_service_health;

public interface MonitoringTask {

    /** Cancels future checks. A check already running may finish. */
    void cancel();
}