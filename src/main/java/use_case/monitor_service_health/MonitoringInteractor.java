package use_case.monitor_service_health;

import entity.HealthCheckResult;
import entity.Service;

import java.util.Objects;

import java.util.List;

public final class MonitoringInteractor implements MonitoringInputBoundary {

    private final HealthChecker checker;
    private final ObservationStore store;
    private final MonitoringOutputBoundary output;
    private final HealthStateEvaluator evaluator = new HealthStateEvaluator();

    public MonitoringInteractor(
            HealthChecker checker,
            ObservationStore store,
            MonitoringOutputBoundary output
    ) {
        this.checker = Objects.requireNonNull(checker, "checker");
        this.store = Objects.requireNonNull(store, "store");
        this.output = Objects.requireNonNull(output, "output");
    }

    @Override
    public synchronized void checkOnce(Service service) {
        Objects.requireNonNull(service, "service");

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
        output.presentHealth(evaluation.getHealth());
    }
}