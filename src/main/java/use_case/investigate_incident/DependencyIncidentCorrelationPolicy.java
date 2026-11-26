package use_case.investigate_incident;

import entity.FailureEvent;
import entity.Incident;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Only one evidence-backed match can be selected; ambiguous matches remain separate. */
public final class DependencyIncidentCorrelationPolicy implements IncidentCorrelationPolicy {
    @Override
    public Optional<Incident> select(FailureInput input, DiagnosticSnapshot snapshot, List<Incident> unresolved) {
        List<Incident> matches = new ArrayList<>();
        for (Incident incident : unresolved) {
            if (!incident.getSystemId().equals(input.getSystemId()) || incident.getStatus() == Incident.Status.RESOLVED) {
                continue;
            }
            if (incident.getFailures().stream().anyMatch(failure -> failure.getId().equals(input.getFailure().getId()))) {
                return Optional.of(incident);
            }
            if (incident.getFailures().stream().anyMatch(failure -> related(input.getFailure(), failure, snapshot))) {
                matches.add(incident);
            }
        }
        if (matches.size() == 1) {
            return Optional.of(matches.get(0));
        }
        return Optional.empty();
    }

    private boolean related(FailureEvent incoming, FailureEvent existing, DiagnosticSnapshot snapshot) {
        if (!snapshot.isContinuingFailure(incoming) || !snapshot.isContinuingFailure(existing)) {
            return false;
        }
        if (incoming.getServiceId().equals(existing.getServiceId())) {
            return true;
        }
        return snapshot.getGraph().path(incoming.getServiceId(), existing.getServiceId()).isPresent()
                || snapshot.getGraph().path(existing.getServiceId(), incoming.getServiceId()).isPresent();
    }
}
