package use_case.investigate_incident;

import entity.HealthCheckResult;
import entity.Incident;
import entity.RecoveryAction;
import entity.Service;
import java.time.Clock;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import use_case.shared.ConfigurationReader;
import use_case.shared.ConfigurationSnapshot;
import use_case.shared.IncidentDataException;
import use_case.shared.IncidentRepository;

/** Coordinates ports; diagnosis and correlation are supplied through IncidentAssessment. */
public final class IncidentInteractor implements IncidentInputBoundary {
    private final ConfigurationReader configurations;
    private final ObservationReader observations;
    private final IncidentRepository incidents;
    private final IncidentAssessment assessment;
    private final IncidentRecoveryRecommendations recommendations;
    private final Clock clock;
    private final IncidentOutputBoundary output;

    public IncidentInteractor(ConfigurationReader configurations, ObservationReader observations,
                              IncidentRepository incidents, IncidentAssessment assessment,
                              IncidentRecoveryRecommendations recommendations, Clock clock,
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
    public FailureProcessingResult processFailure(FailureInput input) {
        Incident result;
        try {
            Optional<ConfigurationSnapshot> configuration = configurations.findBySystemId(input.getSystemId());
            if (configuration.isEmpty()) {
                output.presentError("Monitored system was not found");
                return FailureProcessingResult.REJECTED;
            }
            Set<UUID> serviceIds = configuration.get().getSystem().getServices().stream()
                    .map(service -> service.getId()).collect(Collectors.toSet());
            if (!serviceIds.contains(input.getFailure().getServiceId())) {
                output.presentError("The failed service is no longer part of the monitored system");
                return FailureProcessingResult.REJECTED;
            }
            FailureInput current = withinRoster(input, serviceIds);
            List<Incident> history = incidents.findBySystemId(input.getSystemId());
            UUID failureId = input.getFailure().getId();
            Optional<Incident> recorded = history.stream().filter(incident -> incident.getFailures().stream()
                    .anyMatch(failure -> failure.getId().equals(failureId))).findFirst();
            if (recorded.isPresent()) {
                result = recorded.get();
            } else {
                List<Incident> unresolved = history.stream()
                        .filter(incident -> incident.getStatus() != Incident.Status.RESOLVED)
                        .collect(Collectors.toUnmodifiableList());
                try {
                    result = assessment.assess(current, configuration.get(), unresolved);
                } catch (IllegalArgumentException exception) {
                    output.presentError("Failure input could not be assessed: " + exception.getMessage());
                    return FailureProcessingResult.REJECTED;
                }
                incidents.save(result);
            }
        } catch (IncidentDataException exception) {
            output.presentError(exception.getMessage());
            return FailureProcessingResult.RETRYABLE;
        }
        output.presentProcessed(result);
        return FailureProcessingResult.PROCESSED;
    }

    @Override
    public void listIncidents(UUID systemId) {
        List<IncidentSummary> summaries;
        try {
            Map<UUID, String> names = serviceNames(configurations.findBySystemId(systemId));
            summaries = incidents.findBySystemId(systemId).stream()
                    .map(incident -> new IncidentSummary(incident, names))
                    .sorted(Comparator.comparing(IncidentSummary::getOpenedAt).reversed()
                            .thenComparing(IncidentSummary::getIncidentId))
                    .collect(Collectors.toUnmodifiableList());
        } catch (IncidentDataException exception) {
            output.presentError(exception.getMessage());
            return;
        }
        output.presentIncidents(summaries);
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
                evidence.putAll(observations.findByIds(relatedServices(incident), Set.copyOf(missing)));
            }
            missing.removeAll(evidence.keySet());
            Optional<ConfigurationSnapshot> configuration = configurations.findBySystemId(incident.getSystemId());
            List<RecoveryAction> recommended = configuration
                    .map(snapshot -> recommendations.recommend(opened, snapshot))
                    .orElse(List.of());
            details = new IncidentDetails(opened, evidence, missing, recommended, serviceNames(configuration));
        } catch (IncidentDataException exception) {
            output.presentError(exception.getMessage());
            return;
        }
        output.presentDetails(details);
    }

    /** Names from the current roster; empty when the system configuration is missing. */
    /**
     * Monitoring reads recent observations before UC3 reads the configuration, so a service removed in between
     * can still appear. Its observations cannot support a diagnosis of the current system, so they are left out
     * instead of rejecting the failure of a service that is still configured.
     */
    private static FailureInput withinRoster(FailureInput input, Set<UUID> serviceIds) {
        List<HealthCheckResult> configured = input.getRecentObservations().stream()
                .filter(observation -> serviceIds.contains(observation.getServiceId()))
                .collect(Collectors.toUnmodifiableList());
        return configured.size() == input.getRecentObservations().size() ? input
                : new FailureInput(input.getSystemId(), input.getFailure(), configured);
    }

    private static Map<UUID, String> serviceNames(Optional<ConfigurationSnapshot> configuration) {
        return configuration.map(snapshot -> snapshot.getSystem().getServices().stream()
                        .collect(Collectors.toUnmodifiableMap(Service::getId, Service::getName)))
                .orElse(Map.of());
    }

    /** Services whose observations the incident can cite: failures, findings, their edges and resolution. */
    private static Set<UUID> relatedServices(Incident incident) {
        Set<UUID> services = new HashSet<>();
        incident.getFailures().forEach(failure -> services.add(failure.getServiceId()));
        incident.getFindingHistory().forEach(finding -> {
            services.add(finding.getAffectedServiceId());
            finding.getSuspectedCauseServiceId().ifPresent(services::add);
            finding.getDependencyEvidence().forEach(edge -> {
                services.add(edge.getDependentServiceId());
                services.add(edge.getRequiredServiceId());
            });
        });
        incident.getResolutionEvidence().forEach(result -> services.add(result.getServiceId()));
        return Set.copyOf(services);
    }
}
