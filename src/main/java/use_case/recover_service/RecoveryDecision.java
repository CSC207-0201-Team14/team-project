package use_case.recover_service;

import java.util.Objects;
import java.util.Optional;

/**
 * Either an operation to run, or an explanation of why no operation runs: automatic recovery is unsuitable
 * for the container's state, or it is not needed because the target service has already recovered.
 */
public final class RecoveryDecision {
    public enum Kind {
        /** Run the operation. */
        PERFORM,
        /** The container's state makes automatic recovery unsafe or pointless; the manual next steps apply. */
        UNSUITABLE,
        /** The target already meets its recovery threshold, so operating on it could only disrupt it. */
        NOT_NEEDED
    }

    private final Kind kind;
    private final RecoveryOperation operation;
    private final String explanation;

    private RecoveryDecision(Kind kind, RecoveryOperation operation, String explanation) {
        this.kind = kind;
        this.operation = operation;
        this.explanation = Objects.requireNonNull(explanation, "explanation");
        if (explanation.isBlank()) {
            throw new IllegalArgumentException("A recovery decision requires an explanation");
        }
    }

    public static RecoveryDecision perform(RecoveryOperation operation, String explanation) {
        return new RecoveryDecision(Kind.PERFORM, Objects.requireNonNull(operation, "operation"), explanation);
    }

    public static RecoveryDecision unsuitable(String reason) {
        return new RecoveryDecision(Kind.UNSUITABLE, null, reason);
    }

    public static RecoveryDecision notNeeded(String reason) {
        return new RecoveryDecision(Kind.NOT_NEEDED, null, reason);
    }

    public Kind getKind() {
        return kind;
    }

    /** Empty means no operation may run: see {@link #getKind()} and {@link #getExplanation()} for why. */
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
