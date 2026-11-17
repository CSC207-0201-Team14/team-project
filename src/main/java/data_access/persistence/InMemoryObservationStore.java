package data_access.persistence;

import entity.HealthCheckResult;
import use_case.monitor_service_health.ObservationStore;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

public final class InMemoryObservationStore implements ObservationStore {

    private final Map<UUID, HealthCheckResult> observations = new HashMap<>();

    @Override
    public synchronized void save(HealthCheckResult result) {
        Objects.requireNonNull(result, "result");
        observations.put(result.getId(), result);
    }

    @Override
    public synchronized List<HealthCheckResult> findByServiceId(UUID serviceId) {
        Objects.requireNonNull(serviceId, "serviceId");

        return observations.values().stream()
                .filter(result -> result.getServiceId().equals(serviceId))
                .sorted(Comparator.comparing(HealthCheckResult::getTimestamp)
                        .thenComparing(HealthCheckResult::getId))
                .collect(Collectors.toList());
    }
}
