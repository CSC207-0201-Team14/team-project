package use_case.recover_service;

import entity.FailureEvent;
import entity.HealthCheckResult;
import entity.HealthStatus;
import entity.Incident;
import entity.Service;
import entity.ServiceHealth;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Evaluates whether services have recovered from checks inside a window: a service is HEALTHY once its most
 * recent checks form a trailing streak of at least its configured recovery threshold of consecutive passes.
 * <p>
 * This is intentionally stricter than UC2's HealthStateEvaluator, so the two are not interchangeable. UC2
 * keeps a service HEALTHY until failures reach its failure threshold, whereas here any failure after the
 * passes resets the streak. For example, with failure threshold 2 and recovery threshold 3, PASS PASS PASS FAIL
 * leaves UC2 HEALTHY but is UNHEALTHY here. Recovery evidence should be uninterrupted, and a restart should not
 * be skipped while the latest check is failing.
 * <p>
 * Two windows are used: {@link #evaluate} counts checks after verification started (and after each service's
 * latest recorded failure) to decide resolution, and {@link #assessSinceLatestFailure} counts checks after the
 * target's latest recorded failure to decide whether a restart is still warranted.
 * Pure: it never reads storage or contacts services.
 */
public final class RecoveryVerifier {
    private static final String SINCE_RECOVERY = "since recovery began";
    private static final String SINCE_FAILURE = "since its last recorded failure";

    public VerificationResult evaluate(Incident incident, Instant verificationStartedAt, Map<UUID, Service> services,
                                       Map<UUID, List<HealthCheckResult>> observationsByService, Instant now) {
        Objects.requireNonNull(incident, "incident");
        Objects.requireNonNull(verificationStartedAt, "verificationStartedAt");
        Objects.requireNonNull(now, "now");

        List<ServiceVerification> progress = new ArrayList<>();
        boolean monitoringError = false;
        for (UUID serviceId : incident.getRecoveryRequiredServiceIds()) {
            Service service = services.get(serviceId);
            ServiceVerification verification;
            if (service == null) {
                verification = new ServiceVerification(serviceId, ServiceHealth.unobserved(serviceId), 0, 0,
                        "The service is no longer configured, so its recovery cannot be verified.");
                monitoringError = true;
            } else {
                Instant windowStart = windowStart(incident, serviceId, verificationStartedAt);
                List<HealthCheckResult> fresh = freshObservations(serviceId,
                        observationsByService.getOrDefault(serviceId, List.of()), windowStart, now);
                verification = evaluateService(service, fresh, SINCE_RECOVERY);
                if (verification.getHealth().getLatestResult()
                        .map(result -> result.getOutcome() == HealthCheckResult.Outcome.MONITORING_ERROR)
                        .orElse(false)) {
                    monitoringError = true;
                }
            }
            progress.add(verification);
        }

        VerificationResult.Status status;
        if (progress.stream().allMatch(ServiceVerification::isVerified)) {
            status = VerificationResult.Status.SATISFIED;
        } else if (monitoringError) {
            status = VerificationResult.Status.MONITORING_ERROR;
        } else {
            status = VerificationResult.Status.PENDING;
        }
        return new VerificationResult(incident.getId(), verificationStartedAt, status, progress);
    }

    /**
     * Assesses a recovery target's current health from the checks recorded after its latest failure in the
     * incident (and never before the incident opened), using the same trailing-streak rule as {@link #evaluate}.
     * HEALTHY means the target already meets its recovery threshold; UNHEALTHY means a check in the window failed;
     * UNKNOWN covers no checks yet, a monitoring error, or passes below the threshold.
     */
    public ServiceVerification assessSinceLatestFailure(Incident incident, Service service,
                                                        List<HealthCheckResult> observations, Instant now) {
        Objects.requireNonNull(incident, "incident");
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(now, "now");
        Instant windowStart = windowStart(incident, service.getId(), incident.getOpenedAt());
        return evaluateService(service, freshObservations(service.getId(), observations, windowStart, now),
                SINCE_FAILURE);
    }

    /** Checks must follow both the earliest allowed instant and the service's latest failure in the incident. */
    private static Instant windowStart(Incident incident, UUID serviceId, Instant earliest) {
        Instant start = earliest;
        for (FailureEvent failure : incident.getFailures()) {
            if (failure.getServiceId().equals(serviceId) && !failure.getTimestamp().isBefore(start)) {
                start = failure.getTimestamp().plusNanos(1);
            }
        }
        return start;
    }

    private static List<HealthCheckResult> freshObservations(UUID serviceId, List<HealthCheckResult> observations,
                                                             Instant windowStart, Instant now) {
        List<HealthCheckResult> fresh = new ArrayList<>();
        for (HealthCheckResult result : observations) {
            if (result.getServiceId().equals(serviceId) && !result.getTimestamp().isBefore(windowStart)
                    && !result.getTimestamp().isAfter(now)) {
                fresh.add(result);
            }
        }
        fresh.sort(Comparator.comparing(HealthCheckResult::getTimestamp).thenComparing(HealthCheckResult::getId));
        return fresh;
    }

    private static ServiceVerification evaluateService(Service service, List<HealthCheckResult> fresh,
                                                       String since) {
        int required = service.getMonitoringPolicy().getRecoveryThreshold();
        if (fresh.isEmpty()) {
            return new ServiceVerification(service.getId(), ServiceHealth.unobserved(service.getId()), 0, required,
                    "No checks have been recorded " + since + ".");
        }
        int passes = 0;
        boolean failedInWindow = false;
        for (HealthCheckResult result : fresh) {
            if (result.getOutcome() == HealthCheckResult.Outcome.PASS) {
                passes++;
            } else {
                passes = 0;
                failedInWindow |= result.getOutcome() == HealthCheckResult.Outcome.FAIL;
            }
        }
        HealthCheckResult latest = fresh.get(fresh.size() - 1);
        switch (latest.getOutcome()) {
            case MONITORING_ERROR:
                return new ServiceVerification(service.getId(), ServiceHealth.evaluated(HealthStatus.UNKNOWN, latest),
                        0, required, "The latest check could not be completed: " + latest.getMessage());
            case FAIL:
                return new ServiceVerification(service.getId(),
                        ServiceHealth.evaluated(HealthStatus.UNHEALTHY, latest), 0, required,
                        "The service is still failing: " + latest.getMessage());
            default:
                if (passes >= required) {
                    return new ServiceVerification(service.getId(),
                            ServiceHealth.evaluated(HealthStatus.HEALTHY, latest), passes, required,
                            "The service passed " + passes + " consecutive checks " + since + ".");
                }
                HealthStatus status = failedInWindow ? HealthStatus.UNHEALTHY : HealthStatus.UNKNOWN;
                return new ServiceVerification(service.getId(), ServiceHealth.evaluated(status, latest), passes,
                        required, "Waiting for " + (required - passes) + " more consecutive passing checks.");
        }
    }
}
