package interface_adapter.investigate_incident;

import entity.FailureEvent;
import entity.HealthCheckResult;
import entity.MonitoredSystem;
import entity.Service;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import use_case.configure_monitored_system.ConfigurationLoadResult;
import use_case.configure_monitored_system.ConfigurationRepository;
import use_case.configure_monitored_system.ConfigurationStoreException;
import use_case.investigate_incident.FailureInput;
import use_case.investigate_incident.FailureProcessingResult;
import use_case.investigate_incident.IncidentInputBoundary;
import use_case.investigate_incident.IncidentOutputBoundary;
import use_case.monitor_service_health.FailureEventListener;
import use_case.monitor_service_health.ObservationStore;
import use_case.monitor_service_health.ObservationStoreException;

/**
 * Adapts a UC2 threshold crossing to a system-scoped UC3 investigation. Returns false, so UC2 redelivers,
 * whenever the owning system or its observations cannot be read reliably.
 */
public final class IncidentFailureConnector implements FailureEventListener {
    private final ConfigurationRepository configurations;
    private final ObservationStore observations;
    private final IncidentInputBoundary incidents;
    private final IncidentOutputBoundary output;
    private final Clock clock;
    private final Duration evidenceWindow;

    public IncidentFailureConnector(ConfigurationRepository configurations, ObservationStore observations,
                                    IncidentInputBoundary incidents, IncidentOutputBoundary output, Clock clock,
                                    Duration evidenceWindow) {
        this.configurations = Objects.requireNonNull(configurations, "configurations");
        this.observations = Objects.requireNonNull(observations, "observations");
        this.incidents = Objects.requireNonNull(incidents, "incidents");
        this.output = Objects.requireNonNull(output, "output");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.evidenceWindow = Objects.requireNonNull(evidenceWindow, "evidenceWindow");
        if (evidenceWindow.isNegative() || evidenceWindow.isZero()) {
            throw new IllegalArgumentException("Evidence window must be positive");
        }
    }

    @Override
    public boolean deliver(FailureEvent event) {
        FailureInput input;
        try {
            ConfigurationLoadResult loaded = configurations.findAll();
            List<MonitoredSystem> owners = loaded.getSystems().stream()
                    .filter(system -> system.getServices().stream()
                            .anyMatch(service -> service.getId().equals(event.getServiceId())))
                    .collect(Collectors.toList());
            if (owners.isEmpty()) {
                // Only a complete roster can confirm that a service has been removed.
                return loaded.getSkippedFiles().isEmpty();
            }
            if (owners.size() > 1) {
                // Redelivery cannot resolve a configuration conflict, so acknowledge and report it instead.
                output.presentError("Failure of service " + event.getServiceId() + " was not investigated because "
                        + "the service is configured in more than one system: " + owners.stream()
                        .map(MonitoredSystem::getName).sorted().collect(Collectors.joining(", "))
                        + ". Remove the duplicate service so its future failures can be investigated.");
                return true;
            }
            MonitoredSystem system = owners.get(0);
            input = new FailureInput(system.getId(), event, recentObservations(system));
        } catch (ConfigurationStoreException | ObservationStoreException exception) {
            return false;
        }
        FailureProcessingResult result = incidents.processFailure(input);
        return result == FailureProcessingResult.PROCESSED || result == FailureProcessingResult.REJECTED;
    }

    private List<HealthCheckResult> recentObservations(MonitoredSystem system) {
        Instant now = clock.instant();
        Instant earliest = now.minus(evidenceWindow);
        List<HealthCheckResult> recent = new ArrayList<>();
        for (Service service : system.getServices()) {
            for (HealthCheckResult result : observations.findByServiceId(service.getId())) {
                if (!result.getTimestamp().isBefore(earliest) && !result.getTimestamp().isAfter(now)) {
                    recent.add(result);
                }
            }
        }
        return recent;
    }
}
