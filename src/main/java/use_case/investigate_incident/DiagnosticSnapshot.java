package use_case.investigate_incident;

import entity.FailureEvent;
import entity.HealthCheckResult;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Immutable evidence shared by pure diagnosis and correlation policies. */
public final class DiagnosticSnapshot {
    private final ConfigurationSnapshot configuration;
    private final List<FailureEvent> failures;
    private final List<HealthCheckResult> observations;
    private final Instant assessedAt;
    private final Instant earliestEvidence;
    private final DependencyGraph graph;

    public DiagnosticSnapshot(ConfigurationSnapshot configuration, List<FailureEvent> failures,
                              List<HealthCheckResult> observations, Instant assessedAt, Duration evidenceWindow) {
        if (evidenceWindow.isNegative() || evidenceWindow.isZero()) {
            throw new IllegalArgumentException("Evidence window must be positive");
        }
        this.configuration = configuration;
        this.failures = List.copyOf(failures);
        this.assessedAt = assessedAt;
        this.earliestEvidence = assessedAt.minus(evidenceWindow);
        List<HealthCheckResult> combined = new ArrayList<>(observations);
        failures.forEach(failure -> combined.addAll(failure.getEvidence()));
        this.observations = List.copyOf(combined);
        this.graph = new DependencyGraph(configuration.getSystem().getDependencies());
    }

    /** Conflicting outcomes at the latest timestamp are insufficient to assert failure. */
    public Optional<HealthCheckResult> currentFailure(UUID serviceId) {
        List<HealthCheckResult> available = new ArrayList<>();
        for (HealthCheckResult result : observations) {
            if (result.getServiceId().equals(serviceId) && !result.getTimestamp().isAfter(assessedAt)
                    && !result.getTimestamp().isBefore(earliestEvidence)) {
                available.add(result);
            }
        }
        available.sort(Comparator.comparing(HealthCheckResult::getTimestamp).reversed()
                .thenComparing(HealthCheckResult::getId));
        if (available.isEmpty()) {
            return Optional.empty();
        }
        HealthCheckResult latest = available.get(0);
        for (HealthCheckResult result : available) {
            if (result.getTimestamp().equals(latest.getTimestamp())
                    && result.getOutcome() != HealthCheckResult.Outcome.FAIL) {
                return Optional.empty();
            }
        }
        return Optional.of(latest);
    }

    /** A passing check or monitoring error interrupts evidence of continuous failure. */
    public boolean isContinuingFailure(FailureEvent failure) {
        if (failure.getTimestamp().isBefore(earliestEvidence)
                || currentFailure(failure.getServiceId()).isEmpty()) {
            return false;
        }
        return observations.stream().noneMatch(result -> result.getServiceId().equals(failure.getServiceId())
                && !result.getTimestamp().isBefore(failure.getTimestamp())
                && !result.getTimestamp().isAfter(assessedAt)
                && result.getOutcome() != HealthCheckResult.Outcome.FAIL);
    }

    public ConfigurationSnapshot getConfiguration() { return configuration; }
    public List<FailureEvent> getFailures() { return failures; }
    public Instant getAssessedAt() { return assessedAt; }
    public DependencyGraph getGraph() { return graph; }
}
