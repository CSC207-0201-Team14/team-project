package use_case.monitor_service_health;

/** Indicates that observation storage could not complete an operation. */
public final class ObservationStoreException extends RuntimeException {

    public ObservationStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}