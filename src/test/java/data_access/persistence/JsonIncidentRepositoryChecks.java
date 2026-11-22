package data_access.persistence;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import entity.Dependency;
import entity.DiagnosticFinding;
import entity.FailureEvent;
import entity.HealthCheckResult;
import entity.HealthStatus;
import entity.Incident;
import entity.ServiceHealth;
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
            failedSavePreservesPreviousVersion(root.resolve("failed-save"));
        } finally {
            List<Path> created;
            try (Stream<Path> paths = Files.walk(root)) {
                created = paths.sorted(Comparator.reverseOrder()).collect(Collectors.toList());
            }
            for (Path path : created) {
                Files.delete(path);
            }
        }
    }

    private static void fullIncidentSurvivesReload(Path directory) {
        UUID dependent = UUID.randomUUID();
        FailureEvent first = failure(SERVICE, OPENED, "HTTP 503\n\"unavailable\"");
        FailureEvent second = failure(dependent, OPENED.plusSeconds(1), "Connection refused");
        DiagnosticFinding previous = new DiagnosticFinding(UUID.randomUUID(), "observed-failure", SERVICE, OPENED,
                Optional.empty(), List.of(first.getEvidence().get(0).getId()), List.of(), "Failed check", List.of());
        Dependency dependency = new Dependency(UUID.randomUUID(), dependent, SERVICE);
        DiagnosticFinding current = new DiagnosticFinding(UUID.randomUUID(), "dependency-failure", dependent,
                OPENED.plusSeconds(1), Optional.of(SERVICE),
                List.of(first.getEvidence().get(0).getId(), second.getEvidence().get(0).getId()),
                List.of(dependency), "Dependency may explain the failure", List.of("Read logs", "Recheck \u6570\u636e\u5e93"));
        Instant finished = OPENED.plusSeconds(4);
        Incident original = Incident.open(UUID.randomUUID(), SYSTEM, OPENED, first).recordFailure(second)
                .startInvestigation(OPENED.plusSeconds(1))
                .updateAssessment(Set.of(), List.of(previous)).updateAssessment(Set.of(dependent), List.of(current))
                .resolve(finished, OPENED.plusSeconds(2), Map.of(SERVICE, healthy(SERVICE, finished),
                        dependent, healthy(dependent, finished)));

        new JsonIncidentRepository(directory).save(original);
        Incident loaded = new JsonIncidentRepository(directory).findById(original.getId()).orElseThrow();
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

    private static ServiceHealth healthy(UUID serviceId, Instant at) {
        return ServiceHealth.evaluated(HealthStatus.HEALTHY, new HealthCheckResult(UUID.randomUUID(), serviceId,
                at, HealthCheckResult.Outcome.PASS, OptionalLong.empty(), "Recovered"));
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

    private static void expectDataFailure(Runnable action) {
        try {
            action.run();
        } catch (IncidentDataException expected) {
            return;
        }
        throw new AssertionError("Expected IncidentDataException");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
