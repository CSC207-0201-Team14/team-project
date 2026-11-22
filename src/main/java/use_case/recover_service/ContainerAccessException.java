package use_case.recover_service;

import java.util.Objects;

/** Expected container gateway failures, with a message suitable for presentation. */
public final class ContainerAccessException extends RuntimeException {
    public enum Kind {
        /** The container engine could not be reached or answered unexpectedly. */
        UNAVAILABLE,
        /** The engine refused the request for lack of permission. */
        PERMISSION_DENIED,
        /** The engine was reached but the operation did not succeed. */
        FAILED
    }

    private final Kind kind;

    public ContainerAccessException(Kind kind, String message) {
        super(message);
        this.kind = Objects.requireNonNull(kind, "kind");
    }

    public ContainerAccessException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = Objects.requireNonNull(kind, "kind");
    }

    public Kind getKind() {
        return kind;
    }
}
