package use_case.investigate_incident;

import entity.DiagnosticFinding;
import entity.FailureEvent;
import entity.Incident;
import use_case.shared.ConfigurationSnapshot;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/** Coordinates independent correlation and diagnostic strategies without I/O. */
public final class RuleBasedIncidentAssessment implements IncidentAssessment {
    private final IncidentCorrelationPolicy correlation;
    private final List<DiagnosticRule> rules;
    private final Clock clock;
    private final Supplier<UUID> incidentIds;
    private final Duration evidenceWindow;

    public RuleBasedIncidentAssessment(IncidentCorrelationPolicy correlation, List<DiagnosticRule> rules,
                                       Clock clock, Supplier<UUID> incidentIds, Duration evidenceWindow) {
        if (evidenceWindow.isNegative() || evidenceWindow.isZero()) {
            throw new IllegalArgumentException("Evidence window must be positive");
        }
        this.correlation = correlation;
        this.rules = List.copyOf(rules);
        this.clock = clock;
        this.incidentIds = incidentIds;
        this.evidenceWindow = evidenceWindow;
    }

    @Override
    public Incident assess(FailureInput input, ConfigurationSnapshot configuration, List<Incident> unresolved) {
        Instant now = clock.instant();
        if (!input.getSystemId().equals(configuration.getSystem().getId())
                || input.getFailure().getTimestamp().isAfter(now)) {
            throw new IllegalArgumentException("Assessment requires matching configuration and an elapsed failure");
        }
        List<FailureEvent> candidateFailures = new ArrayList<>();
        for (Incident incident : unresolved) {
            if (incident.getSystemId().equals(input.getSystemId()) && incident.getStatus() != Incident.Status.RESOLVED) {
                candidateFailures.addAll(incident.getFailures());
            }
        }
        candidateFailures.add(input.getFailure());
        DiagnosticSnapshot correlationSnapshot = new DiagnosticSnapshot(configuration, candidateFailures,
                input.getRecentObservations(), now, evidenceWindow);
        Optional<Incident> selected = correlation.select(input, correlationSnapshot, unresolved);
        if (selected.isPresent() && selected.get().getFailures().stream()
                .anyMatch(failure -> failure.getId().equals(input.getFailure().getId()))) {
            return selected.get();
        }
        Incident incident = selected.map(existing -> existing.recordFailure(input.getFailure()))
                .orElseGet(() -> Incident.open(incidentIds.get(), input.getSystemId(), now, input.getFailure()));
        DiagnosticSnapshot diagnosticSnapshot = new DiagnosticSnapshot(configuration, incident.getFailures(),
                input.getRecentObservations(), now, evidenceWindow);
        List<DiagnosticFinding> findings = new ArrayList<>();
        for (DiagnosticRule rule : rules) {
            findings.addAll(rule.evaluate(diagnosticSnapshot));
        }
        Set<UUID> potentialImpact = findings.stream().map(DiagnosticFinding::getAffectedServiceId)
                .filter(serviceId -> !incident.getFailingServiceIds().contains(serviceId)).collect(Collectors.toSet());
        return incident.updateAssessment(potentialImpact, findings);
    }
}
