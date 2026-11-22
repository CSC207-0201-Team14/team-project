package use_case.recover_service;

import java.util.Objects;
import java.util.Optional;

/** Either an operation to run, or an explanation of why automatic recovery is unsuitable. */
public final class RecoveryDecision {
    private final RecoveryOperation operation;
    private final String explanation;

    private RecoveryDecision(RecoveryOperation operation, String explanation) {
        this.operation = operation;
        this.explanation = Objects.requireNonNull(explanation, "explanation");
        if (explanation.isBlank()) {
            throw new IllegalArgumentException("A recovery decision requires an explanation");
        }
    }

    public static RecoveryDecision perform(RecoveryOperation operation, String explanation) {
        return new RecoveryDecision(Objects.requireNonNull(operation, "operation"), explanation);
    }

    public static RecoveryDecision unsuitable(String reason) {
        return new RecoveryDecision(null, reason);
    }

    /** Empty means no operation may run and the manual next steps apply. */
    public Optional<RecoveryOperation> getOperation() {
        return Optional.ofNullable(operation);
    }

    public boolean isSupported() {
        return operation != null;
    }

    public String getExplanation() {
        return explanation;
    }
}
