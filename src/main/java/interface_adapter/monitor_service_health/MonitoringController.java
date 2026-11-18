package interface_adapter.monitor_service_health;

import entity.Service;
import use_case.monitor_service_health.MonitoringInputBoundary;
import use_case.monitor_service_health.MonitoringOutputBoundary;

import javax.swing.SwingWorker;
import java.util.Objects;
import java.util.concurrent.ExecutionException;

import use_case.monitor_service_health.MonitoringSessionInputBoundary;
import java.util.UUID;

public final class MonitoringController {

    private final MonitoringInputBoundary input;
    private final MonitoringOutputBoundary output;
    private final MonitoringSessionInputBoundary sessions;

    public MonitoringController(
            MonitoringInputBoundary input,
            MonitoringSessionInputBoundary sessions,
            MonitoringOutputBoundary output
    ) {
        this.input = Objects.requireNonNull(input, "input");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.output = Objects.requireNonNull(output, "output");
    }

    public void checkOnce(Service service) {
        Objects.requireNonNull(service, "service");

        SwingWorker<Void, Void> worker = new SwingWorker<>() {
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
                    output.presentError(
                            "Check could not complete: "
                                    + exception.getCause().getMessage()
                    );
                }
            }
        };

        worker.execute();
    }

    public boolean startMonitoring(Service service) {
        Objects.requireNonNull(service, "service");

        sessions.startMonitoring(service);
        return sessions.isMonitoring(service.getId());
    }

    public void stopMonitoring(UUID serviceId) {
        sessions.stopMonitoring(serviceId);
    }

    public void stopAll() {
        sessions.stopAll();
    }
}