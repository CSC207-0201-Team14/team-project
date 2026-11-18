package interface_adapter.monitor_service_health;

import entity.Service;
import use_case.monitor_service_health.MonitoringInputBoundary;
import use_case.monitor_service_health.MonitoringOutputBoundary;

import javax.swing.SwingWorker;
import java.util.Objects;
import java.util.concurrent.ExecutionException;

public final class MonitoringController {

    private final MonitoringInputBoundary input;
    private final MonitoringOutputBoundary output;

    public MonitoringController(
            MonitoringInputBoundary input,
            MonitoringOutputBoundary output
    ) {
        this.input = Objects.requireNonNull(input, "input");
        this.output = Objects.requireNonNull(output, "output");
    }

    public void checkOnce(Service service) {
        Objects.requireNonNull(service, "service");

        SwingWorker<Void, Void> worker = new SwingWorker<Void, Void>() {
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
}