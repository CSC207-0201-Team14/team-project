package data_access.persistence;

import entity.IncidentRecoveryRecord;
import entity.RecoveryAttempt;
import use_case.shared.IncidentDataException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class JsonRecoveryRecordRepositoryChecks {
    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("sentinel-recovery-check");
        UUID incident = UUID.randomUUID();
        UUID action = UUID.randomUUID();
        Instant start = Instant.parse("2026-11-26T12:00:00.123456789Z");
        JsonRecoveryRecordRepository repository = new JsonRecoveryRecordRepository(directory);
        check(repository.findByIncidentId(incident).isEmpty(), "An absent recovery record is empty");

        IncidentRecoveryRecord record = IncidentRecoveryRecord.empty(incident);
        for (RecoveryAttempt.Outcome outcome : RecoveryAttempt.Outcome.values()) {
            Instant at = start.plusSeconds(outcome.ordinal() * 10);
            record = record.recordAttempt(new RecoveryAttempt(UUID.randomUUID(), action, at, at.plusSeconds(1),
                    outcome, "Result " + outcome));
        }
        Instant manualStart = start.plusSeconds(90);
        repository.save(record.beginManualVerification(manualStart));
        Path file = directory.resolve(incident + ".json");
        String saved = Files.readString(file);
        JsonRecoveryRecordRepository reopened = new JsonRecoveryRecordRepository(directory);
        IncidentRecoveryRecord loaded = reopened.findByIncidentId(incident).orElseThrow();
        check(loaded.getVerificationStartedAt().orElseThrow().equals(manualStart),
                "Manual verification retains the exact timestamp after reopening");
        List<RecoveryAttempt> attempts = loaded.getAttempts();
        check(attempts.size() == record.getAttempts().size(), "Every attempt is retained in the recorded order");
        for (int index = 0; index < attempts.size(); index++) {
            RecoveryAttempt expected = record.getAttempts().get(index);
            RecoveryAttempt actual = attempts.get(index);
            check(actual.getId().equals(expected.getId()) && actual.getActionId().equals(action)
                            && actual.getStartedAt().equals(expected.getStartedAt())
                            && actual.getCompletedAt().equals(expected.getCompletedAt())
                            && actual.getOutcome() == expected.getOutcome()
                            && actual.getMessage().equals(expected.getMessage()), "All attempt fields survive reload");
        }
        for (String corrupt : List.of("{", saved.replace(incident.toString(), UUID.randomUUID().toString()),
                saved.replace(manualStart.toString(), start.minusSeconds(1).toString()))) {
            Files.writeString(file, corrupt);
            expectDataError(() -> reopened.findByIncidentId(incident));
            check(Files.readString(file).equals(corrupt), "A failed read leaves its source untouched");
        }

        Path occupied = directory.resolve(UUID.randomUUID() + ".json");
        Files.createDirectory(occupied);
        Files.writeString(occupied.resolve("existing"), "keep");
        UUID blocked = UUID.fromString(occupied.getFileName().toString().replace(".json", ""));
        expectDataError(() -> repository.save(IncidentRecoveryRecord.empty(blocked)));
        check(Files.readString(occupied.resolve("existing")).equals("keep"), "Failed replacement keeps existing data");
        try (var files = Files.list(directory)) {
            check(files.noneMatch(path -> path.toString().endsWith(".tmp")), "Failed replacement cleans up temp files");
        }
        System.out.println("UC4 JSON recovery record checks passed");
    }

    private static void expectDataError(Runnable work) {
        try {
            work.run();
        } catch (IncidentDataException expected) {
            return;
        }
        throw new AssertionError("Expected an incident data error");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
