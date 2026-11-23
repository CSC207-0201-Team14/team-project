package entity;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Recovery history for one incident: every executed attempt and the start of the current
 * verification window. Kept separate from Incident so diagnosis and recovery evolve independently.
 * Only checks at or after the verification start may count towards resolving the incident.
 */
public final class IncidentRecoveryRecord {
    private final UUID incidentId;
    private final List<RecoveryAttempt> attempts;
    private final Instant verificationStartedAt;

    private IncidentRecoveryRecord(UUID incidentId, List<RecoveryAttempt> attempts, Instant verificationStartedAt) {
        this.incidentId = incidentId;
        this.attempts = List.copyOf(attempts);
        this.verificationStartedAt = verificationStartedAt;
    }

    public static IncidentRecoveryRecord empty(UUID incidentId) {
        return new IncidentRecoveryRecord(Objects.requireNonNull(incidentId, "incidentId"), List.of(), null);
    }

    /**
     * Appends an attempt in chronological order. A successful attempt starts verification at its
     * completion time; failed or denied attempts leave the verification window unchanged.
     */
    public IncidentRecoveryRecord recordAttempt(RecoveryAttempt attempt) {
        Objects.requireNonNull(attempt, "attempt");
        for (RecoveryAttempt existing : attempts) {
            if (existing.getId().equals(attempt.getId())) {
                throw new IllegalArgumentException("Each recovery attempt must have a fresh ID");
            }
        }
        if (!attempts.isEmpty() && attempt.getStartedAt().isBefore(attempts.get(attempts.size() - 1).getStartedAt())) {
            throw new IllegalArgumentException("Recovery attempts must be recorded in chronological order");
        }
        List<RecoveryAttempt> updated = new ArrayList<>(attempts);
        updated.add(attempt);
        Instant window = verificationStartedAt;
        if (attempt.getOutcome() == RecoveryAttempt.Outcome.SUCCEEDED) {
            window = advanceWindow(attempt.getCompletedAt());
        }
        return new IncidentRecoveryRecord(incidentId, updated, window);
    }

    /** Starts verification after the developer has performed the manual next steps outside Sentinel. */
    public IncidentRecoveryRecord beginManualVerification(Instant startedAt) {
        Objects.requireNonNull(startedAt, "startedAt");
        return new IncidentRecoveryRecord(incidentId, attempts, advanceWindow(startedAt));
    }

    private Instant advanceWindow(Instant startedAt) {
        if (verificationStartedAt != null && startedAt.isBefore(verificationStartedAt)) {
            throw new IllegalArgumentException("The verification window cannot move backwards");
        }
        return startedAt;
    }

    public UUID getIncidentId() {
        return incidentId;
    }

    public List<RecoveryAttempt> getAttempts() {
        return attempts;
    }

    public Optional<Instant> getVerificationStartedAt() {
        return Optional.ofNullable(verificationStartedAt);
    }
}
