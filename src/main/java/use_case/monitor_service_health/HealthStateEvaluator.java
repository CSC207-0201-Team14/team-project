package use_case.monitor_service_health;

import entity.FailureEvent;
import entity.HealthCheckResult;
import entity.HealthStatus;
import entity.Service;
import entity.ServiceHealth;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class HealthStateEvaluator {

    public HealthEvaluation evaluate(
            Service service,
            List<HealthCheckResult> history
    ) {
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(history, "history");

        List<HealthCheckResult> observations = new ArrayList<>(history);
        Set<UUID> observationIds = new HashSet<>();

        for (HealthCheckResult result : observations) {
            Objects.requireNonNull(result, "observation");

            if (!result.getServiceId().equals(service.getId())
                    || !observationIds.add(result.getId())) {
                throw new IllegalArgumentException(
                        "History must contain distinct observations "
                                + "for the requested service"
                );
            }
        }

        observations.sort(
                Comparator.comparing(HealthCheckResult::getTimestamp)
                        .thenComparing(HealthCheckResult::getId)
        );

        if (observations.isEmpty()) {
            return new HealthEvaluation(
                    ServiceHealth.unobserved(service.getId()),
                    null
            );
        }

        HealthStatus status = HealthStatus.UNKNOWN;
        int consecutivePasses = 0;
        List<HealthCheckResult> consecutiveFailures = new ArrayList<>();
        FailureEvent latestFailure = null;

        for (HealthCheckResult result : observations) {
            latestFailure = null;

            switch (result.getOutcome()) {
                case PASS:
                    consecutiveFailures.clear();
                    consecutivePasses++;

                    if (consecutivePasses
                            >= service.getMonitoringPolicy().getRecoveryThreshold()) {
                        status = HealthStatus.HEALTHY;
                    }
                    break;

                case FAIL:
                    consecutivePasses = 0;
                    consecutiveFailures.add(result);

                    if (consecutiveFailures.size()
                            >= service.getMonitoringPolicy().getFailureThreshold()
                            && status != HealthStatus.UNHEALTHY) {
                        status = HealthStatus.UNHEALTHY;

                        String eventKey = "monitoring-failure:" + result.getId();
                        UUID eventId = UUID.nameUUIDFromBytes(
                                eventKey.getBytes(StandardCharsets.UTF_8)
                        );

                        latestFailure = new FailureEvent(
                                eventId,
                                service.getId(),
                                result.getTimestamp(),
                                consecutiveFailures
                        );
                    }
                    break;

                case MONITORING_ERROR:
                    consecutivePasses = 0;
                    consecutiveFailures.clear();
                    status = HealthStatus.UNKNOWN;
                    break;
            }
        }

        HealthCheckResult latest = observations.get(observations.size() - 1);

        return new HealthEvaluation(
                ServiceHealth.evaluated(status, latest),
                latestFailure
        );
    }
}