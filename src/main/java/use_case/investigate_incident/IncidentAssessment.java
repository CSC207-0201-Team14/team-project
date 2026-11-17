package use_case.investigate_incident;

import entity.Incident;
import java.util.List;

/**
 * Seam for the later deterministic diagnosis and correlation implementation.
 * Implementations perform no I/O and do not mutate arguments. Return a new or updated
 * unresolved incident for input.systemId containing the delivered failure.
 * Repeated event IDs must reuse their existing incident. Correlation requires supporting
 * evidence; timestamp proximity alone is insufficient. Preserve historical findings.
 * Unknown causes and unavailable dependencies remain valid inputs.
 */
public interface IncidentAssessment {
    Incident assess(FailureInput input, ConfigurationSnapshot configuration, List<Incident> unresolved);
}
