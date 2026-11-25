package interface_adapter.investigate_incident;

import entity.HealthCheckResult;
import use_case.investigate_incident.ObservationReader;
import use_case.monitor_service_health.ObservationLookup;
import use_case.monitor_service_health.ObservationStoreException;
import use_case.shared.IncidentDataException;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class ObservationLookupReader implements ObservationReader {
    private final ObservationLookup lookup;

    public ObservationLookupReader(ObservationLookup lookup) {
        this.lookup = Objects.requireNonNull(lookup, "lookup");
    }

    @Override
    public Map<UUID, HealthCheckResult> findByIds(Set<UUID> serviceIds, Set<UUID> observationIds) {
        try {
            return lookup.findByIds(serviceIds, observationIds);
        } catch (ObservationStoreException exception) {
            throw new IncidentDataException(exception.getMessage(), exception);
        }
    }
}
