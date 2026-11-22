package use_case.investigate_incident;

import entity.Dependency;
import entity.DiagnosticFinding;
import entity.HealthCheckResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/** Reports downstream exposure separately from services with observed threshold failures. */
public final class PotentialImpactRule implements DiagnosticRule {
    private final Supplier<UUID> ids;

    public PotentialImpactRule(Supplier<UUID> ids) {
        this.ids = ids;
    }

    @Override
    public List<DiagnosticFinding> evaluate(DiagnosticSnapshot snapshot) {
        if (snapshot.getConfiguration().getDependencyUnavailableReason().isPresent()) {
            return List.of();
        }
        Set<UUID> observed = snapshot.getFailures().stream().map(failure -> failure.getServiceId())
                .collect(Collectors.toCollection(TreeSet::new));
        TreeSet<UUID> services = new TreeSet<>();
        snapshot.getConfiguration().getSystem().getServices().forEach(service -> services.add(service.getId()));
        List<DiagnosticFinding> findings = new ArrayList<>();
        for (UUID failed : observed) {
            Optional<HealthCheckResult> evidence = snapshot.currentFailure(failed);
            if (evidence.isEmpty()) {
                continue;
            }
            for (UUID dependent : services) {
                Optional<List<Dependency>> path = snapshot.getGraph().path(dependent, failed);
                if (!observed.contains(dependent) && path.isPresent()) {
                    findings.add(new DiagnosticFinding(ids.get(), "potential-impact", dependent,
                            snapshot.getAssessedAt(), Optional.of(failed), List.of(evidence.get().getId()), path.get(),
                            "This service depends on a service with a current failed check and may be affected. "
                                    + "A threshold-crossing failure has not been recorded for this dependent in this incident.",
                            List.of("Check the dependent service and inspect its dependency path.")));
                }
            }
        }
        return List.copyOf(findings);
    }
}
