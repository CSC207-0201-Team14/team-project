package use_case.investigate_incident;

/**
 * Outcome of processFailure for the background caller delivering failure events.
 * The output boundary is still notified for presentation; this result only decides redelivery.
 */
public enum FailureProcessingResult {
    /** The incident was saved. Duplicate deliveries of a stored event ID also return this. */
    PROCESSED,
    /** Configuration or incident storage could not be accessed. Redeliver with the same event ID. */
    RETRYABLE,
    /** The input cannot be processed as given, such as an unknown system or a foreign service. Do not redeliver. */
    REJECTED
}
