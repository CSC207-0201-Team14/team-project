package use_case.investigate_incident;

import entity.DiagnosticFinding;
import entity.FailureEvent;
import entity.HealthCheckResult;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/** Records observed threshold crossings without assigning a cause. */
public final class ObservedFailureRule implements DiagnosticRule {
    private final Supplier<UUID> ids;

    public ObservedFailureRule(Supplier<UUID> ids) {
        this.ids = ids;
    }

    @Override
    public List<DiagnosticFinding> evaluate(DiagnosticSnapshot snapshot) {
        TreeMap<UUID, FailureEvent> latest = new TreeMap<>();
        snapshot.getFailures().stream().sorted(Comparator.comparing(FailureEvent::getTimestamp)
                .thenComparing(FailureEvent::getId)).forEach(failure -> latest.put(failure.getServiceId(), failure));
        // Extension 3a: without configured dependencies, no other service can be linked to this failure.
        boolean noDependencies = snapshot.getConfiguration().getSystem().getServices().size() > 1
                && snapshot.getConfiguration().getSystem().getDependencies().isEmpty();
        List<DiagnosticFinding> findings = new ArrayList<>();
        for (FailureEvent failure : latest.values()) {
            String explanation = "A threshold-crossing failure was recorded. Its cause is unknown from this evidence.";
            if (noDependencies) {
                explanation += " No dependencies are configured for this system, so dependency-based impact "
                        + "analysis is not possible.";
            }
            List<UUID> evidence = failure.getEvidence().stream().map(HealthCheckResult::getId)
                    .sorted().collect(Collectors.toList());
            findings.add(new DiagnosticFinding(ids.get(), "observed-failure", failure.getServiceId(),
                    snapshot.getAssessedAt(), Optional.empty(), evidence, List.of(), explanation,
                    List.of("Inspect the recorded checks and service logs.")));
        }
        return List.copyOf(findings);
    }
}
