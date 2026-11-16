package entity;

import java.util.Objects;
import java.util.UUID;

public final class Dependency {
    private final UUID id;
    private final UUID dependentServiceId;
    private final UUID requiredServiceId;

    public Dependency(UUID id, UUID dependentServiceId, UUID requiredServiceId) {
        this.id = Objects.requireNonNull(id, "id");
        this.dependentServiceId = Objects.requireNonNull(dependentServiceId, "dependentServiceId");
        this.requiredServiceId = Objects.requireNonNull(requiredServiceId, "requiredServiceId");
        if (dependentServiceId.equals(requiredServiceId)) {
            throw new IllegalArgumentException("A service cannot depend on itself");
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getDependentServiceId() {
        return dependentServiceId;
    }

    public UUID getRequiredServiceId() {
        return requiredServiceId;
    }
}
