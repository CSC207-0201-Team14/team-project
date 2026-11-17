package use_case.investigate_incident;

import entity.HealthCheckResult;
import entity.Incident;
import entity.RecoveryAction;
import java.time.Clock;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import use_case.shared.IncidentDataException;
import use_case.shared.IncidentRepository;

/** Coordinates ports; diagnosis and correlation are supplied through IncidentAssessment. */
public final class IncidentInteractor implements IncidentInputBoundary {
    private final ConfigurationReader configurations;
    private final ObservationReader observations;
    private final IncidentRepository incidents;
    private final IncidentAssessment assessment;
    private final RecoveryRecommendationProvider recommendations;
    private final Clock clock;
    private final IncidentOutputBoundary output;

    public IncidentInteractor(ConfigurationReader configurations, ObservationReader observations,
                              IncidentRepository incidents, IncidentAssessment assessment,
                              RecoveryRecommendationProvider recommendations, Clock clock,
                              IncidentOutputBoundary output) {
        this.configurations = configurations;
        this.observations = observations;
        this.incidents = incidents;
        this.assessment = assessment;
        this.recommendations = recommendations;
        this.clock = clock;
        this.output = output;
    }

    @Override
    public void processFailure(FailureInput input) {
        Incident result;
        try {
            Optional<ConfigurationSnapshot> configuration = configurations.findBySystemId(input.getSystemId());
            if (configuration.isEmpty()) {
                output.presentError("Monitored system was not found");
                return;
            }
            Set<UUID> serviceIds = configuration.get().getSystem().getServices().stream()
                    .map(service -> service.getId()).collect(Collectors.toSet());
            if (!serviceIds.contains(input.getFailure().getServiceId())
                    || input.getRecentObservations().stream().anyMatch(resultItem ->
                    !serviceIds.contains(resultItem.getServiceId()))) {
                output.presentError("Failure input references a service outside the monitored system");
                return;
            }
            List<Incident> unresolved = incidents.findUnresolvedBySystemId(input.getSystemId());
            result = assessment.assess(input, configuration.get(), List.copyOf(unresolved));
            incidents.save(result);
        } catch (IncidentDataException exception) {
            output.presentError(exception.getMessage());
            return;
        }
        output.presentProcessed(result);
    }

    @Override
    public void openIncident(UUID incidentId) {
        IncidentDetails details;
        try {
            Optional<Incident> stored = incidents.findById(incidentId);
            if (stored.isEmpty()) {
                output.presentError("Incident was not found");
                return;
            }
            Incident incident = stored.get();
            if (incident.getStatus() == Incident.Status.OPEN) {
                incident = incident.startInvestigation(clock.instant());
                incidents.save(incident);
            }
            Incident opened = incident;
            Map<UUID, HealthCheckResult> evidence = new HashMap<>();
            incident.getFailures().forEach(failure -> failure.getEvidence().forEach(result ->
                    evidence.put(result.getId(), result)));
            Set<UUID> required = new HashSet<>(evidence.keySet());
            incident.getFindingHistory().forEach(finding -> required.addAll(finding.getObservationIds()));
            incident.getResolutionEvidence().forEach(result -> {
                required.add(result.getId());
                evidence.put(result.getId(), result);
            });
            Set<UUID> missing = new HashSet<>(required);
            missing.removeAll(evidence.keySet());
            if (!missing.isEmpty()) {
                evidence.putAll(observations.findByIds(Set.copyOf(missing)));
            }
            missing.removeAll(evidence.keySet());
            List<RecoveryAction> recommended = configurations.findBySystemId(incident.getSystemId())
                    .map(configuration -> recommendations.recommend(opened, configuration))
                    .orElse(List.of());
            details = new IncidentDetails(opened, evidence, missing, recommended);
        } catch (IncidentDataException exception) {
            output.presentError(exception.getMessage());
            return;
        }
        output.presentDetails(details);
    }
}
