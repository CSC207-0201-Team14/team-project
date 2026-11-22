package use_case.monitor_service_health;

import entity.Service;

public interface MonitoringInputBoundary {

    void checkOnce(Service service);
}