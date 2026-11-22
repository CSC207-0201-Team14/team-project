package use_case.investigate_incident;

import entity.Dependency;
import entity.DiagnosticFinding;
import entity.HealthCheckResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Supplier;

/** A failed required service may explain a later failure along a directed path. */
public final class DependencyFailureRule implements DiagnosticRule {
    private final Supplier<UUID> ids;

    public DependencyFailureRule(Supplier<UUID> ids) {
        this.ids = ids;
    }

    @Override
    public List<DiagnosticFinding> evaluate(DiagnosticSnapshot snapshot) {
        if (snapshot.getConfiguration().getDependencyUnavailableReason().isPresent()) {
            return List.of();
        }
        TreeSet<UUID> failingServices = new TreeSet<>();
        snapshot.getFailures().forEach(failure -> failingServices.add(failure.getServiceId()));
        TreeSet<UUID> configuredServices = new TreeSet<>();
        snapshot.getConfiguration().getSystem().getServices().forEach(service -> configuredServices.add(service.getId()));
        List<DiagnosticFinding> findings = new ArrayList<>();
        for (UUID affected : failingServices) {
            Optional<HealthCheckResult> affectedFailure = snapshot.currentFailure(affected);
            if (affectedFailure.isEmpty()) {
                continue;
            }
            for (UUID cause : configuredServices) {
                Optional<List<Dependency>> path = snapshot.getGraph().path(affected, cause);
                Optional<HealthCheckResult> causeFailure = snapshot.currentFailure(cause);
                if (path.isPresent() && causeFailure.isPresent()
                        && !causeFailure.get().getTimestamp().isAfter(affectedFailure.get().getTimestamp())) {
                    findings.add(new DiagnosticFinding(ids.get(), "failed-dependency", affected,
                            snapshot.getAssessedAt(), Optional.of(cause),
                            List.of(affectedFailure.get().getId(), causeFailure.get().getId()), path.get(),
                            "A required service has a failed check at or before the dependent service's failed check. "
                                    + "The dependency path supports a suspected cause; causation remains uncertain.",
                            List.of("Inspect the required service and the recorded dependency path.")));
                }
            }
        }
        return List.copyOf(findings);
    }
}
