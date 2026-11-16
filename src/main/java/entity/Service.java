package entity;

import java.util.Objects;
import java.util.UUID;

public final class Service {
    private final UUID id;
    private final UUID systemId;
    private final String name;
    private final MonitoringTarget monitoringTarget;
    private final MonitoringPolicy monitoringPolicy;

    public Service(UUID id, UUID systemId, String name, MonitoringTarget monitoringTarget,
                   MonitoringPolicy monitoringPolicy) {
        this.id = Objects.requireNonNull(id, "id");
        this.systemId = Objects.requireNonNull(systemId, "systemId");
        this.name = Objects.requireNonNull(name, "name").strip();
        if (this.name.isEmpty()) {
            throw new IllegalArgumentException("Service name must not be blank");
        }
        this.monitoringTarget = Objects.requireNonNull(monitoringTarget, "monitoringTarget");
        this.monitoringPolicy = Objects.requireNonNull(monitoringPolicy, "monitoringPolicy");
    }

    public UUID getId() {
        return id;
    }

    public UUID getSystemId() {
        return systemId;
    }

    public String getName() {
        return name;
    }

    public MonitoringTarget getMonitoringTarget() {
        return monitoringTarget;
    }

    public MonitoringPolicy getMonitoringPolicy() {
        return monitoringPolicy;
    }
}
