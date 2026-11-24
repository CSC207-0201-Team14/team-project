package interface_adapter.investigate_incident;

import entity.HealthCheckResult;
import entity.MonitoredSystem;
import support.TestData;
import use_case.configure_monitored_system.ConfigurationLoadResult;
import use_case.configure_monitored_system.ConfigurationRepository;
import use_case.configure_monitored_system.ConfigurationStoreException;
import use_case.shared.ConfigurationSnapshot;
import use_case.monitor_service_health.ObservationStoreException;
import use_case.shared.IncidentDataException;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static support.Checks.check;

public final class IncidentReaderChecks {
    private IncidentReaderChecks() {
    }

    public static void main(String[] args) {
        readsConfigurationById();
        readsRetainedObservationsWithinScope();
        System.out.println("UC3 reader adapter checks passed");
    }

    private static void readsConfigurationById() {
        MonitoredSystem system = new MonitoredSystem(UUID.randomUUID(), "Orders", "", List.of(), List.of());
        UUID inaccessible = UUID.randomUUID();
        ConfigurationStoreException failure = new ConfigurationStoreException("Could not read Orders.", null);
        ConfigurationRepositoryReader reader = new ConfigurationRepositoryReader(new ConfigurationRepository() {
            @Override
            public Optional<MonitoredSystem> findById(UUID id) {
                if (id.equals(inaccessible)) {
                    throw failure;
                }
                return id.equals(system.getId()) ? Optional.of(system) : Optional.empty();
            }

            @Override
            public ConfigurationLoadResult findAll() {
                throw new AssertionError("Expected a single-system lookup");
            }

            @Override
            public void save(MonitoredSystem value) {
                throw new AssertionError("Unexpected configuration write");
            }
        });
        ConfigurationSnapshot snapshot = reader.findBySystemId(system.getId()).orElseThrow();
        check(snapshot.getSystem() == system && snapshot.getDependencyUnavailableReason().isEmpty(),
                "A stored configuration with no dependencies is an available graph");
        check(reader.findBySystemId(UUID.randomUUID()).isEmpty(), "Missing configurations remain absent");
        expectTranslated(failure, () -> reader.findBySystemId(inaccessible));
    }

    private static void readsRetainedObservationsWithinScope() {
        UUID serviceId = UUID.randomUUID();
        HealthCheckResult historical = TestData.observation(serviceId, TestData.T0, HealthCheckResult.Outcome.FAIL);
        Set<UUID> scope = Set.of(serviceId);
        Set<UUID> requested = Set.of(historical.getId(), UUID.randomUUID());
        ObservationLookupReader reader = new ObservationLookupReader((serviceIds, observationIds) -> {
            check(serviceIds.equals(scope), "Preserve the incident's service scope");
            check(observationIds.equals(requested), "Request all evidence IDs");
            return Map.of(historical.getId(), historical);
        });
        check(reader.findByIds(scope, requested).equals(Map.of(historical.getId(), historical)),
                "Return retained evidence and omit missing IDs");

        ObservationStoreException failure = new ObservationStoreException("Observation history is unavailable.", null);
        ObservationLookupReader inaccessible = new ObservationLookupReader((serviceIds, observationIds) -> {
            throw failure;
        });
        expectTranslated(failure, () -> inaccessible.findByIds(scope, requested));
    }

    private static void expectTranslated(RuntimeException failure, Runnable read) {
        try {
            read.run();
        } catch (IncidentDataException exception) {
            check(exception.getCause() == failure && failure.getMessage().equals(exception.getMessage()),
                    "Keep the storage error and its presentation message");
            return;
        }
        throw new AssertionError("Expected a storage failure to reach UC3");
    }
}
