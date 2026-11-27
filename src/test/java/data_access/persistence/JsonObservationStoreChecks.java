package data_access.persistence;

import com.google.gson.JsonObject;
import entity.HealthCheckResult;
import use_case.monitor_service_health.ObservationStoreException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Executable integration checks using an isolated directory; no Docker or network is required. */
public final class JsonObservationStoreChecks {
    private static final Instant TIME = Instant.parse("2026-11-25T12:00:00.123456789Z");

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory(Files.createDirectories(Path.of("target")), "observation-store-");
        try {
            roundTripsAndSortsAfterRestart(root.resolve("round-trip"));
            scopesLookupAndPreservesSnapshots(root.resolve("lookup"));
            protectsExistingEvidence(root.resolve("identity"));
            rejectsCorruption(root.resolve("corruption"));
            reportsInvalidStoragePaths(root.resolve("invalid-paths"));
            serializesConcurrentAccess(root.resolve("concurrent"));
        } finally {
            List<Path> created;
            try (Stream<Path> paths = Files.walk(root)) {
                created = paths.sorted(Comparator.reverseOrder()).collect(Collectors.toList());
            }
            for (Path path : created) {
                Files.delete(path);
            }
        }
        System.out.println("JsonObservationStoreChecks passed");
    }

    private static void roundTripsAndSortsAfterRestart(Path directory) {
        UUID serviceId = UUID.randomUUID();
        JsonObservationStore first = new JsonObservationStore(directory);
        require(first.findByServiceId(serviceId).isEmpty(), "A missing store starts with empty history");
        HealthCheckResult pass = observation(UUID.fromString("00000000-0000-0000-0000-000000000001"), serviceId,
                TIME, HealthCheckResult.Outcome.PASS, OptionalLong.of(0), "正常 ✓");
        HealthCheckResult fail = observation(UUID.fromString("00000000-0000-0000-0000-000000000002"), serviceId,
                TIME, HealthCheckResult.Outcome.FAIL, OptionalLong.of(Long.MAX_VALUE), "HTTP 503\nretry later");
        HealthCheckResult error = observation(UUID.randomUUID(), serviceId, TIME.plusNanos(1),
                HealthCheckResult.Outcome.MONITORING_ERROR, OptionalLong.empty(), "Connection unavailable");
        first.save(error);
        first.save(fail);
        first.save(pass);
        List<HealthCheckResult> reopened = new JsonObservationStore(directory).findByServiceId(serviceId);
        require(reopened.stream().map(HealthCheckResult::getId).collect(Collectors.toList())
                        .equals(List.of(pass.getId(), fail.getId(), error.getId())),
                "Restarted history must sort by timestamp then UUID, independently of insertion order");
        require(same(reopened.get(0), pass) && same(reopened.get(1), fail) && same(reopened.get(2), error),
                "Every field, outcome, nanosecond timestamp and optional duration must survive restart");
    }

    private static void scopesLookupAndPreservesSnapshots(Path directory) throws Exception {
        JsonObservationStore store = new JsonObservationStore(directory);
        UUID serviceA = UUID.randomUUID();
        UUID serviceB = UUID.randomUUID();
        HealthCheckResult a = result(serviceA);
        HealthCheckResult b = result(serviceB);
        store.save(a);
        store.save(b);
        UUID missing = UUID.randomUUID();
        Set<UUID> requested = Set.of(a.getId(), b.getId(), missing);
        Map<UUID, HealthCheckResult> scoped = store.findByIds(Set.of(serviceA), requested);
        require(scoped.size() == 1 && same(scoped.get(a.getId()), a),
                "Missing and out-of-scope observations must be omitted");
        require(store.findByIds(Set.of(serviceA, serviceB), requested).size() == 2,
                "Both allowed services must be readable");
        require(store.findByIds(Set.of(), requested).isEmpty()
                        && store.findByIds(Set.of(serviceA), Set.of()).isEmpty(),
                "An empty scope or request must return no observations");
        List<HealthCheckResult> snapshot = store.findByServiceId(serviceA);
        store.save(result(serviceA));
        require(snapshot.size() == 1 && scoped.size() == 1, "Previously returned collections are independent snapshots");
        expect(UnsupportedOperationException.class, () -> scoped.put(b.getId(), b),
                "Lookup results cannot be modified");
        expect(UnsupportedOperationException.class, () -> snapshot.add(b), "History results cannot be modified");
        Files.writeString(directory.resolve("unfinished.tmp"), "an interrupted temporary write");
        require(store.findByServiceId(serviceA).size() == 2, "Uncommitted temporary files are not observations");
    }

    private static void protectsExistingEvidence(Path directory) throws Exception {
        JsonObservationStore store = new JsonObservationStore(directory);
        HealthCheckResult original = result(UUID.randomUUID());
        store.save(original);
        Path file = directory.resolve(original.getId() + ".json");
        String document = Files.readString(file);
        store.save(observation(original.getId(), original.getServiceId(), original.getTimestamp(),
                original.getOutcome(), original.getResponseTimeMs(), original.getMessage()));
        require(Files.readString(file).equals(document), "Re-saving identical data must be idempotent");
        HealthCheckResult conflict = observation(original.getId(), original.getServiceId(), TIME,
                HealthCheckResult.Outcome.PASS, OptionalLong.empty(), "Changed evidence");
        expect(ObservationStoreException.class, () -> store.save(conflict), "Conflicting IDs cannot overwrite evidence");
        require(Files.readString(file).equals(document), "A rejected save must preserve the original record");
    }

    private static void rejectsCorruption(Path root) throws Exception {
        UUID serviceId = UUID.randomUUID();
        HealthCheckResult observation = result(serviceId);
        JsonObject valid = JsonCodec.encodeHealthCheckResult(observation);
        valid.addProperty("schemaVersion", 1);
        assertUnreadable(root.resolve("malformed"), observation, "{not json");
        JsonObject wrongVersion = valid.deepCopy();
        wrongVersion.addProperty("schemaVersion", 2);
        assertUnreadable(root.resolve("version"), observation, JsonCodec.toJson(wrongVersion));
        JsonObject wrongId = valid.deepCopy();
        wrongId.addProperty("id", UUID.randomUUID().toString());
        assertUnreadable(root.resolve("filename"), observation, JsonCodec.toJson(wrongId));
        JsonObject negativeTime = valid.deepCopy();
        negativeTime.addProperty("responseTimeMs", -1);
        assertUnreadable(root.resolve("negative"), observation, JsonCodec.toJson(negativeTime));
        JsonObject fractionalTime = valid.deepCopy();
        fractionalTime.addProperty("responseTimeMs", 1.5);
        assertUnreadable(root.resolve("fractional"), observation, JsonCodec.toJson(fractionalTime));
        JsonObject missingField = valid.deepCopy();
        missingField.remove("serviceId");
        assertUnreadable(root.resolve("missing-field"), observation, JsonCodec.toJson(missingField));
    }

    private static void assertUnreadable(Path directory, HealthCheckResult observation, String document)
            throws Exception {
        Files.createDirectories(directory);
        Path file = directory.resolve(observation.getId() + ".json");
        Files.writeString(file, document);
        JsonObservationStore store = new JsonObservationStore(directory);
        expect(ObservationStoreException.class, () -> store.findByServiceId(observation.getServiceId()),
                "Corruption must not silently shorten service history");
        expect(ObservationStoreException.class, () -> store.findByIds(Set.of(observation.getServiceId()),
                Set.of(observation.getId())), "A damaged requested observation is an access failure, not a missing ID");
        expect(ObservationStoreException.class, () -> store.save(observation), "Saving must not repair evidence silently");
        require(Files.readString(file).equals(document), "A failed save must leave the original file intact");
    }

    private static void reportsInvalidStoragePaths(Path root) throws Exception {
        Files.createDirectories(root);
        Path blocked = root.resolve("blocked");
        Files.writeString(blocked, "This is a regular file, not a directory");
        JsonObservationStore store = new JsonObservationStore(blocked);
        HealthCheckResult observation = result(UUID.randomUUID());
        expect(ObservationStoreException.class, () -> store.save(observation), "Invalid storage must make saves fail");
        expect(ObservationStoreException.class, () -> store.findByServiceId(observation.getServiceId()),
                "An unreadable directory must not look like empty history");
        require(Files.readString(blocked).equals("This is a regular file, not a directory"),
                "A storage failure must preserve unrelated existing data");
    }

    private static void serializesConcurrentAccess(Path directory) throws Exception {
        JsonObservationStore store = new JsonObservationStore(directory);
        UUID serviceId = UUID.randomUUID();
        ExecutorService workers = Executors.newFixedThreadPool(3);
        try {
            List<Future<?>> saved = new java.util.ArrayList<>();
            for (int i = 0; i < 12; i++) {
                saved.add(workers.submit(() -> {
                    HealthCheckResult observation = result(serviceId);
                    store.save(observation);
                    require(store.findByIds(Set.of(serviceId), Set.of(observation.getId())).size() == 1,
                            "A completed save must be readable alongside other threads' writes");
                }));
            }
            for (Future<?> task : saved) {
                task.get(5, java.util.concurrent.TimeUnit.SECONDS);
            }
            require(new JsonObservationStore(directory).findByServiceId(serviceId).size() == 12,
                    "Concurrent callers must not lose completed observations");
        } finally {
            workers.shutdownNow();
        }
    }

    private static HealthCheckResult result(UUID serviceId) {
        return observation(UUID.randomUUID(), serviceId, TIME, HealthCheckResult.Outcome.FAIL,
                OptionalLong.of(5), "HTTP 503");
    }

    private static HealthCheckResult observation(UUID id, UUID serviceId, Instant timestamp,
                                                HealthCheckResult.Outcome outcome, OptionalLong duration,
                                                String message) {
        return new HealthCheckResult(id, serviceId, timestamp, outcome, duration, message);
    }

    private static boolean same(HealthCheckResult a, HealthCheckResult b) {
        return a != null && a.getId().equals(b.getId()) && a.getServiceId().equals(b.getServiceId())
                && a.getTimestamp().equals(b.getTimestamp()) && a.getOutcome() == b.getOutcome()
                && a.getResponseTimeMs().equals(b.getResponseTimeMs()) && a.getMessage().equals(b.getMessage());
    }

    private static void expect(Class<? extends Throwable> type, Runnable action, String message) {
        try {
            action.run();
        } catch (RuntimeException exception) {
            if (type.isInstance(exception)) {
                return;
            }
            throw new AssertionError(message + ": unexpected exception", exception);
        }
        throw new AssertionError(message);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
