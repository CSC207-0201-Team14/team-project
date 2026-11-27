package infrastructure.scheduling;

import use_case.monitor_service_health.MonitoringTask;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class MonitoringSchedulerChecks {

    public static void main(String[] args) throws InterruptedException {
        AtomicInteger executions = new AtomicInteger();
        AtomicBoolean interrupted = new AtomicBoolean();

        CountDownLatch firstThree = new CountDownLatch(3);
        CountDownLatch releaseThird = new CountDownLatch(1);
        CountDownLatch thirdFinished = new CountDownLatch(1);
        CountDownLatch unexpectedRun = new CountDownLatch(1);

        try (ExecutorMonitoringScheduler scheduler =
                     new ExecutorMonitoringScheduler()) {

            MonitoringTask task = scheduler.schedule(() -> {
                int count = executions.incrementAndGet();

                if (count > 3) {
                    unexpectedRun.countDown();
                    return;
                }

                firstThree.countDown();

                if (count == 3) {
                    try {
                        releaseThird.await();
                    } catch (InterruptedException exception) {
                        interrupted.set(true);
                        Thread.currentThread().interrupt();
                    } finally {
                        thirdFinished.countDown();
                    }
                }
            }, Duration.ofMillis(20));

            try {
                require(firstThree.await(3, TimeUnit.SECONDS),
                        "The task should execute three times");

                task.cancel();
                releaseThird.countDown();

                require(thirdFinished.await(3, TimeUnit.SECONDS),
                        "The running check should be allowed to finish");
                require(!interrupted.get(),
                        "Cancellation should not interrupt the running check");
                require(!unexpectedRun.await(200, TimeUnit.MILLISECONDS),
                        "No further checks should run after cancellation");

                System.out.println("MonitoringSchedulerChecks passed");
            } finally {
                releaseThird.countDown();
                task.cancel();
            }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}