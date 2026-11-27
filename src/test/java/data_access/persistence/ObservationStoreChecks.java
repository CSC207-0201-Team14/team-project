package data_access.persistence;

import entity.HealthCheckResult;

import java.time.Instant;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;

public final class ObservationStoreChecks {

    public static void main(String[] args) {
        InMemoryObservationStore store = new InMemoryObservationStore();
        UUID serviceA = UUID.randomUUID();
        UUID serviceB = UUID.randomUUID();

        require(store.findByServiceId(serviceA).isEmpty(),
                "A new service should have no history");

        Instant start = Instant.parse("2026-11-17T12:00:00Z");
        HealthCheckResult earlier = result(serviceA, start);
        HealthCheckResult later = result(serviceA, start.plusSeconds(10));
        HealthCheckResult otherService = result(serviceB, start);

        // Save out of order to verify that retrieval sorts by time.
        store.save(later);
        store.save(otherService);
        store.save(earlier);

        require(store.findByServiceId(serviceA).equals(List.of(earlier, later)),
                "Service A history should be isolated and sorted");

        require(store.findByServiceId(serviceB).equals(List.of(otherService)),
                "Service B should have its own history");

        store.save(earlier);
        require(store.findByServiceId(serviceA).size() == 2,
                "Saving the same observation twice should not duplicate it");

        System.out.println("ObservationStoreChecks passed");
    }

    private static HealthCheckResult result(UUID serviceId, Instant timestamp) {
        return new HealthCheckResult(
                UUID.randomUUID(),
                serviceId,
                timestamp,
                HealthCheckResult.Outcome.PASS,
                OptionalLong.of(20),
                "HTTP 200"
        );
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}