package use_case.investigate_incident;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static support.Checks.check;
import static support.TestData.T0;
import static support.TestData.failure;

/** Run explicitly after mvn test-compile, following the existing test convention. */
public final class SerializedIncidentChecks {
    public static void main(String[] args) throws Exception {
        runsMonitoringAndUiCallsOneAtATime();
        returnsRetryableWhenInterruptedOrClosed();
        allowsNestedCallsFromTheWorker();
        System.out.println("UC3 serialized boundary checks passed");
    }

    private static void runsMonitoringAndUiCallsOneAtATime() throws Exception {
        CountDownLatch processing = new CountDownLatch(1);
        CountDownLatch uiQueued = new CountDownLatch(1);
        AtomicReference<Thread> processingThread = new AtomicReference<>();
        CountingBoundary inner = new CountingBoundary(input -> {
            processingThread.set(Thread.currentThread());
            processing.countDown();
            try {
                check(uiQueued.await(5, TimeUnit.SECONDS), "UI requests return while the worker is busy");
            } catch (InterruptedException interrupted) {
                throw new AssertionError(interrupted);
            }
            return FailureProcessingResult.PROCESSED;
        });
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
            check(processing.await(5, TimeUnit.SECONDS), "Worker starts processing a failure");
            List<String> expectedRequests = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                UUID systemId = UUID.randomUUID();
                UUID incidentId = UUID.randomUUID();
                boundary.listIncidents(systemId);
                boundary.openIncident(incidentId);
                expectedRequests.add("list " + systemId);
                expectedRequests.add("open " + incidentId);
            }
            uiQueued.countDown();
            for (Thread caller : callers) {
                caller.join();
            }
            boundary.processFailure(input()); // Wait for all earlier UI requests to finish.
            check(inner.uiRequests.equals(expectedRequests), "List and open requests run in submission order");
            check(results.size() == 4 && results.stream().allMatch(r -> r == FailureProcessingResult.PROCESSED),
                    "Monitoring callers receive the interactor's result");
            check(inner.maxActive.get() == 1, "Monitoring and UI calls never overlap");
            check(processingThread.get() != Thread.currentThread()
                    && inner.uiThreads.stream().allMatch(thread -> thread == processingThread.get()),
                    "List, open and failure requests use the same worker thread");
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
        boundary.listIncidents(UUID.randomUUID());
        check(inner.uiRequests.isEmpty(), "A closed boundary ignores new UI requests");
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
        return new FailureInput(UUID.randomUUID(), failure(UUID.randomUUID(), T0), List.of());
    }

    private static final class CountingBoundary implements IncidentInputBoundary {
        private final Function<FailureInput, FailureProcessingResult> behaviour;
        private final AtomicInteger active = new AtomicInteger();
        private final AtomicInteger maxActive = new AtomicInteger();
        private final List<String> uiRequests = new ArrayList<>();
        private final List<Thread> uiThreads = new ArrayList<>();

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
        public void listIncidents(UUID systemId) {
            recordRequest("list " + systemId);
        }

        @Override
        public void openIncident(UUID incidentId) {
            recordRequest("open " + incidentId);
        }

        private void recordRequest(String request) {
            enter();
            uiRequests.add(request);
            uiThreads.add(Thread.currentThread());
            active.decrementAndGet();
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
