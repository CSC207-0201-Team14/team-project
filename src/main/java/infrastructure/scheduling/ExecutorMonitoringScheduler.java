package infrastructure.scheduling;

import use_case.monitor_service_health.MonitoringScheduler;
import use_case.monitor_service_health.MonitoringTask;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public final class ExecutorMonitoringScheduler
        implements MonitoringScheduler, AutoCloseable {

    private final ScheduledThreadPoolExecutor executor =
            new ScheduledThreadPoolExecutor(1);

    public ExecutorMonitoringScheduler() {
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    }

    @Override
    public MonitoringTask schedule(Runnable check, Duration interval) {
        Objects.requireNonNull(check, "check");
        Objects.requireNonNull(interval, "interval");

        if (interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException(
                    "The monitoring interval must be positive"
            );
        }

        ScheduledFuture<?> future = executor.scheduleWithFixedDelay(
                check,
                0,
                interval.toNanos(),
                TimeUnit.NANOSECONDS
        );

        return () -> future.cancel(false);
    }

    @Override
    public void close() {
        executor.shutdown();
    }
}