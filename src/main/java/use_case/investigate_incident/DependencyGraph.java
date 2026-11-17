package use_case.investigate_incident;

import entity.Dependency;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;

/** Directed paths run from a dependent service to a required service. */
public final class DependencyGraph {
    private final List<Dependency> edges;

    public DependencyGraph(List<Dependency> edges) {
        List<Dependency> ordered = new ArrayList<>(edges);
        ordered.sort(Comparator.comparing(Dependency::getRequiredServiceId).thenComparing(Dependency::getId));
        this.edges = List.copyOf(ordered);
    }

    public Optional<List<Dependency>> path(UUID dependent, UUID required) {
        if (dependent.equals(required)) {
            return Optional.empty();
        }
        Queue<List<Dependency>> pending = new ArrayDeque<>();
        Set<UUID> visited = new HashSet<>();
        visited.add(dependent);
        for (Dependency edge : edges) {
            if (edge.getDependentServiceId().equals(dependent)) {
                pending.add(List.of(edge));
            }
        }
        while (!pending.isEmpty()) {
            List<Dependency> path = pending.remove();
            UUID target = path.get(path.size() - 1).getRequiredServiceId();
            if (target.equals(required)) {
                return Optional.of(List.copyOf(path));
            }
            if (visited.add(target)) {
                for (Dependency edge : edges) {
                    if (edge.getDependentServiceId().equals(target)) {
                        List<Dependency> extended = new ArrayList<>(path);
                        extended.add(edge);
                        pending.add(extended);
                    }
                }
            }
        }
        return Optional.empty();
    }
}
