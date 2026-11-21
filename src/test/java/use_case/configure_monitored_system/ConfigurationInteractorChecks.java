package use_case.configure_monitored_system;

import entity.Dependency;
import entity.MonitoredSystem;
import entity.MonitoringTarget;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class ConfigurationInteractorChecks {
    private static final UUID SYSTEM = UUID.randomUUID();
    private static final UUID API = UUID.randomUUID();
    private static final UUID DATABASE = UUID.randomUUID();
    private static final Dependency API_REQUIRES_DATABASE = new Dependency(UUID.randomUUID(), API, DATABASE);
    private static final ServiceInput HTTP = http("API", " https://localhost:8443/health?verbose=true ", "1000");
    private static final ServiceInput DOCKER = new ServiceInput(DATABASE, "Database", MonitoringTarget.Method.DOCKER,
            " database-1 ", "2000", "2.000001", "2", "1");

    public static void main(String[] args) {
        savesAndRetrievesServiceAndDependencyEdits();
        rejectsInvalidDraftsBeforeWriting();
        reportsStorageErrorsAndAllowsRetry();
        System.out.println("UC1 interactor checks passed.");
    }

    private static void savesAndRetrievesServiceAndDependencyEdits() {
        MemoryRepository repository = new MemoryRepository();
        Output output = new Output();
        ConfigurationInputBoundary input = new ConfigurationInteractor(repository, output);
        input.loadSystems();
        require(output.systems.isEmpty(), "A new repository has no saved systems");

        input.save(draft(List.of(HTTP, DOCKER), List.of(API_REQUIRES_DATABASE)));
        MonitoredSystem saved = output.saved;
        require(saved.getId().equals(SYSTEM) && saved.getName().equals("Sentinel test"), "System identity and name");
        require(saved.getDescription().equals("Service configuration"), "System description");
        require(saved.getServices().get(0).getMonitoringTarget().getEndpoint().orElseThrow().getPort() == 8443,
                "HTTP endpoint port");
        require(saved.getServices().get(1).getMonitoringTarget().getContainerId().orElseThrow().equals("database-1"),
                "Docker container");
        require(saved.getServices().get(0).getMonitoringPolicy().getCheckInterval().equals(Duration.ofMillis(1000)),
                "Check interval");
        require(saved.getServices().get(1).getMonitoringPolicy().getTimeout().equals(Duration.ofNanos(2000001)),
                "Timeout retains sub-millisecond precision");
        require(saved.getDependencies().get(0).getRequiredServiceId().equals(DATABASE), "Dependency direction");

        input.save(draft(List.of(http("Renamed API", "http://localhost/ready", "3000")), List.of()));
        input.loadSystems();
        MonitoredSystem edited = output.systems.get(0);
        require(output.systems.size() == 1 && edited.getId().equals(SYSTEM), "Editing replaces the original system");
        require(edited.getServices().size() == 1 && edited.getDependencies().isEmpty(), "Service and edge removal");
        require(edited.getServices().get(0).getId().equals(API)
                        && edited.getServices().get(0).getName().equals("Renamed API")
                        && edited.getServices().get(0).getMonitoringPolicy().getCheckInterval().toMillis() == 3000,
                "Editing retains service identity and updates its configuration");
    }

    private static void rejectsInvalidDraftsBeforeWriting() {
        MemoryRepository repository = new MemoryRepository();
        Output output = new Output();
        ConfigurationInputBoundary input = new ConfigurationInteractor(repository, output);
        input.save(draft(List.of(HTTP, DOCKER), List.of(API_REQUIRES_DATABASE)));
        MonitoredSystem original = repository.saved;

        for (String url : List.of("ftp://localhost", "http://", "http://localhost:0", "http://localhost:65536",
                "http://localhost:-1", "http://localhost:", "https://user:secret@localhost", "http://localhost/#test")) {
            expectInvalid(input, output, draft(List.of(http("API", url, "1000")), List.of()), "valid port");
        }
        for (String interval : List.of("0", "-1", "three", "9223372036854775807")) {
            expectInvalid(input, output, draft(List.of(http("API", "https://localhost", interval)), List.of()),
                    "Check interval");
        }

        expectInvalid(input, output, draft(List.of(http(" ", "https://localhost", "1000")), List.of()), "Service name");
        expectInvalid(input, output, draft(List.of(new ServiceInput(DATABASE, "DB", MonitoringTarget.Method.DOCKER,
                "", "1000", "1000", "1", "1")), List.of()), "Container ID");
        expectInvalid(input, output, draft(List.of(new ServiceInput(DATABASE, "DB", MonitoringTarget.Method.DOCKER,
                "db", "1000", "1000", "1", "0")), List.of()), "Recovery threshold");
        expectInvalid(input, output, draft(List.of(new ServiceInput(DATABASE, "DB", MonitoringTarget.Method.DOCKER,
                "db", "1000", "invalid", "1", "1")), List.of()), "Timeout");
        expectInvalid(input, output, draft(List.of(new ServiceInput(DATABASE, "DB", MonitoringTarget.Method.DOCKER,
                "db", "1000", "1000", "2147483648", "1")), List.of()), "Failure threshold");
        expectInvalid(input, output, draft(List.of(http("database", "https://localhost", "1000"), DOCKER), List.of()),
                "unique");
        expectInvalid(input, output, draft(List.of(HTTP), List.of(API_REQUIRES_DATABASE)), "endpoints");
        Dependency duplicate = new Dependency(UUID.randomUUID(), API, DATABASE);
        expectInvalid(input, output, draft(List.of(HTTP, DOCKER), List.of(API_REQUIRES_DATABASE, duplicate)), "unique");
        expectInvalid(input, output, new ConfigurationInput(SYSTEM, " ", "", List.of(), List.of()), "System name");

        require(repository.saves == 1 && repository.saved == original, "Invalid drafts must never alter saved data");
    }

    private static void reportsStorageErrorsAndAllowsRetry() {
        MemoryRepository repository = new MemoryRepository();
        Output output = new Output();
        ConfigurationInputBoundary input = new ConfigurationInteractor(repository, output);
        ConfigurationInput draft = draft(List.of(HTTP), List.of());
        repository.unavailable = true;
        input.loadSystems();
        require(output.error.contains("Could not load") && output.systems == null, "Report read failure");
        input.save(draft);
        require(output.error.contains("Could not save") && output.saved == null, "Report write failure");

        repository.unavailable = false;
        input.save(draft);
        require(output.saved != null && repository.saves == 1, "The same draft can be retried");
    }

    private static void expectInvalid(ConfigurationInputBoundary input, Output output, ConfigurationInput draft,
                                      String message) {
        output.error = null;
        output.saved = null;
        input.save(draft);
        require(output.error != null && output.error.contains(message) && output.saved == null,
                "Validation must report " + message);
    }

    private static ConfigurationInput draft(List<ServiceInput> services, List<Dependency> dependencies) {
        return new ConfigurationInput(SYSTEM, " Sentinel test ", "Service configuration", services, dependencies);
    }

    private static ServiceInput http(String name, String url, String interval) {
        return new ServiceInput(API, name, MonitoringTarget.Method.HTTP, url, interval, "500", "3", "2");
    }

    private static void require(boolean passed, String message) {
        if (!passed) {
            throw new AssertionError(message);
        }
    }

    private static final class MemoryRepository implements ConfigurationRepository {
        private MonitoredSystem saved;
        private boolean unavailable;
        private int saves;

        @Override
        public List<MonitoredSystem> findAll() {
            checkStorage();
            return saved == null ? List.of() : List.of(saved);
        }

        @Override
        public Optional<MonitoredSystem> findById(UUID systemId) {
            return findAll().stream().filter(system -> system.getId().equals(systemId)).findFirst();
        }

        @Override
        public void save(MonitoredSystem system) {
            checkStorage();
            saved = system;
            saves++;
        }

        private void checkStorage() {
            if (unavailable) {
                throw new ConfigurationStoreException("Storage unavailable", new IllegalStateException());
            }
        }
    }

    private static final class Output implements ConfigurationOutputBoundary {
        private MonitoredSystem saved;
        private List<MonitoredSystem> systems;
        private String error;

        @Override
        public void presentSystems(List<MonitoredSystem> systems) {
            this.systems = systems;
        }

        @Override
        public void presentSaved(MonitoredSystem system) {
            saved = system;
        }

        @Override
        public void presentError(String message) {
            error = message;
        }
    }
}
