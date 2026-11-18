package use_case.monitor_service_health;

import entity.HealthCheckResult;
import entity.MonitoringPolicy;
import entity.MonitoringTarget;
import entity.Service;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

public final class MonitoringSessionChecks {

    public static void main(String[] args) {
        Service service = new Service(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "Test service",
                MonitoringTarget.http(URI.create("http://localhost/health")),
                new MonitoringPolicy(
                        Duration.ofSeconds(10),
                        Duration.ofSeconds(2),
                        3,
                        2
                )
        );

        AtomicInteger attempts = new AtomicInteger();

        MonitoringInputBoundary monitoring = requestedService -> {
            require(requestedService.getId().equals(service.getId()),
                    "The requested service should be checked");

            if (attempts.incrementAndGet() == 1) {
                throw new IllegalStateException("Simulated check failure");
            }
        };

        FakeScheduler scheduler = new FakeScheduler();
        List<String> errors = new ArrayList<>();

        MonitoringOutputBoundary output = new MonitoringOutputBoundary() {
            @Override
            public void presentCheckResult(HealthCheckResult result) {
                throw new AssertionError(
                        "Check results should come from the single-check use case"
                );
            }

            @Override
            public void presentError(String message) {
                errors.add(message);
            }
        };

        MonitoringSessionInteractor session =
                new MonitoringSessionInteractor(monitoring, scheduler, output);

        session.startMonitoring(service);
        session.startMonitoring(service);

        require(scheduler.tasks.size() == 1,
                "Starting twice should create only one task");

        FakeTask first = scheduler.tasks.get(0);
        require(first.interval.equals(service.getMonitoringPolicy().getCheckInterval()),
                "The task should use the configured interval");

        first.check.run();
        require(errors.equals(List.of(
                        "Monitoring check failed: Simulated check failure"
                )),
                "An unexpected check failure should be reported");

        first.check.run();
        require(attempts.get() == 2,
                "A later check should still run after an error");

        session.stopMonitoring(service.getId());
        require(first.cancelled, "Stopping should cancel the task");

        first.check.run();
        require(attempts.get() == 2,
                "A stopped callback should perform no further checks");

        session.startMonitoring(service);
        require(scheduler.tasks.size() == 2,
                "Restarting should create a new task");

        FakeTask restarted = scheduler.tasks.get(1);

        first.check.run();
        require(attempts.get() == 2,
                "An old callback should remain inactive after restart");

        restarted.check.run();
        require(attempts.get() == 3,
                "The restarted task should perform checks");

        session.stopAll();
        require(restarted.cancelled,
                "Stopping all should cancel the active task");

        restarted.check.run();
        require(attempts.get() == 3,
                "Stopped tasks should remain inactive");

        session.stopMonitoring(service.getId());
        session.stopAll();

        System.out.println("MonitoringSessionChecks passed");
    }

    private static final class FakeScheduler implements MonitoringScheduler {

        private final List<FakeTask> tasks = new ArrayList<>();

        @Override
        public MonitoringTask schedule(Runnable check, Duration interval) {
            FakeTask task = new FakeTask(check, interval);
            tasks.add(task);
            return task;
        }
    }

    private static final class FakeTask implements MonitoringTask {

        private final Runnable check;
        private final Duration interval;
        private boolean cancelled;

        private FakeTask(Runnable check, Duration interval) {
            this.check = check;
            this.interval = interval;
        }

        @Override
        public void cancel() {
            cancelled = true;
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}