package use_case.monitor_service_health;

import data_access.persistence.InMemoryObservationStore;
import entity.HealthCheckResult;
import entity.MonitoringPolicy;
import entity.MonitoringTarget;
import entity.Service;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;

import java.io.IOException;
import java.util.ArrayList;

import entity.ServiceHealth;
import entity.HealthStatus;

public final class MonitoringInteractorChecks {

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

        HealthCheckResult expected = new HealthCheckResult(
                UUID.randomUUID(),
                service.getId(),
                Instant.now(),
                HealthCheckResult.Outcome.PASS,
                OptionalLong.of(20),
                "HTTP 200"
        );

        HealthChecker checker = requestedService -> {
            require(requestedService.getId().equals(service.getId()),
                    "The checker should receive the requested service");
            return expected;
        };

        InMemoryObservationStore store = new InMemoryObservationStore();

        List<ServiceHealth> healthUpdates = new ArrayList<>();

        MonitoringOutputBoundary output = new MonitoringOutputBoundary() {
            @Override
            public void presentCheckResult(HealthCheckResult result) {
                require(result == expected,
                        "The output should receive the checker's result");
                require(store.findByServiceId(service.getId())
                                .equals(List.of(expected)),
                        "The result should be saved before presentation");
                System.out.println("Check result presented after saving");
            }

            @Override
            public void presentError(String message) {
                throw new AssertionError("Unexpected error: " + message);
            }

            @Override
            public void presentHealth(ServiceHealth health) {
                healthUpdates.add(health);
            }
        };

        MonitoringInteractor interactor = new MonitoringInteractor(
                checker, store, output
        );

        interactor.checkOnce(service);

        require(healthUpdates.size() == 1,
                "One check should produce one health update");

        ServiceHealth health = healthUpdates.get(0);

        require(health.getStatus() == HealthStatus.UNKNOWN,
                "One pass should remain UNKNOWN when recovery requires two passes");

        require(health.getLatestResult().orElseThrow() == expected,
                "The health update should reference the latest check");

        require(store.findByServiceId(service.getId()).equals(List.of(expected)),
                "The observation should remain available in history");

        System.out.println("MonitoringInteractorChecks main flow passed");
        checksStorageFailure(service, expected);
        System.out.println("MonitoringInteractorChecks storage failure passed");
    }

    private static void checksStorageFailure(
            Service service,
            HealthCheckResult expected
    ) {
        ObservationStore failingStore = new ObservationStore() {
            @Override
            public void save(HealthCheckResult result) {
                throw new ObservationStoreException(
                        "Disk full",
                        new IOException("Simulated disk error")
                );
            }

            @Override
            public List<HealthCheckResult> findByServiceId(UUID serviceId) {
                throw new AssertionError(
                        "Checking once should not need to read history"
                );
            }
        };

        List<String> errors = new ArrayList<>();

        MonitoringOutputBoundary output = new MonitoringOutputBoundary() {
            @Override
            public void presentCheckResult(HealthCheckResult result) {
                throw new AssertionError(
                        "A storage failure should be reported through presentError"
                );
            }

            @Override
            public void presentError(String message) {
                errors.add(message);
            }

            @Override
            public void presentHealth(ServiceHealth health) {
                throw new AssertionError(
                        "A storage failure should prevent health evaluation"
                );
            }
        };

        MonitoringInteractor interactor = new MonitoringInteractor(
                requestedService -> expected,
                failingStore,
                output
        );

        interactor.checkOnce(service);

        require(errors.equals(List.of(
                        "Could not save health check result: Disk full"
                )),
                "A storage failure should produce exactly one error message");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}