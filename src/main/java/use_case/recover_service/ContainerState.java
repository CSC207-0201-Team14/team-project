package use_case.recover_service;

import java.util.Locale;
import java.util.Objects;

/** Container state reported by the container gateway, translated from the external API. */
public final class ContainerState {
    public enum Status { CREATED, RUNNING, PAUSED, RESTARTING, EXITED, DEAD, REMOVING, MISSING }

    private final Status status;
    private final boolean oomKilled;

    public ContainerState(Status status, boolean oomKilled) {
        this.status = Objects.requireNonNull(status, "status");
        this.oomKilled = oomKilled;
    }

    /** The container ID does not refer to an existing container. */
    public static ContainerState missing() {
        return new ContainerState(Status.MISSING, false);
    }

    public Status getStatus() {
        return status;
    }

    /** True when the container was last stopped for exceeding its memory limit. */
    public boolean isOomKilled() {
        return oomKilled;
    }

    @Override
    public String toString() {
        String name = status.name().toLowerCase(Locale.ROOT);
        return oomKilled ? name + " (killed for exceeding memory)" : name;
    }
}
