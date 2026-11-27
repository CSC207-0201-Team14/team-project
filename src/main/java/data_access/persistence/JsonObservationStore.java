package data_access.persistence;

import com.google.gson.JsonObject;
import entity.HealthCheckResult;
import use_case.monitor_service_health.ObservationLookup;
import use_case.monitor_service_health.ObservationStore;
import use_case.monitor_service_health.ObservationStoreException;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * One immutable observation per UUID-named JSON file. Shared by UC2, UC3 and UC4 in one application.
 * Writes use a temporary file and an atomic move; unsupported atomic moves fail rather than weakening
 * that guarantee. Calls on this instance are serialized. Multiple application processes sharing the
 * same directory are not supported. History reads scan the directory; there is no retention policy yet.
 */
public final class JsonObservationStore implements ObservationStore, ObservationLookup {
    private static final int SCHEMA_VERSION = 1;
    private static final Comparator<HealthCheckResult> CHRONOLOGICAL =
            Comparator.comparing(HealthCheckResult::getTimestamp).thenComparing(HealthCheckResult::getId);
    private final Path directory;

    public JsonObservationStore(Path directory) {
        this.directory = Objects.requireNonNull(directory, "directory");
    }

    /** Re-saving identical data is harmless; an existing observation ID cannot be rewritten. */
    @Override
    public synchronized void save(HealthCheckResult observation) {
        Objects.requireNonNull(observation, "observation");
        Path file = fileFor(observation.getId());
        Optional<HealthCheckResult> previous = readIfPresent(file);
        if (previous.isPresent()) {
            if (!sameObservation(previous.get(), observation)) {
                throw new ObservationStoreException("Observation " + observation.getId()
                        + " already exists with different data; historical evidence was not changed.", null);
            }
            return;
        }
        try {
            JsonObject document = JsonCodec.encodeHealthCheckResult(observation);
            document.addProperty("schemaVersion", SCHEMA_VERSION);
            Files.createDirectories(directory);
            Path temporary = Files.createTempFile(directory, "observation-", ".tmp");
            try {
                Files.writeString(temporary, JsonCodec.toJson(document));
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException | RuntimeException exception) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException | SecurityException cleanupFailure) {
                    exception.addSuppressed(cleanupFailure);
                }
                throw exception;
            }
        } catch (IOException | RuntimeException exception) {
            throw new ObservationStoreException("Could not save observation file " + file + ".", exception);
        }
    }

    @Override
    public synchronized List<HealthCheckResult> findByServiceId(UUID serviceId) {
        Objects.requireNonNull(serviceId, "serviceId");
        if (isMissing(directory)) {
            return List.of();
        }
        List<HealthCheckResult> history = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "*.json")) {
            for (Path file : files) {
                // A corrupt or disappearing record must not silently shorten a failure/recovery streak.
                HealthCheckResult observation = readIfPresent(file).orElseThrow(() ->
                        new ObservationStoreException("Observation file " + file
                                + " disappeared while history was being read.", null));
                if (observation.getServiceId().equals(serviceId)) {
                    history.add(observation);
                }
            }
        } catch (IOException | DirectoryIteratorException | SecurityException exception) {
            throw new ObservationStoreException("Could not read observations directory " + directory + ".", exception);
        }
        history.sort(CHRONOLOGICAL);
        return List.copyOf(history);
    }

    @Override
    public synchronized Map<UUID, HealthCheckResult> findByIds(Set<UUID> allowedServiceIds,
                                                               Set<UUID> observationIds) {
        Set<UUID> allowed = Set.copyOf(allowedServiceIds);
        Set<UUID> requested = Set.copyOf(observationIds);
        if (allowed.isEmpty() || requested.isEmpty()) {
            return Map.of();
        }
        Map<UUID, HealthCheckResult> found = new HashMap<>();
        for (UUID id : requested) {
            Optional<HealthCheckResult> observation = readIfPresent(fileFor(id));
            if (observation.isPresent() && allowed.contains(observation.get().getServiceId())) {
                found.put(id, observation.get());
            }
        }
        return Map.copyOf(found);
    }

    private Path fileFor(UUID id) {
        return directory.resolve(id + ".json");
    }

    private static Optional<HealthCheckResult> readIfPresent(Path file) {
        try (Reader reader = Files.newBufferedReader(file)) {
            JsonObject json = JsonCodec.readObject(reader);
            if (JsonCodec.readInt(json, "schemaVersion") != SCHEMA_VERSION) {
                throw new IllegalArgumentException("Unsupported observation schema version");
            }
            HealthCheckResult observation = JsonCodec.readHealthCheckResult(json);
            if (!file.getFileName().toString().equals(observation.getId() + ".json")) {
                throw new IllegalArgumentException("Observation ID does not match its filename");
            }
            return Optional.of(observation);
        } catch (NoSuchFileException exception) {
            if (isMissing(file)) {
                return Optional.empty();
            }
            throw new ObservationStoreException("Observation file " + file + " is a dangling link.", exception);
        } catch (IOException | RuntimeException exception) {
            throw new ObservationStoreException("Could not read observation file " + file + ".", exception);
        }
    }

    private static boolean isMissing(Path path) {
        try {
            // False also covers indeterminate access; the actual open/list then reports the failure.
            return Files.notExists(path, LinkOption.NOFOLLOW_LINKS);
        } catch (SecurityException exception) {
            throw new ObservationStoreException("Could not access observations at " + path + ".", exception);
        }
    }

    private static boolean sameObservation(HealthCheckResult first, HealthCheckResult second) {
        return first.getId().equals(second.getId())
                && first.getServiceId().equals(second.getServiceId())
                && first.getTimestamp().equals(second.getTimestamp())
                && first.getOutcome() == second.getOutcome()
                && first.getResponseTimeMs().equals(second.getResponseTimeMs())
                && first.getMessage().equals(second.getMessage());
    }
}
