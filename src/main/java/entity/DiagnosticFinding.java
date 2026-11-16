package entity;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class DiagnosticFinding {
    private final UUID id;
    private final String ruleId;
    private final UUID affectedServiceId;
    private final Instant createdAt;
    private final UUID suspectedCauseServiceId;
    private final List<UUID> observationIds;
    private final List<Dependency> dependencyEvidence;
    private final String explanation;
    private final List<String> nextSteps;

    public DiagnosticFinding(UUID id, String ruleId, UUID affectedServiceId, Instant createdAt,
                             Optional<UUID> suspectedCauseServiceId, List<UUID> observationIds,
                             List<Dependency> dependencyEvidence, String explanation, List<String> nextSteps) {
        this.id = Objects.requireNonNull(id, "id");
        this.ruleId = Objects.requireNonNull(ruleId, "ruleId");
        this.affectedServiceId = Objects.requireNonNull(affectedServiceId, "affectedServiceId");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.suspectedCauseServiceId = suspectedCauseServiceId.orElse(null);
        this.observationIds = List.copyOf(observationIds);
        this.dependencyEvidence = List.copyOf(dependencyEvidence);
        this.explanation = Objects.requireNonNull(explanation, "explanation");
        this.nextSteps = List.copyOf(nextSteps);
        if (ruleId.isBlank() || explanation.isBlank() || this.observationIds.isEmpty()) {
            throw new IllegalArgumentException("A finding requires a rule, an explanation and observation evidence");
        }
    }

    public UUID getId() {
        return id;
    }

    public String getRuleId() {
        return ruleId;
    }

    public UUID getAffectedServiceId() {
        return affectedServiceId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Optional<UUID> getSuspectedCauseServiceId() {
        return Optional.ofNullable(suspectedCauseServiceId);
    }

    public List<UUID> getObservationIds() {
        return observationIds;
    }

    public List<Dependency> getDependencyEvidence() {
        return dependencyEvidence;
    }

    public String getExplanation() {
        return explanation;
    }

    public List<String> getNextSteps() {
        return nextSteps;
    }
}
