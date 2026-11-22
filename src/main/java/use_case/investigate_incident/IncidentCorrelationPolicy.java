package use_case.investigate_incident;

import entity.Incident;
import java.util.List;
import java.util.Optional;

/** Selects an existing incident; performs no diagnosis, mutation, storage or recovery. */
public interface IncidentCorrelationPolicy {
    Optional<Incident> select(FailureInput input, DiagnosticSnapshot snapshot, List<Incident> unresolved);
}
