package app;

import com.sun.net.httpserver.HttpServer;
import data_access.configuration.JsonConfigurationRepository;
import data_access.persistence.JsonIncidentRepository;
import entity.HealthCheckResult;
import entity.HealthStatus;
import entity.Incident;
import entity.MonitoredSystem;
import entity.MonitoringPolicy;
import entity.MonitoringTarget;
import entity.Service;
import entity.ServiceHealth;
import use_case.investigate_incident.IncidentDetails;
import use_case.investigate_incident.IncidentOutputBoundary;
import use_case.investigate_incident.IncidentSummary;
import use_case.monitor_service_health.MonitoringOutputBoundary;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Real local HTTP + UC2 + UC3 + JSON checks, using isolated temporary data and no Docker. */
public final class HttpMonitoringWiringChecks {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory(Files.createDirectories(Path.of("target")), "http-monitoring-");
        try {
            httpFailureCreatesIncident(root.resolve("flow"));
            failureHistorySurvivesApplicationRestart(root.resolve("restart"));
            closingLetsAnAcceptedCheckFinish(root.resolve("shutdown"));
        } finally {
            List<Path> created;
            try (Stream<Path> paths = Files.walk(root)) {
                created = paths.sorted(Comparator.reverseOrder()).collect(Collectors.toList());
            }
            for (Path path : created) {
                Files.delete(path);
            }
        }
        System.out.println("HttpMonitoringWiringChecks passed");
    }

    private static void httpFailureCreatesIncident(Path root) throws Exception {
        AtomicInteger status = new AtomicInteger(503);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/health", exchange -> {
            try {
                exchange.sendResponseHeaders(status.get(), -1);
            } finally {
                exchange.close();
            }
        });
        server.start();
        TestClock clock = new TestClock();
        Output output = new Output();
        Service service = saveService(root, server.getAddress().getPort(), 2);
        try (Sentinel app = new Sentinel(root, clock)) {
            Sentinel.MonitoringApplication runtime = app.wireMonitoring(output, output);
            runtime.getChecks().checkOnce(service);
            require(new JsonIncidentRepository(root.resolve("incidents")).findBySystemId(service.getSystemId())
                    .isEmpty(), "One failure must not bypass the configured threshold of two");
            clock.advance();
            runtime.getChecks().checkOnce(service);
            List<Incident> incidents = new JsonIncidentRepository(root.resolve("incidents"))
                    .findBySystemId(service.getSystemId());
            require(incidents.size() == 1 && output.processed.size() == 1,
                    "The threshold crossing must reach UC3's real connector and JSON repository");
            require(output.checks.size() == 2 && output.checks.get(1).getTimestamp().equals(clock.instant())
                            && output.checks.get(1).getOutcome() == HealthCheckResult.Outcome.FAIL,
                    "HTTP observations must use the same injected clock as incident processing");
            runtime.getInvestigation().getInvestigation().openIncident(incidents.get(0).getId());
            IncidentDetails details = output.details.get(5, TimeUnit.SECONDS);
            require(details.getMissingObservationIds().isEmpty()
                            && details.getObservations().keySet().containsAll(output.checks.stream()
                            .map(HealthCheckResult::getId).collect(Collectors.toList())),
                    "Incident details must retrieve threshold evidence from the same observation store");
            status.set(200);
            clock.advance();
            runtime.getChecks().checkOnce(service);
            require(output.health.get(output.health.size() - 1).getStatus() == HealthStatus.HEALTHY,
                    "A passing HTTP check satisfies the recovery threshold of one");
            require(new JsonIncidentRepository(root.resolve("incidents")).findBySystemId(service.getSystemId())
                    .size() == 1, "Recovery must not duplicate the existing incident");
        } finally {
            server.stop(0);
        }
    }

    private static void closingLetsAnAcceptedCheckFinish(Path root) throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/health", exchange -> {
            try {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("The shutdown test did not release the HTTP response");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return;
                }
                exchange.sendResponseHeaders(503, -1);
            } finally {
                exchange.close();
            }
        });
        server.start();
        ExecutorService checks = Executors.newSingleThreadExecutor();
        Service service = saveService(root, server.getAddress().getPort(), 1);
        Output output = new Output();
        Sentinel app = new Sentinel(root, new TestClock());
        try {
            Sentinel.MonitoringApplication runtime = app.wireMonitoring(output, output);
            Future<?> running = checks.submit(() -> runtime.getChecks().checkOnce(service));
            require(entered.await(5, TimeUnit.SECONDS), "The check must enter HTTP before closing");
            app.close();
            release.countDown();
            running.get(5, TimeUnit.SECONDS);
            require(output.processed.size() == 1,
                    "Closing must allow an accepted check to deliver before the incident worker shuts down");
            runtime.getChecks().checkOnce(service);
            require(output.checks.size() == 1, "Closed application inputs must not start another HTTP check");
        } finally {
            release.countDown();
            app.close();
            checks.shutdownNow();
            server.stop(0);
        }
    }

    private static void failureHistorySurvivesApplicationRestart(Path root) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/health", exchange -> {
            try {
                exchange.sendResponseHeaders(503, -1);
            } finally {
                exchange.close();
            }
        });
        server.start();
        TestClock clock = new TestClock();
        Service service = saveService(root, server.getAddress().getPort(), 2);
        Output first = new Output();
        Output second = new Output();
        try {
            try (Sentinel app = new Sentinel(root, clock)) {
                app.wireMonitoring(first, first).getChecks().checkOnce(service);
                require(first.processed.isEmpty(), "A single failure must not cross a threshold of two");
            }
            clock.advance();
            UUID incidentId;
            try (Sentinel restarted = new Sentinel(root, clock)) {
                Sentinel.MonitoringApplication runtime = restarted.wireMonitoring(second, second);
                runtime.getChecks().checkOnce(service);
                List<Incident> incidents = new JsonIncidentRepository(root.resolve("incidents"))
                        .findBySystemId(service.getSystemId());
                require(incidents.size() == 1 && second.processed.size() == 1,
                        "A pre-restart failure and a fresh failure must cross the threshold together");
                Incident incident = incidents.get(0);
                incidentId = incident.getId();
                require(incident.getFailures().get(0).getEvidence().size() == 2,
                        "The resulting failure must retain evidence from both application instances");
                runtime.getInvestigation().getInvestigation().openIncident(incidentId);
                IncidentDetails details = second.details.get(5, TimeUnit.SECONDS);
                require(details.getMissingObservationIds().isEmpty()
                                && details.getObservations().containsKey(first.checks.get(0).getId()),
                        "Historical lookup must load evidence saved by the earlier application instance");
            }
            clock.advance();
            Output third = new Output();
            try (Sentinel restartedAgain = new Sentinel(root, clock)) {
                Sentinel.MonitoringApplication runtime = restartedAgain.wireMonitoring(third, third);
                runtime.getChecks().checkOnce(service);
                require(new JsonIncidentRepository(root.resolve("incidents"))
                                .findBySystemId(service.getSystemId()).size() == 1
                                && third.processed.equals(List.of(incidentId)),
                        "Replaying persistent history must acknowledge the same incident, not create a duplicate");
                runtime.getInvestigation().getInvestigation().openIncident(incidentId);
                require(third.details.get(5, TimeUnit.SECONDS).getMissingObservationIds().isEmpty(),
                        "Incident evidence remains available after another restart");
            }
        } finally {
            server.stop(0);
        }
    }

    private static Service saveService(Path root, int port, int threshold) {
        UUID systemId = UUID.randomUUID();
        Service service = new Service(UUID.randomUUID(), systemId, "HTTP API",
                MonitoringTarget.http(URI.create("http://127.0.0.1:" + port + "/health")),
                new MonitoringPolicy(Duration.ofSeconds(3), Duration.ofSeconds(10), threshold, 1));
        new JsonConfigurationRepository(root.resolve("systems")).save(
                new MonitoredSystem(systemId, "Wiring check", "", List.of(service), List.of()));
        return service;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static final class TestClock extends Clock {
        private Instant time = Instant.parse("2026-11-24T12:00:00Z");

        private void advance() { time = time.plusSeconds(1); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(time, zone); }
        @Override public Instant instant() { return time; }
    }

    private static final class Output implements MonitoringOutputBoundary, IncidentOutputBoundary {
        private final List<HealthCheckResult> checks = new CopyOnWriteArrayList<>();
        private final List<ServiceHealth> health = new CopyOnWriteArrayList<>();
        private final List<UUID> processed = new CopyOnWriteArrayList<>();
        private final CompletableFuture<IncidentDetails> details = new CompletableFuture<>();

        @Override public void presentCheckResult(HealthCheckResult result) { checks.add(result); }
        @Override public void presentHealth(ServiceHealth result) { health.add(result); }
        @Override public void presentProcessed(Incident incident) { processed.add(incident.getId()); }
        @Override public void presentDetails(IncidentDetails value) { details.complete(value); }
        @Override public void presentIncidents(List<IncidentSummary> incidents) { }
        @Override public void presentError(String message) { throw new AssertionError(message); }
    }
}
