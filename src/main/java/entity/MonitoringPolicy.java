package entity;

import java.time.Duration;
import java.util.Objects;

public final class MonitoringPolicy {
    private final Duration checkInterval;
    private final Duration timeout;
    private final int failureThreshold;
    private final int recoveryThreshold;

    public MonitoringPolicy(Duration checkInterval, Duration timeout, int failureThreshold, int recoveryThreshold) {
        this.checkInterval = Objects.requireNonNull(checkInterval, "checkInterval");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (checkInterval.isZero() || checkInterval.isNegative() || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("Interval and timeout must be positive");
        }
        if (failureThreshold < 1 || recoveryThreshold < 1) {
            throw new IllegalArgumentException("Failure and recovery thresholds must be positive");
        }
        this.failureThreshold = failureThreshold;
        this.recoveryThreshold = recoveryThreshold;
    }

    public Duration getCheckInterval() {
        return checkInterval;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public int getFailureThreshold() {
        return failureThreshold;
    }

    public int getRecoveryThreshold() {
        return recoveryThreshold;
    }
}
