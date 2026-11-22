package use_case.investigate_incident;

import entity.Incident;
import entity.MonitoringTarget;
import entity.RecoveryAction;
import entity.Service;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Recommends which containers to recover for an unresolved incident, without I/O or Docker state.
 * Targets are the most upstream failing services: a failing service is a target unless it requires
 * another failing service outside its own dependency cycle. Only Docker targets receive an action;
 * HTTP targets and incidents without dependency information rely on the findings' manual next steps.
 */
public final class RecoveryRecommendationProvider {

    public List<RecoveryAction> recommend(Incident incident, ConfigurationSnapshot configuration) {
        if (incident.getStatus() == Incident.Status.RESOLVED
                || !incident.getSystemId().equals(configuration.getSystem().getId())
                || configuration.getDependencyUnavailableReason().isPresent()) {
            return List.of();
        }
        Map<UUID, Service> services = configuration.getSystem().getServices().stream()
                .collect(Collectors.toMap(Service::getId, Function.identity()));
        DependencyGraph graph = new DependencyGraph(configuration.getSystem().getDependencies());
        TreeSet<UUID> failing = new TreeSet<>(incident.getFailingServiceIds());

        List<RecoveryAction> actions = new ArrayList<>();
        for (UUID candidate : failing) {
            boolean downstream = false;
            boolean inCycle = false;
            for (UUID other : failing) {
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
            actions.add(new RecoveryAction(actionId(incident.getId(), candidate), incident.getId(), candidate,
                    RecoveryAction.ActionType.RECOVER_DOCKER_CONTAINER, description(service, inCycle)));
        }
        return List.copyOf(actions);
    }

    private static String description(Service service, boolean inCycle) {
        String reason = inCycle
                ? "It fails within a dependency cycle of failing services, so no single upstream service can be identified."
                : "No failing service that it requires was recorded, so it is the most upstream failing service.";
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
