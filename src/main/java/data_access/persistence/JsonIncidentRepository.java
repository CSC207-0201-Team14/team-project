package data_access.persistence;

import entity.Incident;
import use_case.shared.IncidentDataException;
import use_case.shared.IncidentRepository;

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
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** One complete incident per file, shared by investigation and recovery. */
public final class JsonIncidentRepository implements IncidentRepository {
    private final Path directory;

    public JsonIncidentRepository(Path directory) {
        this.directory = Objects.requireNonNull(directory, "directory");
    }

    @Override
    public Optional<Incident> findById(UUID incidentId) {
        return readIfPresent(directory.resolve(Objects.requireNonNull(incidentId, "incidentId") + ".json"));
    }

    @Override
    public List<Incident> findBySystemId(UUID systemId) {
        if (isMissing(directory)) {
            return List.of();
        }
        List<Incident> incidents = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "*.json")) {
            for (Path file : files) {
                // A listed file that vanishes may have held an unresolved incident, so it is not skipped.
                Incident incident = readIfPresent(file).orElseThrow(() -> new IncidentDataException(
                        "Incident file " + file + " disappeared while it was being read."));
                if (incident.getSystemId().equals(systemId)) {
                    incidents.add(incident);
                }
            }
        } catch (IOException | DirectoryIteratorException | SecurityException exception) {
            throw new IncidentDataException("Could not read the incidents directory " + directory + ".", exception);
        }
        return List.copyOf(incidents);
    }

    private static boolean isMissing(Path path) {
        try {
            return Files.notExists(path, LinkOption.NOFOLLOW_LINKS);
        } catch (SecurityException exception) {
            throw new IncidentDataException("Could not access " + path + ".", exception);
        }
    }

    /**
     * Empty only when no file exists at the path, checked by opening it so a concurrent removal cannot turn
     * into a read failure. A dangling link still means a record was expected, so it is a data failure.
     */
    private static Optional<Incident> readIfPresent(Path file) {
        try (Reader reader = Files.newBufferedReader(file)) {
            Incident incident = IncidentJsonCodec.decode(JsonCodec.readObject(reader));
            if (!file.getFileName().toString().equals(incident.getId() + ".json")) {
                throw new IllegalArgumentException("Incident ID does not match its filename");
            }
            return Optional.of(incident);
        } catch (NoSuchFileException missing) {
            if (isMissing(file)) {
                return Optional.empty();
            }
            throw new IncidentDataException("Incident file " + file + " is a dangling link.", missing);
        } catch (IOException | RuntimeException exception) {
            throw new IncidentDataException("Could not read incident file " + file + ".", exception);
        }
    }

    @Override
    public void save(Incident incident) {
        Path file = directory.resolve(incident.getId() + ".json");
        try {
            String document = JsonCodec.toJson(IncidentJsonCodec.encode(incident));
            Files.createDirectories(directory);
            Path temporary = Files.createTempFile(directory, "incident-", ".tmp");
            try {
                Files.writeString(temporary, document);
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
            throw new IncidentDataException("Could not save incident file " + file + ".", exception);
        }
    }
}
