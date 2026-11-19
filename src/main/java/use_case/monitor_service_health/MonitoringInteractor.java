package use_case.monitor_service_health;

import entity.FailureEvent;
import entity.HealthCheckResult;
import entity.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class MonitoringInteractor implements MonitoringInputBoundary {

    private final HealthChecker checker;
    private final ObservationStore store;
    private final MonitoringOutputBoundary output;
    private final FailureEventListener listener;
    private final HealthStateEvaluator evaluator = new HealthStateEvaluator();

    private final Map<UUID, FailureEvent> pending = new LinkedHashMap<>();

    public MonitoringInteractor(
            HealthChecker checker,
            ObservationStore store,
            MonitoringOutputBoundary output,
            FailureEventListener listener
    ) {
        this.checker = Objects.requireNonNull(checker, "checker");
        this.store = Objects.requireNonNull(store, "store");
        this.output = Objects.requireNonNull(output, "output");
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    @Override
    public synchronized void checkOnce(Service service) {
        Objects.requireNonNull(service, "service");

        retryPendingFailures();

        HealthCheckResult result = checker.check(service);

        try {
            store.save(result);
        } catch (ObservationStoreException exception) {
            output.presentError(
                    "Could not save health check result: " + exception.getMessage()
            );
            return;
        }

        output.presentCheckResult(result);

        List<HealthCheckResult> history;

        try {
            history = store.findByServiceId(service.getId());
        } catch (ObservationStoreException exception) {
            output.presentError(
                    "Could not read health check history: " + exception.getMessage()
            );
            return;
        }

        HealthEvaluation evaluation = evaluator.evaluate(service, history);

        evaluation.getFailureEvent().ifPresent(this::enqueueFailure);
        output.presentHealth(evaluation.getHealth());
    }

    private void retryPendingFailures() {
        pending.values().removeIf(this::tryDeliver);
    }

    private void enqueueFailure(FailureEvent event) {
        if (pending.containsKey(event.getId())) {
            return;
        }

        pending.put(event.getId(), event);

        if (tryDeliver(event)) {
            pending.remove(event.getId());
        }
    }

    private boolean tryDeliver(FailureEvent event) {
        try {
            return listener.deliver(event);
        } catch (RuntimeException exception) {
            output.presentError(
                    "Could not deliver failure event "
                            + event.getId()
                            + ": "
                            + exception.getMessage()
            );
            return false;
        }
    }
}