package use_case.investigate_incident;

import entity.FailureEvent;
import entity.HealthCheckResult;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/** Run explicitly after mvn test-compile, following the existing test convention. */
public final class SerializedIncidentChecks {
    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

    public static void main(String[] args) throws Exception {
        runsMonitoringAndUiCallsOneAtATime();
        returnsRetryableWhenInterruptedOrClosed();
        allowsNestedCallsFromTheWorker();
        System.out.println("UC3 serialized boundary checks passed");
    }

    private static void runsMonitoringAndUiCallsOneAtATime() throws Exception {
        CountingBoundary inner = new CountingBoundary(input -> FailureProcessingResult.PROCESSED);
        try (SerializedIncidentInputBoundary boundary = new SerializedIncidentInputBoundary(inner)) {
            List<Thread> callers = new ArrayList<>();
            List<FailureProcessingResult> results = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                callers.add(new Thread(() -> {
                    FailureProcessingResult result = boundary.processFailure(input());
                    synchronized (results) {
                        results.add(result);
                    }
                }));
            }
            callers.forEach(Thread::start);
            for (int i = 0; i < 4; i++) {
                boundary.openIncident(UUID.randomUUID());
            }
            for (Thread caller : callers) {
                caller.join();
            }
            check(inner.opened.await(5, TimeUnit.SECONDS), "Queued open requests run");
            check(results.size() == 4 && results.stream().allMatch(r -> r == FailureProcessingResult.PROCESSED),
                    "Monitoring callers receive the interactor's result");
            check(inner.maxActive.get() == 1, "Failures and open requests never overlap");
            check("incident-worker".equals(inner.openThread.get()), "Open requests run off the calling thread");
        }
    }

    private static void returnsRetryableWhenInterruptedOrClosed() {
        CountingBoundary inner = new CountingBoundary(input -> FailureProcessingResult.PROCESSED);
        SerializedIncidentInputBoundary boundary = new SerializedIncidentInputBoundary(inner);
        Thread.currentThread().interrupt();
        FailureProcessingResult interrupted = boundary.processFailure(input());
        check(interrupted == FailureProcessingResult.RETRYABLE && Thread.interrupted(),
                "An interrupted caller keeps the event and its interrupt flag");
        boundary.close();
        check(boundary.processFailure(input()) == FailureProcessingResult.RETRYABLE,
                "A closed boundary asks the caller to redeliver");
        boundary.openIncident(UUID.randomUUID());
    }

    private static void allowsNestedCallsFromTheWorker() {
        AtomicReference<SerializedIncidentInputBoundary> outer = new AtomicReference<>();
        AtomicInteger depth = new AtomicInteger();
        CountingBoundary inner = new CountingBoundary(input -> depth.incrementAndGet() == 1
                ? outer.get().processFailure(input) : FailureProcessingResult.PROCESSED);
        try (SerializedIncidentInputBoundary boundary = new SerializedIncidentInputBoundary(inner)) {
            outer.set(boundary);
            check(boundary.processFailure(input()) == FailureProcessingResult.PROCESSED,
                    "A call made from the worker runs directly instead of deadlocking");
        }
    }

    private static FailureInput input() {
        UUID service = UUID.randomUUID();
        HealthCheckResult failed = new HealthCheckResult(UUID.randomUUID(), service, START,
                HealthCheckResult.Outcome.FAIL, OptionalLong.empty(), "");
        return new FailureInput(UUID.randomUUID(), new FailureEvent(UUID.randomUUID(), service, START,
                List.of(failed)), List.of());
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static final class CountingBoundary implements IncidentInputBoundary {
        private final Function<FailureInput, FailureProcessingResult> behaviour;
        private final AtomicInteger active = new AtomicInteger();
        private final AtomicInteger maxActive = new AtomicInteger();
        private final CountDownLatch opened = new CountDownLatch(4);
        private final AtomicReference<String> openThread = new AtomicReference<>();

        CountingBoundary(Function<FailureInput, FailureProcessingResult> behaviour) {
            this.behaviour = behaviour;
        }

        @Override
        public FailureProcessingResult processFailure(FailureInput input) {
            enter();
            try {
                return behaviour.apply(input);
            } finally {
                active.decrementAndGet();
            }
        }

        @Override
        public void openIncident(UUID incidentId) {
            enter();
            openThread.set(Thread.currentThread().getName());
            active.decrementAndGet();
            opened.countDown();
        }

        private void enter() {
            maxActive.accumulateAndGet(active.incrementAndGet(), Math::max);
            try {
                Thread.sleep(10);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
