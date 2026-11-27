package data_access.persistence;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import entity.IncidentRecoveryRecord;
import entity.RecoveryAttempt;
import use_case.recover_service.RecoveryRecordRepository;
import use_case.shared.IncidentDataException;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Stores recovery history alongside the incident files. Access is serialized by the incident worker. */
public final class JsonRecoveryRecordRepository implements RecoveryRecordRepository {
    private final Path directory;

    public JsonRecoveryRecordRepository(Path directory) {
        this.directory = Objects.requireNonNull(directory, "directory");
    }

    @Override
    public Optional<IncidentRecoveryRecord> findByIncidentId(UUID incidentId) {
        Path file = fileFor(incidentId);
        try (Reader reader = Files.newBufferedReader(file)) {
            return Optional.of(decode(JsonCodec.readObject(reader), incidentId));
        } catch (NoSuchFileException exception) {
            try {
                if (Files.notExists(file, LinkOption.NOFOLLOW_LINKS)) {
                    return Optional.empty();
                }
            } catch (SecurityException denied) {
                exception.addSuppressed(denied);
            }
            throw new IncidentDataException("Could not read recovery record " + file, exception);
        } catch (IOException | RuntimeException exception) {
            throw new IncidentDataException("Could not read recovery record " + file, exception);
        }
    }

    @Override
    public void save(IncidentRecoveryRecord record) {
        Path file = fileFor(record.getIncidentId());
        try {
            String document = JsonCodec.toJson(encode(record));
            Files.createDirectories(directory);
            Path temporary = Files.createTempFile(directory, "recovery-", ".tmp");
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
            throw new IncidentDataException("Could not save recovery record " + file, exception);
        }
    }

    private Path fileFor(UUID incidentId) {
        return directory.resolve(Objects.requireNonNull(incidentId, "incidentId") + ".json");
    }

    private static JsonObject encode(IncidentRecoveryRecord record) {
        JsonObject json = new JsonObject();
        json.addProperty("incidentId", record.getIncidentId().toString());
        json.add("attempts", JsonCodec.encodeArray(record.getAttempts(), JsonRecoveryRecordRepository::encodeAttempt));
        record.getVerificationStartedAt().ifPresent(start -> json.addProperty("verificationStartedAt", start.toString()));
        return json;
    }

    private static IncidentRecoveryRecord decode(JsonObject json, UUID incidentId) {
        if (!JsonCodec.readId(json, "incidentId").equals(incidentId)) {
            throw new IllegalArgumentException("Incident ID does not match the recovery record filename");
        }
        IncidentRecoveryRecord record = IncidentRecoveryRecord.empty(incidentId);
        for (RecoveryAttempt attempt : JsonCodec.readList(json, "attempts", JsonRecoveryRecordRepository::readAttempt)) {
            record = record.recordAttempt(attempt);
        }
        if (json.has("verificationStartedAt")) {
            return record.beginManualVerification(JsonCodec.readInstant(json, "verificationStartedAt"));
        }
        if (record.getVerificationStartedAt().isPresent()) {
            throw new IllegalArgumentException("Successful recovery requires a verification start");
        }
        return record;
    }

    private static JsonObject encodeAttempt(RecoveryAttempt attempt) {
        JsonObject json = new JsonObject();
        json.addProperty("id", attempt.getId().toString());
        json.addProperty("actionId", attempt.getActionId().toString());
        json.addProperty("startedAt", attempt.getStartedAt().toString());
        json.addProperty("completedAt", attempt.getCompletedAt().toString());
        json.addProperty("outcome", attempt.getOutcome().name());
        json.addProperty("message", attempt.getMessage());
        return json;
    }

    private static RecoveryAttempt readAttempt(JsonElement element) {
        JsonObject json = element.getAsJsonObject();
        return new RecoveryAttempt(JsonCodec.readId(json, "id"), JsonCodec.readId(json, "actionId"),
                JsonCodec.readInstant(json, "startedAt"), JsonCodec.readInstant(json, "completedAt"),
                RecoveryAttempt.Outcome.valueOf(JsonCodec.readString(json, "outcome")), JsonCodec.readString(json, "message"));
    }
}
