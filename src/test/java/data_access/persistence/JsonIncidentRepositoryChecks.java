package data_access.persistence;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import data_access.configuration.JsonConfigurationRepository;
import entity.Dependency;
import entity.DiagnosticFinding;
import entity.FailureEvent;
import entity.HealthCheckResult;
import entity.Incident;
import entity.MonitoredSystem;
import entity.MonitoringTarget;
import entity.Service;
import use_case.monitor_service_health.HealthStateEvaluator;
import use_case.recover_service.RecoveryVerifier;
import use_case.recover_service.VerificationResult;
import use_case.shared.IncidentDataException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static support.Checks.check;
import static support.Checks.expect;
import static support.TestData.healthy;
import static support.TestData.observation;
import static support.TestData.service;

/** Run directly with Java, using isolated files under the project's build directory. */
public final class JsonIncidentRepositoryChecks {
    private static final Instant OPENED = Instant.parse("2026-01-01T00:00:00.123456789Z");
    private static final UUID SERVICE = UUID.randomUUID();
    private static final UUID SYSTEM = UUID.randomUUID();

    public static void main(String[] args) throws IOException {
        Path root = Files.createTempDirectory(Files.createDirectories(Path.of("target")), "incident-checks-");
        try {
            fullIncidentSurvivesReload(root.resolve("round-trip"));
            filtersAndReplacesIncidents(root.resolve("filter"));
            reportsCorruptFiles(root.resolve("corrupt"));
            if (root.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                reportsDanglingRecord(root.resolve("dangling"));
            }
            failedSavePreservesPreviousVersion(root.resolve("failed-save"));
            sharesConfigurationDependencyFormat(root.resolve("systems"));
            preservesOptionalResponseTime();
        } finally {
            List<Path> created;
            try (Stream<Path> paths = Files.walk(root)) {
                created = paths.sorted(Comparator.reverseOrder()).collect(Collectors.toList());
            }
            for (Path path : created) {
                Files.delete(path);
            }
        }
        System.out.println("UC3 JSON incident repository checks passed");
    }

    private static void fullIncidentSurvivesReload(Path directory) {
        UUID dependent = UUID.randomUUID();
        Service db = service(SERVICE, SYSTEM, "Database", MonitoringTarget.docker("db"));
        Service api = service(dependent, SYSTEM, "API", MonitoringTarget.docker("api"));
        FailureEvent first = thresholdFailure(db, OPENED, "HTTP 503\n\"unavailable\"");
        FailureEvent second = thresholdFailure(api, OPENED.plusSeconds(1), "Connection refused");
        DiagnosticFinding previous = new DiagnosticFinding(UUID.randomUUID(), "observed-failure", SERVICE, OPENED,
                Optional.empty(), List.of(first.getEvidence().get(0).getId()), List.of(), "Failed check", List.of());
        Dependency dependency = new Dependency(UUID.randomUUID(), dependent, SERVICE);
        DiagnosticFinding current = new DiagnosticFinding(UUID.randomUUID(), "dependency-failure", dependent,
                OPENED.plusSeconds(1), Optional.of(SERVICE),
                List.of(first.getEvidence().get(0).getId(), second.getEvidence().get(0).getId()),
                List.of(dependency), "Dependency may explain the failure", List.of("Read logs", "Recheck \u6570\u636e\u5e93"));
        Instant finished = OPENED.plusSeconds(4);
        Incident assessed = Incident.open(UUID.randomUUID(), SYSTEM, OPENED, first).recordFailure(second)
                .startInvestigation(OPENED.plusSeconds(1))
                .updateAssessment(Set.of(), List.of(previous)).updateAssessment(Set.of(dependent), List.of(current));
        Instant verificationStart = OPENED.plusSeconds(2);
        VerificationResult verification = new RecoveryVerifier().evaluate(assessed, verificationStart,
                Map.of(SERVICE, db, dependent, api),
                Map.of(SERVICE, passingChecks(SERVICE, finished), dependent, passingChecks(dependent, finished)), finished);
        check(verification.isSatisfied(), "UC4 must verify both monitored services before resolution");
        Incident original = assessed.resolve(finished, verificationStart, verification.getHealthByService());

        new JsonIncidentRepository(directory).save(original);
        JsonIncidentRepository reader = new JsonIncidentRepository(directory);
        Incident loaded = reader.findById(original.getId()).orElseThrow();
        List<Incident> history = reader.findBySystemId(SYSTEM);
        check(history.size() == 1 && history.get(0).getId().equals(original.getId())
                && reader.findUnresolvedBySystemId(SYSTEM).isEmpty(), "Resolved incident belongs in history only");
        check(original.getId().equals(loaded.getId()) && SYSTEM.equals(loaded.getSystemId())
                && original.getOpenedAt().equals(loaded.getOpenedAt())
                && original.getInvestigatingAt().equals(loaded.getInvestigatingAt())
                && original.getResolvedAt().equals(loaded.getResolvedAt())
                && loaded.getStatus() == Incident.Status.RESOLVED, "Incident identity or lifecycle changed");
        check(loaded.getPotentiallyAffectedServiceIds().equals(Set.of(dependent)), "Affected services changed");
        check(loaded.getFailures().size() == 2, "Missing recorded failures");
        for (int i = 0; i < original.getFailures().size(); i++) {
            FailureEvent expected = original.getFailures().get(i);
            FailureEvent actual = loaded.getFailures().get(i);
            check(expected.getId().equals(actual.getId()) && expected.getServiceId().equals(actual.getServiceId())
                    && expected.getTimestamp().equals(actual.getTimestamp()), "Failure identity or timestamp changed");
            checkObservations(expected.getEvidence(), actual.getEvidence());
        }
        check(loaded.getFindingHistory().size() == 2, "Missing historical assessment");
        checkFinding(previous, loaded.getFindingHistory().get(0));
        checkFinding(current, loaded.getFindingHistory().get(1));
        check(loaded.getActiveFindings().equals(List.of(loaded.getFindingHistory().get(1))),
                "Only the latest assessment should be active");
        checkObservations(original.getResolutionEvidence(), loaded.getResolutionEvidence());
    }

    private static FailureEvent thresholdFailure(Service service, Instant at, String message) {
        HealthCheckResult failed = new HealthCheckResult(UUID.randomUUID(), service.getId(), at,
                HealthCheckResult.Outcome.FAIL, OptionalLong.of(4_000_000_000L), message);
        return new HealthStateEvaluator().evaluate(service, List.of(
                observation(service.getId(), at.minusSeconds(1), HealthCheckResult.Outcome.FAIL), failed))
                .getFailureEvent().orElseThrow();
    }

    private static List<HealthCheckResult> passingChecks(UUID serviceId, Instant finished) {
        return List.of(observation(serviceId, finished.minusSeconds(1), HealthCheckResult.Outcome.PASS),
                observation(serviceId, finished, HealthCheckResult.Outcome.PASS));
    }

    private static void filtersAndReplacesIncidents(Path directory) {
        JsonIncidentRepository repository = new JsonIncidentRepository(directory);
        UUID missing = UUID.randomUUID();
        check(repository.findById(missing).isEmpty() && repository.findBySystemId(SYSTEM).isEmpty()
                && repository.findUnresolvedBySystemId(SYSTEM).isEmpty(),
                "A new repository should be empty");
        Incident open = openIncident(SYSTEM);
        Incident investigating = openIncident(SYSTEM).startInvestigation(OPENED);
        Incident closed = openIncident(SYSTEM);
        repository.save(open);
        repository.save(investigating);
        repository.save(openIncident(UUID.randomUUID()));
        repository.save(closed);
        Instant finished = OPENED.plusSeconds(1);
        repository.save(closed.resolve(finished, OPENED, Map.of(SERVICE, healthy(SERVICE, finished))));

        List<Incident> history = repository.findBySystemId(SYSTEM);
        check(history.size() == 3 && history.stream().map(Incident::getId).collect(Collectors.toSet())
                .equals(Set.of(open.getId(), investigating.getId(), closed.getId())),
                "System history must include its open, investigating and resolved incidents");
        List<Incident> unresolved = repository.findUnresolvedBySystemId(SYSTEM);
        check(unresolved.size() == 2 && unresolved.stream().map(Incident::getId).collect(Collectors.toSet())
                .equals(Set.of(open.getId(), investigating.getId())), "Query must return only matching unresolved IDs");
        check(repository.findById(investigating.getId()).orElseThrow().getStatus() == Incident.Status.INVESTIGATING,
                "Investigation status changed");
        check(repository.findById(closed.getId()).orElseThrow().getStatus() == Incident.Status.RESOLVED,
                "A later save must replace the previous state");
        check(repository.findById(missing).isEmpty(), "An absent record must remain absent");
    }

    private static void reportsCorruptFiles(Path directory) throws IOException {
        JsonIncidentRepository repository = new JsonIncidentRepository(directory);
        repository.save(openIncident(SYSTEM));
        Incident foreign = openIncident(UUID.randomUUID());
        repository.save(foreign);
        Path path = directory.resolve(foreign.getId() + ".json");
        JsonObject document = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
        check(document.get("schemaVersion").getAsInt() == 1, "Saved incident must declare its schema version");
        document.addProperty("schemaVersion", 2);
        for (String invalid : List.of(document.toString(), "{\"schemaVersion\":1,")) {
            Files.writeString(path, invalid);
            expectDataFailure(() -> repository.findById(foreign.getId()));
            expectDataFailure(() -> repository.findBySystemId(SYSTEM));
            expectDataFailure(() -> repository.findUnresolvedBySystemId(SYSTEM));
        }
        repository.save(foreign);
        UUID unreadableId = UUID.randomUUID();
        Files.createDirectory(directory.resolve(unreadableId + ".json"));
        expectDataFailure(() -> repository.findById(unreadableId));
        expectDataFailure(() -> repository.findBySystemId(SYSTEM));
        expectDataFailure(() -> repository.findUnresolvedBySystemId(SYSTEM));
    }

    private static void reportsDanglingRecord(Path directory) throws IOException {
        JsonIncidentRepository repository = new JsonIncidentRepository(directory);
        repository.save(openIncident(SYSTEM));
        UUID id = UUID.randomUUID();
        Files.createSymbolicLink(directory.resolve(id + ".json"), Path.of("missing-target"));
        expectDataFailure(() -> repository.findById(id));
        expectDataFailure(() -> repository.findBySystemId(SYSTEM));
    }

    private static void failedSavePreservesPreviousVersion(Path directory) throws IOException {
        JsonIncidentRepository repository = new JsonIncidentRepository(directory);
        Incident original = openIncident(SYSTEM);
        repository.save(original);
        Path path = directory.resolve(original.getId() + ".json");
        byte[] before = Files.readAllBytes(path);
        // An unpaired surrogate in a check message cannot be written as UTF-8.
        String invalidMessage = String.valueOf((char) 0xD800);
        Incident updated = original.recordFailure(failure(SERVICE, OPENED.plusSeconds(1), invalidMessage));
        expectDataFailure(() -> repository.save(updated));
        check(Arrays.equals(before, Files.readAllBytes(path)), "A failed save changed the stored bytes");
        check(repository.findById(original.getId()).orElseThrow().getFailures().size() == 1,
                "The previous version must remain readable");
        try (Stream<Path> files = Files.list(directory)) {
            check(files.count() == 1, "A failed save should clean up its temporary file");
        }
    }

    private static Incident openIncident(UUID systemId) {
        return Incident.open(UUID.randomUUID(), systemId, OPENED, failure(SERVICE, OPENED, "Unavailable"));
    }

    private static FailureEvent failure(UUID serviceId, Instant at, String message) {
        HealthCheckResult failed = new HealthCheckResult(UUID.randomUUID(), serviceId, at,
                HealthCheckResult.Outcome.FAIL, OptionalLong.of(4_000_000_000L), message);
        return new FailureEvent(UUID.randomUUID(), serviceId, at, List.of(failed));
    }

    private static void checkObservations(List<HealthCheckResult> original, List<HealthCheckResult> restored) {
        check(original.size() == restored.size(), "Observation count changed");
        for (int i = 0; i < original.size(); i++) {
            HealthCheckResult expected = original.get(i);
            HealthCheckResult actual = restored.get(i);
            check(expected.getId().equals(actual.getId()) && expected.getServiceId().equals(actual.getServiceId())
                    && expected.getTimestamp().equals(actual.getTimestamp()) && expected.getOutcome() == actual.getOutcome()
                    && expected.getResponseTimeMs().equals(actual.getResponseTimeMs())
                    && expected.getMessage().equals(actual.getMessage()), "Observation changed: " + expected.getId());
        }
    }

    private static void checkFinding(DiagnosticFinding expected, DiagnosticFinding actual) {
        check(expected.getId().equals(actual.getId()) && expected.getRuleId().equals(actual.getRuleId())
                && expected.getAffectedServiceId().equals(actual.getAffectedServiceId())
                && expected.getCreatedAt().equals(actual.getCreatedAt())
                && expected.getSuspectedCauseServiceId().equals(actual.getSuspectedCauseServiceId())
                && expected.getExplanation().equals(actual.getExplanation())
                && expected.getNextSteps().equals(actual.getNextSteps())
                && expected.getObservationIds().equals(actual.getObservationIds()), "Finding changed: " + expected.getId());
        check(expected.getDependencyEvidence().size() == actual.getDependencyEvidence().size(),
                "Dependency count changed");
        for (int i = 0; i < expected.getDependencyEvidence().size(); i++) {
            Dependency original = expected.getDependencyEvidence().get(i);
            Dependency restored = actual.getDependencyEvidence().get(i);
            check(original.getId().equals(restored.getId())
                    && original.getDependentServiceId().equals(restored.getDependentServiceId())
                    && original.getRequiredServiceId().equals(restored.getRequiredServiceId()), "Dependency evidence changed");
        }
    }

    private static void sharesConfigurationDependencyFormat(Path directory) throws IOException {
        Service api = service(UUID.randomUUID(), SYSTEM, "API", MonitoringTarget.docker("api"));
        Service database = service(SERVICE, SYSTEM, "Database", MonitoringTarget.docker("db"));
        Dependency edge = new Dependency(UUID.randomUUID(), api.getId(), database.getId());
        new JsonConfigurationRepository(directory).save(
                new MonitoredSystem(SYSTEM, "Orders", "", List.of(api, database), List.of(edge)));
        JsonObject config = JsonParser.parseString(Files.readString(directory.resolve(SYSTEM + ".json"))).getAsJsonObject();
        JsonObject stored = config.getAsJsonArray("dependencies").get(0).getAsJsonObject();
        check(JsonCodec.encodeDependency(edge).equals(stored), "Dependency format differs from UC1");

        Dependency loaded = JsonCodec.readDependency(stored);
        check(loaded.getId().equals(edge.getId()) && loaded.getDependentServiceId().equals(edge.getDependentServiceId())
                && loaded.getRequiredServiceId().equals(edge.getRequiredServiceId()), "Dependency direction changed");
    }

    private static void preservesOptionalResponseTime() {
        long duration = 4_000_000_000L;
        JsonObject timed = JsonCodec.encodeHealthCheckResult(timedObservation(OptionalLong.of(duration)));
        check(timed.getAsJsonPrimitive("responseTimeMs").isNumber()
                && JsonCodec.readHealthCheckResult(timed).getResponseTimeMs().orElseThrow() == duration,
                "Response time must retain its full integer precision");

        JsonObject untimed = JsonCodec.encodeHealthCheckResult(timedObservation(OptionalLong.empty()));
        check(!untimed.has("responseTimeMs") && JsonCodec.readHealthCheckResult(untimed).getResponseTimeMs().isEmpty(),
                "An absent response time must survive reloading");

        timed.addProperty("responseTimeMs", 1.5);
        expect(ArithmeticException.class, () -> JsonCodec.readHealthCheckResult(timed), "fractional response time");
    }

    private static HealthCheckResult timedObservation(OptionalLong responseTimeMs) {
        return new HealthCheckResult(UUID.randomUUID(), UUID.randomUUID(), Instant.EPOCH,
                HealthCheckResult.Outcome.PASS, responseTimeMs, "OK");
    }

    private static void expectDataFailure(Runnable action) {
        expect(IncidentDataException.class, action, "storage problem was not reported");
    }
}
