package use_case.investigate_incident;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;

/**
 * Decorator that runs every incident call on one worker thread, so the monitoring thread (failures)
 * and the UI (opening incidents) never write the same incident concurrently.
 * processFailure waits for its result; openIncident returns immediately and presents through the output boundary.
 */
public final class SerializedIncidentInputBoundary implements IncidentInputBoundary, AutoCloseable {
    private final IncidentInputBoundary delegate;
    private final ExecutorService worker;
    private volatile Thread workerThread;

    public SerializedIncidentInputBoundary(IncidentInputBoundary delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.worker = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(() -> {
                workerThread = Thread.currentThread();
                task.run();
            }, "incident-worker");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Returns RETRYABLE if the caller is interrupted or this boundary is closed;
     * the caller keeps the event and redelivers it with the same ID.
     */
    @Override
    public FailureProcessingResult processFailure(FailureInput input) {
        Objects.requireNonNull(input, "input");
        if (Thread.currentThread() == workerThread) {
            return delegate.processFailure(input);
        }
        Future<FailureProcessingResult> result;
        try {
            result = worker.submit(() -> delegate.processFailure(input));
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

    /** Queues the request; it is ignored once this boundary is closed. */
    @Override
    public void openIncident(UUID incidentId) {
        Objects.requireNonNull(incidentId, "incidentId");
        try {
            worker.execute(() -> delegate.openIncident(incidentId));
        } catch (RejectedExecutionException closed) {
            // Shutting down: nothing to present.
        }
    }

    /** Lets queued calls finish and rejects new ones. */
    @Override
    public void close() {
        worker.shutdown();
    }
}
