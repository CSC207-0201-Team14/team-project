package interface_adapter.monitor_service_health;

import entity.MonitoringPolicy;
import entity.MonitoringTarget;
import entity.Service;
import use_case.monitor_service_health.MonitoringInputBoundary;
import use_case.monitor_service_health.MonitoringOutputBoundary;
import use_case.monitor_service_health.MonitoringSessionInputBoundary;

import javax.swing.SwingWorker;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

/** UI entry points, called on the EDT. Network checks run in a background worker. */
public final class MonitoringController {
    private final MonitoringInputBoundary input;
    private final MonitoringOutputBoundary output;
    private final MonitoringSessionInputBoundary sessions;
    private final Map<UUID, Service> activeServices = new HashMap<>();

    public MonitoringController(MonitoringInputBoundary input, MonitoringSessionInputBoundary sessions,
                                MonitoringOutputBoundary output) {
        this.input = Objects.requireNonNull(input, "input");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.output = Objects.requireNonNull(output, "output");
    }

    public void checkOnce(Service service) {
        Objects.requireNonNull(service, "service");
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() {
                input.checkOnce(service);
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    output.presentError("Waiting for the check was interrupted");
                } catch (ExecutionException exception) {
                    output.presentError("Check could not complete: " + exception.getCause().getMessage());
                }
            }
        }.execute();
    }

    public boolean startMonitoring(Service service) {
        Objects.requireNonNull(service, "service");
        sessions.startMonitoring(service);
        boolean started = sessions.isMonitoring(service.getId());
        if (started) {
            activeServices.putIfAbsent(service.getId(), service);
        }
        return started;
    }

    public boolean isMonitoring(UUID serviceId) {
        return sessions.isMonitoring(serviceId);
    }

    public void stopMonitoring(UUID serviceId) {
        sessions.stopMonitoring(serviceId);
        activeServices.remove(serviceId);
    }

    public void stopAll() {
        sessions.stopAll();
        activeServices.clear();
    }

    /**
     * Called when saved configuration is loaded or updated. Cancel schedules using obsolete snapshots;
     * the user explicitly starts the updated configuration. A check already running may finish.
     */
    public int reconcileServices(List<Service> configured) {
        Objects.requireNonNull(configured, "configured");
        Map<UUID, Service> current = new HashMap<>();
        for (Service service : configured) {
            current.put(service.getId(), service);
        }
        int stopped = 0;
        for (Service active : List.copyOf(activeServices.values())) {
            Service updated = current.get(active.getId());
            if (updated == null || !sameConfiguration(active, updated)) {
                stopMonitoring(active.getId());
                stopped++;
            }
        }
        return stopped;
    }

    private static boolean sameConfiguration(Service first, Service second) {
        MonitoringTarget a = first.getMonitoringTarget();
        MonitoringTarget b = second.getMonitoringTarget();
        MonitoringPolicy x = first.getMonitoringPolicy();
        MonitoringPolicy y = second.getMonitoringPolicy();
        return first.getSystemId().equals(second.getSystemId())
                && first.getName().equals(second.getName())
                && a.getMethod() == b.getMethod()
                && a.getEndpoint().equals(b.getEndpoint())
                && a.getContainerId().equals(b.getContainerId())
                && x.getCheckInterval().equals(y.getCheckInterval())
                && x.getTimeout().equals(y.getTimeout())
                && x.getFailureThreshold() == y.getFailureThreshold()
                && x.getRecoveryThreshold() == y.getRecoveryThreshold();
    }
}
