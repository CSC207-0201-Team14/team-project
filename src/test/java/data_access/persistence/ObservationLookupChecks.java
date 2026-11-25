package data_access.persistence;

import entity.HealthCheckResult;

import java.time.Instant;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;

public final class ObservationLookupChecks {

    public static void main(String[] args) {
        InMemoryObservationStore store = new InMemoryObservationStore();

        UUID serviceA = UUID.randomUUID();
        UUID serviceB = UUID.randomUUID();
        UUID missingId = UUID.randomUUID();

        HealthCheckResult observationA = observation(serviceA);
        HealthCheckResult observationB = observation(serviceB);

        store.save(observationA);
        store.save(observationB);

        Set<UUID> requestedIds = Set.of(
                observationA.getId(),
                observationB.getId(),
                missingId
        );

        Map<UUID, HealthCheckResult> scoped = store.findByIds(
                Set.of(serviceA),
                requestedIds
        );

        require(scoped.equals(Map.of(observationA.getId(), observationA)),
                "Only requested observations within the service scope should be returned");

        Map<UUID, HealthCheckResult> bothServices = store.findByIds(
                Set.of(serviceA, serviceB),
                requestedIds
        );

        require(bothServices.equals(Map.of(
                        observationA.getId(), observationA,
                        observationB.getId(), observationB
                )),
                "Both allowed services should be readable; missing IDs should be omitted");

        require(store.findByIds(Set.of(), requestedIds).isEmpty(),
                "An empty service scope should return no observations");

        require(store.findByIds(Set.of(serviceA), Set.of()).isEmpty(),
                "An empty request should return no observations");

        HealthCheckResult laterObservation = observation(serviceA);
        store.save(laterObservation);

        require(scoped.equals(Map.of(observationA.getId(), observationA)),
                "An earlier lookup should remain an independent snapshot");

        require(store.findByIds(
                        Set.of(serviceA),
                        Set.of(observationA.getId())
                ).equals(Map.of(observationA.getId(), observationA)),
                "Earlier evidence should remain retrievable after new checks");

        try {
            scoped.clear();
            throw new AssertionError("Lookup results should be unmodifiable");
        } catch (UnsupportedOperationException expected) {
            // The lookup result correctly rejects modification.
        }

        System.out.println("ObservationLookupChecks passed");
    }

    private static HealthCheckResult observation(UUID serviceId) {
        return new HealthCheckResult(
                UUID.randomUUID(),
                serviceId,
                Instant.parse("2026-11-19T12:00:00Z"),
                HealthCheckResult.Outcome.FAIL,
                OptionalLong.empty(),
                "Test observation"
        );
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}