package use_case.configure_monitored_system;

import entity.Dependency;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** A complete configuration draft. Saving replaces the selected system's configuration. */
public final class ConfigurationInput {
    final UUID systemId;
    final String name;
    final String description;
    final List<ServiceInput> services;
    final List<Dependency> dependencies;

    public ConfigurationInput(UUID systemId, String name, String description, List<ServiceInput> services,
                              List<Dependency> dependencies) {
        this.systemId = Objects.requireNonNull(systemId, "systemId");
        this.name = Objects.requireNonNull(name, "name");
        this.description = Objects.requireNonNull(description, "description");
        this.services = List.copyOf(services);
        this.dependencies = List.copyOf(dependencies);
    }
}
