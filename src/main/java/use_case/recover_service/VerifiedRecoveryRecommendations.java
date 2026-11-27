package use_case.recover_service;

import entity.Incident;
import entity.RecoveryAction;
import entity.Service;
import use_case.investigate_incident.IncidentRecoveryRecommendations;
import use_case.investigate_incident.RecoveryRecommendationProvider;
import use_case.monitor_service_health.ObservationStore;
import use_case.monitor_service_health.ObservationStoreException;
import use_case.shared.ConfigurationSnapshot;
import use_case.shared.IncidentDataException;

import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Recalculates the eligible upstream failures using fresh monitoring evidence. */
public final class VerifiedRecoveryRecommendations implements IncidentRecoveryRecommendations {
    private final RecoveryRecommendationProvider provider;
    private final ObservationStore observations;
    private final RecoveryVerifier verifier;
    private final Clock clock;

    public VerifiedRecoveryRecommendations(RecoveryRecommendationProvider provider, ObservationStore observations,
                                           RecoveryVerifier verifier, Clock clock) {
        this.provider = provider;
        this.observations = observations;
        this.verifier = verifier;
        this.clock = clock;
    }

    @Override
    public List<RecoveryAction> recommend(Incident incident, ConfigurationSnapshot configuration) {
        if (incident.getStatus() == Incident.Status.RESOLVED) {
            return List.of();
        }
        Instant now = clock.instant();
        Set<UUID> recovered = new HashSet<>();
        for (Service service : configuration.getSystem().getServices()) {
            if (incident.getFailingServiceIds().contains(service.getId())
                    && assess(incident, service, now).isVerified()) {
                recovered.add(service.getId());
            }
        }
        return provider.recommend(incident, configuration, recovered).stream()
                .filter(action -> !recovered.contains(action.getTargetServiceId()))
                .collect(Collectors.toUnmodifiableList());
    }

    ServiceVerification assess(Incident incident, Service service, Instant now) {
        try {
            return verifier.assessSinceLatestFailure(incident, service,
                    observations.findByServiceId(service.getId()), now);
        } catch (ObservationStoreException exception) {
            throw new IncidentDataException("Observations could not be read: " + exception.getMessage(), exception);
        }
    }
}
