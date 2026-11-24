package infrastructure.scheduling;

import use_case.shared.IncidentWorker;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * IncidentWorker backed by one non-daemon thread, so an accepted incident write finishes even after the last
 * window closes. The thread exits after an idle period and is recreated on demand, so a worker that is never
 * closed cannot keep the JVM alive. Closing lets queued operations finish and rejects new ones.
 */
public final class SingleThreadIncidentWorker implements IncidentWorker, AutoCloseable {
    private static final Duration DEFAULT_IDLE_TIMEOUT = Duration.ofSeconds(1);

    private volatile Thread workerThread;
    private final ThreadPoolExecutor executor;

    public SingleThreadIncidentWorker() {
        this(DEFAULT_IDLE_TIMEOUT);
    }

    SingleThreadIncidentWorker(Duration idleTimeout) {
        Objects.requireNonNull(idleTimeout, "idleTimeout");
        if (idleTimeout.isNegative() || idleTimeout.isZero()) {
            throw new IllegalArgumentException("Idle timeout must be positive");
        }
        // One thread at most and an unbounded FIFO queue keep operations serialized in submission order.
        executor = new ThreadPoolExecutor(1, 1, idleTimeout.toNanos(), TimeUnit.NANOSECONDS,
                new LinkedBlockingQueue<>(), task -> {
                    Thread thread = new Thread(task, "incident-worker");
                    thread.setDaemon(false);
                    workerThread = thread;
                    return thread;
                });
        executor.allowCoreThreadTimeOut(true);
    }

    @Override
    public void execute(Runnable command) {
        executor.execute(command);
    }

    @Override
    public boolean isWorkerThread() {
        return Thread.currentThread() == workerThread;
    }

    /** Lets queued operations finish and rejects new submissions. */
    @Override
    public void close() {
        executor.shutdown();
    }
}
