package use_case.shared;

import entity.Incident;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Incident storage shared by the investigate-incident (UC3) and recover-service (UC4) use cases.
 * Saves replace the whole incident, so callers serialize incident reads and writes on one thread.
 */
public interface IncidentRepository {
    Optional<Incident> findById(UUID incidentId);

    /** Returns every incident for the system, including resolved incidents. */
    List<Incident> findBySystemId(UUID systemId);

    default List<Incident> findUnresolvedBySystemId(UUID systemId) {
        return findBySystemId(systemId).stream()
                .filter(incident -> incident.getStatus() != Incident.Status.RESOLVED)
                .collect(Collectors.toUnmodifiableList());
    }

    /**
     * Inserts or replaces the complete immutable incident by ID, retaining its history.
     * On failure, leave the previous value intact and throw IncidentDataException.
     * All methods report storage failures through IncidentDataException.
     */
    void save(Incident incident);
}
