package use_case.configure_monitored_system;

import entity.MonitoringTarget;

import java.util.Objects;
import java.util.UUID;

/** The fields in the service editor, including the ID retained when a service is edited. */
public final class ServiceInput {
    private final UUID id;
    private final String name;
    private final MonitoringTarget.Method method;
    private final String target;
    private final String intervalMillis;
    private final String timeoutMillis;
    private final String failureThreshold;
    private final String recoveryThreshold;

    public ServiceInput(UUID id, String name, MonitoringTarget.Method method, String target,
                        String intervalMillis, String timeoutMillis, String failureThreshold,
                        String recoveryThreshold) {
        this.id = Objects.requireNonNull(id, "id");
        this.name = Objects.requireNonNull(name, "name");
        this.method = Objects.requireNonNull(method, "method");
        this.target = Objects.requireNonNull(target, "target");
        this.intervalMillis = Objects.requireNonNull(intervalMillis, "intervalMillis");
        this.timeoutMillis = Objects.requireNonNull(timeoutMillis, "timeoutMillis");
        this.failureThreshold = Objects.requireNonNull(failureThreshold, "failureThreshold");
        this.recoveryThreshold = Objects.requireNonNull(recoveryThreshold, "recoveryThreshold");
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public MonitoringTarget.Method getMethod() { return method; }
    public String getTarget() { return target; }
    public String getIntervalMillis() { return intervalMillis; }
    public String getTimeoutMillis() { return timeoutMillis; }
    public String getFailureThreshold() { return failureThreshold; }
    public String getRecoveryThreshold() { return recoveryThreshold; }
}
