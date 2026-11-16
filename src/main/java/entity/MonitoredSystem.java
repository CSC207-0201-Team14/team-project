package entity;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class MonitoredSystem {
    private final UUID id;
    private final String name;
    private final String description;
    private final List<Service> services;
    private final List<Dependency> dependencies;

    public MonitoredSystem(UUID id, String name, String description,
                           List<Service> services, List<Dependency> dependencies) {
        this.id = Objects.requireNonNull(id, "id");
        this.name = Objects.requireNonNull(name, "name").strip();
        if (this.name.isEmpty()) {
            throw new IllegalArgumentException("System name must not be blank");
        }
        this.description = Objects.requireNonNull(description, "description");
        this.services = List.copyOf(services);
        this.dependencies = List.copyOf(dependencies);
        validateGraph();
    }

    private void validateGraph() {
        Set<UUID> serviceIds = new HashSet<>();
        Set<String> serviceNames = new HashSet<>();
        for (Service service : services) {
            if (!id.equals(service.getSystemId())) {
                throw new IllegalArgumentException("Service must belong to the monitored system");
            }
            if (!serviceIds.add(service.getId()) || !serviceNames.add(service.getName().toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("Service IDs and names must be unique within a system");
            }
        }

        Set<UUID> dependencyIds = new HashSet<>();
        Set<List<UUID>> edges = new HashSet<>();
        for (Dependency dependency : dependencies) {
            UUID dependent = dependency.getDependentServiceId();
            UUID required = dependency.getRequiredServiceId();
            if (!serviceIds.contains(dependent) || !serviceIds.contains(required)) {
                throw new IllegalArgumentException("Both dependency endpoints must belong to the system");
            }
            if (!dependencyIds.add(dependency.getId()) || !edges.add(List.of(dependent, required))) {
                throw new IllegalArgumentException("Dependency IDs and directed edges must be unique");
            }
        }
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public List<Service> getServices() {
        return services;
    }

    public List<Dependency> getDependencies() {
        return dependencies;
    }
}
