package entity;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

public final class Incident {
    public enum Status { OPEN, INVESTIGATING, RESOLVED }

    private final UUID id;
    private final UUID systemId;
    private final Instant openedAt;
    private final Instant investigatingAt;
    private final Instant resolvedAt;
    private final List<FailureEvent> failures;
    private final Set<UUID> potentiallyAffectedServiceIds;
    private final List<DiagnosticFinding> activeFindings;
    private final List<DiagnosticFinding> findingHistory;
    private final List<HealthCheckResult> resolutionEvidence;

    private Incident(UUID id, UUID systemId, Instant openedAt, Instant investigatingAt, Instant resolvedAt,
                     List<FailureEvent> failures, Set<UUID> potentiallyAffectedServiceIds,
                     List<DiagnosticFinding> activeFindings, List<DiagnosticFinding> findingHistory,
                     List<HealthCheckResult> resolutionEvidence) {
        this.id = id;
        this.systemId = systemId;
        this.openedAt = openedAt;
        this.investigatingAt = investigatingAt;
        this.resolvedAt = resolvedAt;
        this.failures = List.copyOf(failures);
        this.potentiallyAffectedServiceIds = Set.copyOf(potentiallyAffectedServiceIds);
        this.activeFindings = List.copyOf(activeFindings);
        this.findingHistory = List.copyOf(findingHistory);
        this.resolutionEvidence = List.copyOf(resolutionEvidence);
    }

    public static Incident open(UUID id, UUID systemId, Instant openedAt, FailureEvent firstFailure) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(systemId, "systemId");
        Objects.requireNonNull(openedAt, "openedAt");
        if (firstFailure.getTimestamp().isAfter(openedAt)) {
            throw new IllegalArgumentException("The first failure must occur by the time the incident opens");
        }
        return new Incident(id, systemId, openedAt, null, null, List.of(firstFailure), Set.of(),
                List.of(), List.of(), List.of());
    }

    /**
     * Rebuilds a saved incident with its original finding history and resolution evidence.
     * A null transition time means that transition was skipped or has not happened.
     * Active findings reference history entries by ID and retain their supplied order.
     */
    public static Incident restore(UUID id, UUID systemId, Instant openedAt, Instant investigatingAt,
                                   Instant resolvedAt, List<FailureEvent> failures,
                                   Set<UUID> potentiallyAffectedServiceIds, List<DiagnosticFinding> activeFindings,
                                   List<DiagnosticFinding> findingHistory, List<HealthCheckResult> resolutionEvidence) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(systemId, "systemId");
        Objects.requireNonNull(openedAt, "openedAt");
        if (investigatingAt != null && investigatingAt.isBefore(openedAt)) {
            throw new IllegalArgumentException("Investigation cannot begin before the incident opens");
        }
        Instant lastUnresolvedAt = investigatingAt == null ? openedAt : investigatingAt;
        if (resolvedAt != null && resolvedAt.isBefore(lastUnresolvedAt)) {
            throw new IllegalArgumentException("Resolution timestamps are out of order");
        }
        if (failures.isEmpty()) {
            throw new IllegalArgumentException("An incident requires at least one failure");
        }

        Map<UUID, DiagnosticFinding> historyById = new HashMap<>();
        for (DiagnosticFinding finding : findingHistory) {
            if (historyById.put(finding.getId(), finding) != null) {
                throw new IllegalArgumentException("Finding history must contain unique finding IDs");
            }
        }
        List<DiagnosticFinding> restoredActiveFindings = new ArrayList<>();
        for (DiagnosticFinding active : activeFindings) {
            DiagnosticFinding finding = historyById.get(active.getId());
            if (finding == null) {
                throw new IllegalArgumentException("Active finding is missing from history: " + active.getId());
            }
            restoredActiveFindings.add(finding);
        }
        return new Incident(id, systemId, openedAt, investigatingAt, resolvedAt, failures, potentiallyAffectedServiceIds,
                restoredActiveFindings, findingHistory, resolutionEvidence);
    }

    public Incident startInvestigation(Instant timestamp) {
        if (getStatus() != Status.OPEN) {
            throw new IllegalStateException("Only an open incident can enter investigation");
        }
        if (timestamp.isBefore(openedAt)) {
            throw new IllegalArgumentException("Investigation cannot begin before the incident opens");
        }
        return new Incident(id, systemId, openedAt, timestamp, null, failures, potentiallyAffectedServiceIds,
                activeFindings, findingHistory, List.of());
    }

    /** The correlation policy selects the failures that belong to this incident. */
    public Incident recordFailure(FailureEvent failure) {
        requireUnresolved();
        if (failures.stream().anyMatch(existing -> existing.getId().equals(failure.getId()))) {
            return this;
        }
        List<FailureEvent> updated = new ArrayList<>(failures);
        updated.add(failure);
        return new Incident(id, systemId, openedAt, investigatingAt, null, updated, potentiallyAffectedServiceIds,
                activeFindings, findingHistory, List.of());
    }

    /** Replaces the current assessment while retaining previous findings for review. */
    public Incident updateAssessment(Set<UUID> potentiallyAffected, List<DiagnosticFinding> findings) {
        requireUnresolved();
        List<DiagnosticFinding> updatedHistory = new ArrayList<>(findingHistory);
        Set<UUID> knownIds = findingHistory.stream().map(DiagnosticFinding::getId).collect(Collectors.toSet());
        for (DiagnosticFinding finding : findings) {
            if (finding.getCreatedAt().isBefore(openedAt)) {
                throw new IllegalArgumentException("An incident finding cannot predate the incident");
            }
            if (!knownIds.add(finding.getId())) {
                throw new IllegalArgumentException("Each assessment must use fresh finding IDs");
            }
            updatedHistory.add(finding);
        }
        return new Incident(id, systemId, openedAt, investigatingAt, null, failures, potentiallyAffected,
                findings, updatedHistory, List.of());
    }

    /**
     * Accepts health evaluated against the configured thresholds by the monitoring use case.
     * verificationStartedAt is the earliest instant from which follow-up checks may count.
     */
    public Incident resolve(Instant timestamp, Instant verificationStartedAt, Map<UUID, ServiceHealth> healthByService) {
        requireUnresolved();
        if (verificationStartedAt.isBefore(openedAt) || timestamp.isBefore(verificationStartedAt)
                || (investigatingAt != null && timestamp.isBefore(investigatingAt))
                || findingHistory.stream().anyMatch(finding -> finding.getCreatedAt().isAfter(timestamp))) {
            throw new IllegalArgumentException("Resolution timestamps are out of order");
        }

        Set<UUID> requiredServices = new TreeSet<>(getFailingServiceIds());
        requiredServices.addAll(potentiallyAffectedServiceIds);
        for (DiagnosticFinding finding : activeFindings) {
            finding.getSuspectedCauseServiceId().ifPresent(requiredServices::add);
        }

        List<HealthCheckResult> evidence = new ArrayList<>();
        for (UUID serviceId : requiredServices) {
            ServiceHealth health = healthByService.get(serviceId);
            if (health == null || !serviceId.equals(health.getServiceId()) || health.getStatus() != HealthStatus.HEALTHY) {
                throw new IllegalStateException("Recovery has not been verified for service " + serviceId);
            }
            HealthCheckResult result = health.getLatestResult().orElseThrow();
            if (result.getOutcome() != HealthCheckResult.Outcome.PASS
                    || result.getTimestamp().isBefore(verificationStartedAt)
                    || result.getTimestamp().isAfter(timestamp)
                    || failures.stream().anyMatch(failure -> failure.getServiceId().equals(serviceId)
                            && !result.getTimestamp().isAfter(failure.getTimestamp()))) {
                throw new IllegalStateException("Recovery requires a fresh passing check for service " + serviceId);
            }
            evidence.add(result);
        }
        return new Incident(id, systemId, openedAt, investigatingAt, timestamp, failures, potentiallyAffectedServiceIds,
                activeFindings, findingHistory, evidence);
    }

    private void requireUnresolved() {
        if (getStatus() == Status.RESOLVED) {
            throw new IllegalStateException("The incident is already resolved");
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getSystemId() {
        return systemId;
    }

    public Status getStatus() {
        if (resolvedAt != null) {
            return Status.RESOLVED;
        }
        return investigatingAt == null ? Status.OPEN : Status.INVESTIGATING;
    }

    public Instant getOpenedAt() {
        return openedAt;
    }

    public Optional<Instant> getInvestigatingAt() {
        return Optional.ofNullable(investigatingAt);
    }

    public Optional<Instant> getResolvedAt() {
        return Optional.ofNullable(resolvedAt);
    }

    public List<FailureEvent> getFailures() {
        return failures;
    }

    public Set<UUID> getFailingServiceIds() {
        return failures.stream().map(FailureEvent::getServiceId).collect(Collectors.toUnmodifiableSet());
    }

    public Set<UUID> getPotentiallyAffectedServiceIds() {
        return potentiallyAffectedServiceIds;
    }

    public List<DiagnosticFinding> getActiveFindings() {
        return activeFindings;
    }

    public List<DiagnosticFinding> getFindingHistory() {
        return findingHistory;
    }

    public List<HealthCheckResult> getResolutionEvidence() {
        return resolutionEvidence;
    }
}
