package use_case.shared;

/** Expected adapter access failures, with a message suitable for presentation. */
public final class IncidentDataException extends RuntimeException {
    public IncidentDataException(String message) { super(message); }

    public IncidentDataException(String message, Throwable cause) { super(message, cause); }
}
