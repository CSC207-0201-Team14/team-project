package use_case.investigate_incident;

import entity.Incident;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface IncidentRepository {
    Optional<Incident> findById(UUID incidentId);

    List<Incident> findUnresolvedBySystemId(UUID systemId);

    /**
     * Inserts or replaces the complete immutable incident by ID, retaining its history.
     * On failure, leave the previous value intact and throw IncidentDataException.
     * All methods report storage failures through IncidentDataException.
     */
    void save(Incident incident);
}
