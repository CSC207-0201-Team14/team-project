package data_access.configuration;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.Strictness;
import entity.Dependency;
import entity.MonitoredSystem;
import entity.MonitoringPolicy;
import entity.MonitoringTarget;
import entity.Service;
import use_case.configure_monitored_system.ConfigurationRepository;
import use_case.configure_monitored_system.ConfigurationStoreException;

import java.io.IOException;
import java.io.Reader;
import java.net.URI;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.DateTimeException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class JsonConfigurationRepository implements ConfigurationRepository {
    private static final Gson JSON = new GsonBuilder().setStrictness(Strictness.STRICT).setPrettyPrinting().create();
    private static final String EXTENSION = ".json";

    private final Path directory;

    public JsonConfigurationRepository(Path directory) {
        this.directory = Objects.requireNonNull(directory, "directory");
    }

    @Override
    public List<MonitoredSystem> findAll() {
        List<MonitoredSystem> systems = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "*" + EXTENSION)) {
            for (Path file : files) {
                String name = file.getFileName().toString();
                UUID id = UUID.fromString(name.substring(0, name.length() - EXTENSION.length()));
                read(file, id).ifPresent(systems::add);
            }
        } catch (NoSuchFileException exception) {
            return List.of();
        } catch (IOException | DirectoryIteratorException | IllegalArgumentException | SecurityException exception) {
            throw new ConfigurationStoreException("Could not read the systems directory.", exception);
        }
        systems.sort(Comparator.comparing(MonitoredSystem::getName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(MonitoredSystem::getId));
        return List.copyOf(systems);
    }

    @Override
    public Optional<MonitoredSystem> findById(UUID systemId) {
        return read(configurationPath(systemId), systemId);
    }

    private Optional<MonitoredSystem> read(Path file, UUID systemId) {
        try (Reader reader = Files.newBufferedReader(file)) {
            MonitoredSystem system = decode(JSON.fromJson(reader, JsonObject.class));
            if (!systemId.equals(system.getId())) {
                throw new IllegalArgumentException("System ID does not match its filename.");
            }
            return Optional.of(system);
        } catch (NoSuchFileException exception) {
            return Optional.empty();
        } catch (IOException | JsonParseException | DateTimeException | IllegalArgumentException
                 | IllegalStateException | NullPointerException | ArithmeticException | ClassCastException
                 | SecurityException exception) {
            throw new ConfigurationStoreException("Could not read " + file.getFileName() + ".", exception);
        }
    }

    @Override
    public void save(MonitoredSystem system) {
        try {
            Files.createDirectories(directory);
            Path temporary = Files.createTempFile(directory, "system-", ".tmp");
            try {
                Files.writeString(temporary, JSON.toJson(encode(system)) + System.lineSeparator());
                Files.move(temporary, configurationPath(system.getId()),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException | SecurityException exception) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException | SecurityException cleanupFailure) {
                    exception.addSuppressed(cleanupFailure);
                }
                throw exception;
            }
        } catch (IOException | SecurityException exception) {
            throw new ConfigurationStoreException("Could not write the system file.", exception);
        }
    }

    private Path configurationPath(UUID id) {
        return directory.resolve(Objects.requireNonNull(id, "id") + EXTENSION);
    }

    private static JsonObject encode(MonitoredSystem system) {
        JsonObject json = new JsonObject();
        json.addProperty("id", system.getId().toString());
        json.addProperty("name", system.getName());
        json.addProperty("description", system.getDescription());

        JsonArray services = new JsonArray();
        for (Service service : system.getServices()) {
            JsonObject entry = new JsonObject();
            MonitoringTarget target = service.getMonitoringTarget();
            MonitoringPolicy policy = service.getMonitoringPolicy();
            entry.addProperty("id", service.getId().toString());
            entry.addProperty("name", service.getName());
            entry.addProperty("method", target.getMethod().name());
            entry.addProperty("target", target.getEndpoint().map(URI::toString)
                    .orElseGet(() -> target.getContainerId().orElseThrow()));
            entry.addProperty("interval", policy.getCheckInterval().toString());
            entry.addProperty("timeout", policy.getTimeout().toString());
            entry.addProperty("failureThreshold", policy.getFailureThreshold());
            entry.addProperty("recoveryThreshold", policy.getRecoveryThreshold());
            services.add(entry);
        }
        json.add("services", services);

        JsonArray dependencies = new JsonArray();
        for (Dependency dependency : system.getDependencies()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("id", dependency.getId().toString());
            entry.addProperty("dependentServiceId", dependency.getDependentServiceId().toString());
            entry.addProperty("requiredServiceId", dependency.getRequiredServiceId().toString());
            dependencies.add(entry);
        }
        json.add("dependencies", dependencies);
        return json;
    }

    private static MonitoredSystem decode(JsonObject json) {
        UUID systemId = readId(json, "id");
        List<Service> services = new ArrayList<>();
        for (JsonElement element : json.getAsJsonArray("services")) {
            JsonObject entry = element.getAsJsonObject();
            MonitoringTarget.Method method = MonitoringTarget.Method.valueOf(readString(entry, "method"));
            String targetValue = readString(entry, "target");
            MonitoringTarget target = method == MonitoringTarget.Method.HTTP
                    ? MonitoringTarget.http(URI.create(targetValue)) : MonitoringTarget.docker(targetValue);
            MonitoringPolicy policy = new MonitoringPolicy(Duration.parse(readString(entry, "interval")),
                    Duration.parse(readString(entry, "timeout")), readInt(entry, "failureThreshold"),
                    readInt(entry, "recoveryThreshold"));
            services.add(new Service(readId(entry, "id"), systemId, readString(entry, "name"), target, policy));
        }

        List<Dependency> dependencies = new ArrayList<>();
        for (JsonElement element : json.getAsJsonArray("dependencies")) {
            JsonObject entry = element.getAsJsonObject();
            dependencies.add(new Dependency(readId(entry, "id"), readId(entry, "dependentServiceId"),
                    readId(entry, "requiredServiceId")));
        }
        return new MonitoredSystem(systemId, readString(json, "name"), readString(json, "description"),
                services, dependencies);
    }

    private static UUID readId(JsonObject json, String field) {
        return UUID.fromString(readString(json, field));
    }

    private static String readString(JsonObject json, String field) {
        if (!json.getAsJsonPrimitive(field).isString()) {
            throw new IllegalArgumentException("Expected text for " + field);
        }
        return json.get(field).getAsString();
    }

    private static int readInt(JsonObject json, String field) {
        if (!json.getAsJsonPrimitive(field).isNumber()) {
            throw new IllegalArgumentException("Expected a number for " + field);
        }
        return json.get(field).getAsBigDecimal().intValueExact();
    }
}
