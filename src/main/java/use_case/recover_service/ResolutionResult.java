package use_case.recover_service;

import entity.Incident;
import entity.RecoveryAttempt;
import java.util.List;
import java.util.Objects;

/** Steps 6–7 output: the resolved incident, with the attempts and verification that justified resolving it. */
public final class ResolutionResult {
    private final Incident incident;
    private final List<RecoveryAttempt> attempts;
    private final VerificationResult verification;

    public ResolutionResult(Incident incident, List<RecoveryAttempt> attempts, VerificationResult verification) {
        this.incident = Objects.requireNonNull(incident, "incident");
        this.attempts = List.copyOf(attempts);
        this.verification = Objects.requireNonNull(verification, "verification");
        if (incident.getStatus() != Incident.Status.RESOLVED || !verification.isSatisfied()) {
            throw new IllegalArgumentException("A resolution requires a resolved incident and satisfied verification");
        }
    }

    public Incident getIncident() { return incident; }
    public List<RecoveryAttempt> getAttempts() { return attempts; }
    public VerificationResult getVerification() { return verification; }
}
