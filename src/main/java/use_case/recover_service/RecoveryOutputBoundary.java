package use_case.recover_service;

import entity.IncidentRecoveryRecord;

/** Presenters marshal Swing updates onto the EDT. */
public interface RecoveryOutputBoundary {
    void presentPreview(RecoveryPreview preview);

    void presentAttempt(RecoveryAttemptResult result);

    void presentManualSteps(ManualRecoveryGuidance guidance);

    /** Verification is incomplete or could not be performed; the incident remains unresolved. */
    void presentVerification(VerificationResult result);

    void presentResolved(ResolutionResult result);

    void presentHistory(IncidentRecoveryRecord record);

    void presentError(String message);
}
