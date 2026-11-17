package data_access;

import entity.Incident;
import use_case.investigate_incident.IncidentRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/** Session-local storage. Use from the serialized UC3 caller thread. */
public final class InMemoryIncidentRepository implements IncidentRepository {
    private final Map<UUID, Incident> incidents = new LinkedHashMap<>();

    @Override
    public Optional<Incident> findById(UUID incidentId) {
        return Optional.ofNullable(incidents.get(incidentId));
    }

    @Override
    public List<Incident> findUnresolvedBySystemId(UUID systemId) {
        return incidents.values().stream()
                .filter(incident -> incident.getSystemId().equals(systemId))
                .filter(incident -> incident.getStatus() != Incident.Status.RESOLVED)
                .collect(Collectors.toUnmodifiableList());
    }

    @Override
    public void save(Incident incident) {
        incidents.put(incident.getId(), incident);
    }
}
