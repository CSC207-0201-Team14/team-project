package use_case.monitor_service_health;

import entity.FailureEvent;

public interface FailureEventListener {

    /**
     * Delivers a failure event.
     *
     * @return true when processing is confirmed or rejected;
     *         false when the same event should be retried
     */
    boolean deliver(FailureEvent event);
}