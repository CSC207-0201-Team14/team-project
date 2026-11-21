package data_access.configuration;

import entity.Dependency;
import entity.MonitoredSystem;
import entity.MonitoringPolicy;
import entity.MonitoringTarget;
import entity.Service;
import use_case.configure_monitored_system.ConfigurationStoreException;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class JsonConfigurationRepositoryChecks {
    public static void main(String[] args) throws IOException {
        Path directory = Files.createTempDirectory("sentinel-config-checks");
        try {
            roundTripsAndReplacesSystem(directory.resolve("systems"));
            handlesStorageErrors(directory);
        } finally {
            try (Stream<Path> files = Files.walk(directory)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).collect(Collectors.toList())) {
                    Files.delete(file);
                }
            }
        }
        System.out.println("UC1 JSON repository checks passed.");
    }

    private static void roundTripsAndReplacesSystem(Path directory) throws IOException {
        JsonConfigurationRepository writer = new JsonConfigurationRepository(directory);
        UUID systemId = UUID.randomUUID();
        UUID otherId = UUID.randomUUID();
        require(writer.findAll().isEmpty() && writer.findById(systemId).isEmpty(), "Missing directory is empty");
        MonitoringPolicy policy = new MonitoringPolicy(Duration.ofMillis(250), Duration.ofNanos(2000001), 4, 2);
        URI endpoint = URI.create("https://localhost:8443/health?q=a%2Fb");
        Service http = new Service(UUID.randomUUID(), systemId, "Order API", MonitoringTarget.http(endpoint), policy);
        Service docker = new Service(UUID.randomUUID(), systemId, "Order DB", MonitoringTarget.docker("order-db"), policy);
        Dependency edge = new Dependency(UUID.randomUUID(), http.getId(), docker.getId());
        String description = "Development setup\nHealth: \"✓\"";
        writer.save(new MonitoredSystem(systemId, "Orders", description, List.of(http, docker), List.of(edge)));
        writer.save(new MonitoredSystem(otherId, "Another system", "", List.of(), List.of()));

        JsonConfigurationRepository reader = new JsonConfigurationRepository(directory);
        MonitoredSystem loaded = reader.findById(systemId).orElseThrow();
        List<Service> loadedServices = loaded.getServices();
        require(loaded.getId().equals(systemId) && loaded.getName().equals("Orders")
                && loaded.getDescription().equals(description), "System metadata survives reload");
        require(reader.findAll().size() == 2 && reader.findAll().get(0).getId().equals(otherId), "Sorted system list");
        require(loadedServices.get(0).getId().equals(http.getId())
                && loadedServices.get(0).getSystemId().equals(systemId)
                && loadedServices.get(0).getName().equals("Order API")
                && loadedServices.get(0).getMonitoringTarget().getEndpoint().orElseThrow().equals(endpoint),
                "HTTP identity and target");
        require(loadedServices.get(1).getId().equals(docker.getId())
                && loadedServices.get(1).getMonitoringTarget().getContainerId().orElseThrow().equals("order-db"),
                "Docker identity and target");
        MonitoringPolicy loadedPolicy = loadedServices.get(0).getMonitoringPolicy();
        require(loadedPolicy.getCheckInterval().equals(policy.getCheckInterval())
                && loadedPolicy.getTimeout().equals(policy.getTimeout())
                && loadedPolicy.getFailureThreshold() == 4 && loadedPolicy.getRecoveryThreshold() == 2,
                "Policy survives reload with full duration precision");
        Dependency loadedEdge = loaded.getDependencies().get(0);
        require(loadedEdge.getId().equals(edge.getId()) && loadedEdge.getDependentServiceId().equals(http.getId())
                && loadedEdge.getRequiredServiceId().equals(docker.getId()), "Directed edge survives reload");

        writer.save(new MonitoredSystem(systemId, "Updated Orders", "", List.of(http), List.of()));
        loaded = reader.findById(systemId).orElseThrow();
        require(loaded.getName().equals("Updated Orders") && loaded.getServices().size() == 1
                && loaded.getDependencies().isEmpty() && reader.findById(otherId).isPresent(),
                "Replacing one configuration preserves the others");
        try (Stream<Path> files = Files.list(directory)) {
            require(files.allMatch(path -> path.getFileName().toString().endsWith(".json")),
                    "No temporary files remain after saving");
        }
    }

    private static void handlesStorageErrors(Path directory) throws IOException {
        UUID id = UUID.randomUUID();
        Path fileInsteadOfDirectory = Files.createFile(directory.resolve("blocked"));
        JsonConfigurationRepository inaccessible = new JsonConfigurationRepository(fileInsteadOfDirectory);
        expectStorageError(inaccessible::findAll);
        expectStorageError(() -> inaccessible.save(new MonitoredSystem(id, "System", "", List.of(), List.of())));

        Path corrupt = Files.writeString(directory.resolve(id + ".json"), "{ broken json");
        JsonConfigurationRepository reader = new JsonConfigurationRepository(directory);
        expectStorageError(() -> reader.findById(id));
        expectStorageError(reader::findAll);
        require(Files.readString(corrupt).equals("{ broken json"), "Reading invalid data leaves the file untouched");
        Files.writeString(corrupt, "{\"id\": \"" + id + "\", \"services\": {}}");
        expectStorageError(() -> reader.findById(id));

        Files.delete(corrupt);
        Files.createDirectory(corrupt);
        expectStorageError(() -> reader.save(new MonitoredSystem(id, "System", "", List.of(), List.of())));
        require(Files.isDirectory(corrupt), "A failed replacement preserves the destination");
        try (Stream<Path> files = Files.list(directory)) {
            require(files.noneMatch(path -> path.getFileName().toString().endsWith(".tmp")),
                    "Failed replacements remove their temporary files");
        }
    }

    private static void expectStorageError(Runnable readOrWrite) {
        try {
            readOrWrite.run();
            throw new AssertionError("Expected a storage exception");
        } catch (ConfigurationStoreException expected) {
            require(expected.getCause() != null, "Storage error retains the underlying exception");
        }
    }

    private static void require(boolean passed, String message) {
        if (!passed) {
            throw new AssertionError(message);
        }
    }
}
