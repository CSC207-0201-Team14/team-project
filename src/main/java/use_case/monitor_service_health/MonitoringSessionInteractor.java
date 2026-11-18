package use_case.monitor_service_health;

import entity.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MonitoringSessionInteractor
        implements MonitoringSessionInputBoundary {

    private final MonitoringInputBoundary monitoring;
    private final MonitoringScheduler scheduler;
    private final MonitoringOutputBoundary output;

    private final Map<UUID, ActiveMonitoring> sessions = new HashMap<>();

    public MonitoringSessionInteractor(
            MonitoringInputBoundary monitoring,
            MonitoringScheduler scheduler,
            MonitoringOutputBoundary output
    ) {
        this.monitoring = Objects.requireNonNull(monitoring, "monitoring");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.output = Objects.requireNonNull(output, "output");
    }

    @Override
    public synchronized void startMonitoring(Service service) {
        Objects.requireNonNull(service, "service");

        if (sessions.containsKey(service.getId())) {
            return;
        }

        AtomicBoolean active = new AtomicBoolean(true);

        try {
            MonitoringTask task = scheduler.schedule(
                    () -> checkSafely(service, active),
                    service.getMonitoringPolicy().getCheckInterval()
            );

            sessions.put(
                    service.getId(),
                    new ActiveMonitoring(active, task)
            );
        } catch (RuntimeException exception) {
            active.set(false);
            output.presentError(
                    "Could not start monitoring: " + exception.getMessage()
            );
        }
    }

    @Override
    public synchronized void stopMonitoring(UUID serviceId) {
        Objects.requireNonNull(serviceId, "serviceId");

        ActiveMonitoring session = sessions.remove(serviceId);

        if (session != null) {
            session.cancel();
        }
    }

    @Override
    public synchronized void stopAll() {
        for (ActiveMonitoring session : sessions.values()) {
            session.cancel();
        }

        sessions.clear();
    }

    private void checkSafely(Service service, AtomicBoolean active) {
        if (!active.get()) {
            return;
        }

        try {
            monitoring.checkOnce(service);
        } catch (RuntimeException exception) {
            output.presentError(
                    "Monitoring check failed: " + exception.getMessage()
            );
        }
    }

    private static final class ActiveMonitoring {

        private final AtomicBoolean active;
        private final MonitoringTask task;

        private ActiveMonitoring(
                AtomicBoolean active,
                MonitoringTask task
        ) {
            this.active = active;
            this.task = task;
        }

        private void cancel() {
            active.set(false);
            task.cancel();
        }
    }
}