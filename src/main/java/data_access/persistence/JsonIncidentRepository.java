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

    public JsonIncidentRepository() {
        this(Path.of(System.getProperty("user.home"), ".sentinel", "incidents"));
    }

    public JsonIncidentRepository(Path directory) {
        this.directory = Objects.requireNonNull(directory, "directory");
    }

    @Override
    public Optional<Incident> findById(UUID incidentId) {
        Path file = directory.resolve(Objects.requireNonNull(incidentId, "incidentId") + ".json");
        try {
            if (Files.notExists(file, LinkOption.NOFOLLOW_LINKS)
                    && (Files.isDirectory(directory) || Files.notExists(directory, LinkOption.NOFOLLOW_LINKS))) {
                return Optional.empty();
            }
            return Optional.of(read(file));
        } catch (SecurityException exception) {
            throw new IncidentDataException("Could not access " + file + ".", exception);
        }
    }

    @Override
    public List<Incident> findBySystemId(UUID systemId) {
        return readSystemIncidents(systemId, true);
    }

    @Override
    public List<Incident> findUnresolvedBySystemId(UUID systemId) {
        return readSystemIncidents(systemId, false);
    }

    private List<Incident> readSystemIncidents(UUID systemId, boolean includeResolved) {
        List<Incident> incidents = new ArrayList<>();
        try {
            if (Files.notExists(directory, LinkOption.NOFOLLOW_LINKS)) {
                return List.of();
            }
            try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "*.json")) {
                for (Path file : files) {
                    Incident incident = read(file);
                    if (incident.getSystemId().equals(systemId)
                            && (includeResolved || incident.getStatus() != Incident.Status.RESOLVED)) {
                        incidents.add(incident);
                    }
                }
            }
        } catch (IOException | DirectoryIteratorException | SecurityException exception) {
            throw new IncidentDataException("Could not read the incidents directory " + directory + ".", exception);
        }
        return List.copyOf(incidents);
    }

    private static Incident read(Path file) {
        try (Reader reader = Files.newBufferedReader(file)) {
            Incident incident = IncidentJsonCodec.decode(JsonCodec.readObject(reader));
            if (!file.getFileName().toString().equals(incident.getId() + ".json")) {
                throw new IllegalArgumentException("Incident ID does not match its filename");
            }
            return incident;
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
