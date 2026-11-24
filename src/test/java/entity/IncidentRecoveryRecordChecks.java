package entity;

import java.time.Instant;
import java.util.UUID;

/** Run explicitly after mvn test-compile, following the existing test convention. */
public final class IncidentRecoveryRecordChecks {
    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
    private static final UUID ACTION = UUID.randomUUID();

    public static void main(String[] args) {
        startsWithoutVerification();
        successStartsVerificationButFailureDoesNot();
        rejectsDuplicateAndOutOfOrderAttempts();
        verificationWindowNeverMovesBackwards();
        System.out.println("UC4 recovery record checks passed");
    }

    private static void startsWithoutVerification() {
        UUID incident = UUID.randomUUID();
        IncidentRecoveryRecord record = IncidentRecoveryRecord.empty(incident);
        check(record.getIncidentId().equals(incident) && record.getAttempts().isEmpty()
                && record.getVerificationStartedAt().isEmpty(), "A new record has no attempts and no window");
    }

    private static void successStartsVerificationButFailureDoesNot() {
        IncidentRecoveryRecord record = IncidentRecoveryRecord.empty(UUID.randomUUID());
        IncidentRecoveryRecord failed = record.recordAttempt(attempt(0, RecoveryAttempt.Outcome.FAILED));
        check(failed.getAttempts().size() == 1 && failed.getVerificationStartedAt().isEmpty(),
                "A failed attempt is retained but does not start verification");
        IncidentRecoveryRecord denied = failed.recordAttempt(attempt(10, RecoveryAttempt.Outcome.PERMISSION_DENIED));
        check(denied.getVerificationStartedAt().isEmpty(), "A denied attempt does not start verification");
        RecoveryAttempt success = attempt(20, RecoveryAttempt.Outcome.SUCCEEDED);
        IncidentRecoveryRecord succeeded = denied.recordAttempt(success);
        check(succeeded.getAttempts().size() == 3
                && succeeded.getVerificationStartedAt().orElseThrow().equals(success.getCompletedAt()),
                "A successful attempt starts verification when it completes");
        IncidentRecoveryRecord laterFailure = succeeded.recordAttempt(attempt(30, RecoveryAttempt.Outcome.FAILED));
        check(laterFailure.getVerificationStartedAt().equals(succeeded.getVerificationStartedAt()),
                "A later failure keeps the existing window");
        check(record.getAttempts().isEmpty(), "Records are immutable");
    }

    private static void rejectsDuplicateAndOutOfOrderAttempts() {
        RecoveryAttempt first = attempt(10, RecoveryAttempt.Outcome.FAILED);
        IncidentRecoveryRecord record = IncidentRecoveryRecord.empty(UUID.randomUUID()).recordAttempt(first);
        rejects(() -> record.recordAttempt(first));
        rejects(() -> record.recordAttempt(attempt(0, RecoveryAttempt.Outcome.FAILED)));
    }

    private static void verificationWindowNeverMovesBackwards() {
        IncidentRecoveryRecord manual = IncidentRecoveryRecord.empty(UUID.randomUUID())
                .beginManualVerification(START.plusSeconds(60));
        check(manual.getVerificationStartedAt().orElseThrow().equals(START.plusSeconds(60)),
                "Manual verification starts the window");
        rejects(() -> manual.beginManualVerification(START));
        rejects(() -> manual.recordAttempt(attempt(0, RecoveryAttempt.Outcome.SUCCEEDED)));
        check(manual.beginManualVerification(START.plusSeconds(90)).getVerificationStartedAt().orElseThrow()
                .equals(START.plusSeconds(90)), "Restarting verification moves the window forwards");
    }

    private static RecoveryAttempt attempt(long startSeconds, RecoveryAttempt.Outcome outcome) {
        return new RecoveryAttempt(UUID.randomUUID(), ACTION, START.plusSeconds(startSeconds),
                START.plusSeconds(startSeconds + 5), outcome, outcome.name());
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void rejects(Runnable action) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("Expected IllegalArgumentException");
    }
}
