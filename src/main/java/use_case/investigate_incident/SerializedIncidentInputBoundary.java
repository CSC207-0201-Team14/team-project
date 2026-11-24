package use_case.investigate_incident;

import use_case.shared.IncidentWorker;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;

/**
 * Runs incident calls on the shared incident worker. Monitoring waits for the processing result.
 * UI requests to list or open incidents return immediately and present through the output boundary.
 * The worker belongs to the composition root, which also shares it with recovery and shuts it down.
 */
public final class SerializedIncidentInputBoundary implements IncidentInputBoundary {
    private final IncidentInputBoundary delegate;
    private final IncidentWorker worker;

    public SerializedIncidentInputBoundary(IncidentInputBoundary delegate, IncidentWorker worker) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.worker = Objects.requireNonNull(worker, "worker");
    }

    /**
     * Returns RETRYABLE if the caller is interrupted or the worker has shut down;
     * the caller keeps the event and redelivers it with the same ID.
     */
    @Override
    public FailureProcessingResult processFailure(FailureInput input) {
        Objects.requireNonNull(input, "input");
        if (worker.isWorkerThread()) {
            return delegate.processFailure(input);
        }
        FutureTask<FailureProcessingResult> result = new FutureTask<>(() -> delegate.processFailure(input));
        try {
            worker.execute(result);
        } catch (RejectedExecutionException closed) {
            return FailureProcessingResult.RETRYABLE;
        }
        try {
            return result.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return FailureProcessingResult.RETRYABLE;
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw new IllegalStateException(cause);
        }
    }

    @Override
    public void listIncidents(UUID systemId) {
        Objects.requireNonNull(systemId, "systemId");
        queuePresentation(() -> delegate.listIncidents(systemId));
    }

    @Override
    public void openIncident(UUID incidentId) {
        Objects.requireNonNull(incidentId, "incidentId");
        queuePresentation(() -> delegate.openIncident(incidentId));
    }

    private void queuePresentation(Runnable request) {
        try {
            worker.execute(request);
        } catch (RejectedExecutionException closed) {
            // Shutting down: nothing to present.
        }
    }
}
