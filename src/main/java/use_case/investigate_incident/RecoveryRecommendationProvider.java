package use_case.investigate_incident;

import entity.Incident;
import entity.MonitoringTarget;
import entity.RecoveryAction;
import entity.Service;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Recommends which containers to recover for an unresolved incident, without I/O or Docker state.
 * Targets are the most upstream failing services: a failing service is a target unless it requires
 * another failing service outside its own dependency cycle. Only Docker targets receive an action;
 * HTTP targets and incidents without dependency information rely on the findings' manual next steps.
 * <p>
 * Recorded failures stay in the incident after a service recovers, so a caller with current health can name
 * the failing services that have recovered. A recovered service no longer holds back the services that require
 * it, so the next service still failing downstream becomes a target. The recovered service itself stays in the
 * recommendations; deciding whether to act on it now is the recovery use case's job.
 */
public final class RecoveryRecommendationProvider {

    /** Recommendations from recorded failures alone, for callers without current health. */
    public List<RecoveryAction> recommend(Incident incident, ConfigurationSnapshot configuration) {
        return recommend(incident, configuration, Set.of());
    }

    /**
     * @param recoveredServiceIds failing services whose checks since their latest recorded failure already meet
     *                            their recovery threshold; IDs of other services are ignored
     */
    public List<RecoveryAction> recommend(Incident incident, ConfigurationSnapshot configuration,
                                          Set<UUID> recoveredServiceIds) {
        Objects.requireNonNull(recoveredServiceIds, "recoveredServiceIds");
        if (incident.getStatus() == Incident.Status.RESOLVED
                || !incident.getSystemId().equals(configuration.getSystem().getId())
                || configuration.getDependencyUnavailableReason().isPresent()) {
            return List.of();
        }
        Map<UUID, Service> services = configuration.getSystem().getServices().stream()
                .collect(Collectors.toMap(Service::getId, Function.identity()));
        DependencyGraph graph = new DependencyGraph(configuration.getSystem().getDependencies());
        TreeSet<UUID> failing = new TreeSet<>(incident.getFailingServiceIds());
        // Only services that are still failing can hold back the services that require them.
        TreeSet<UUID> stillFailing = new TreeSet<>(failing);
        stillFailing.removeAll(recoveredServiceIds);

        List<RecoveryAction> actions = new ArrayList<>();
        for (UUID candidate : failing) {
            boolean downstream = false;
            boolean inCycle = false;
            for (UUID other : stillFailing) {
                if (graph.path(candidate, other).isPresent()) {
                    if (graph.path(other, candidate).isPresent()) {
                        inCycle = true;
                    } else {
                        downstream = true;
                    }
                }
            }
            Service service = services.get(candidate);
            if (downstream || service == null
                    || service.getMonitoringTarget().getMethod() != MonitoringTarget.Method.DOCKER) {
                continue;
            }
            boolean afterRecoveredDependency = !recoveredServiceIds.contains(candidate) && failing.stream()
                    .anyMatch(other -> recoveredServiceIds.contains(other) && graph.path(candidate, other).isPresent());
            actions.add(new RecoveryAction(actionId(incident.getId(), candidate), incident.getId(), candidate,
                    RecoveryAction.ActionType.RECOVER_DOCKER_CONTAINER,
                    description(service, inCycle, afterRecoveredDependency)));
        }
        return List.copyOf(actions);
    }

    private static String description(Service service, boolean inCycle, boolean afterRecoveredDependency) {
        String reason;
        if (inCycle) {
            reason = "It fails within a dependency cycle of failing services, so no single upstream service can be identified.";
        } else if (afterRecoveredDependency) {
            reason = "The failing services that it requires have recovered, so it is the most upstream service still failing.";
        } else {
            reason = "No failing service that it requires was recorded, so it is the most upstream failing service.";
        }
        return "Recover container " + service.getMonitoringTarget().getContainerId().orElseThrow()
                + " for " + service.getName() + ". " + reason
                + " This is a suspected cause, not a confirmed one; the operation is chosen from the container's state.";
    }

    /** Stable per incident and target, so reopening an incident yields the same recommendation ID. */
    private static UUID actionId(UUID incidentId, UUID serviceId) {
        String key = "recover-docker-container:" + incidentId + ":" + serviceId;
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
    }
}
