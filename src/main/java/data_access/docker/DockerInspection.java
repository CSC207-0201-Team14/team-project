package data_access.docker;

import java.util.Objects;
import java.util.Optional;

/** The subset of an Engine inspect response needed by monitoring; no Docker SDK types cross this seam. */
public final class DockerInspection {
    public enum State { CREATED, RUNNING, PAUSED, RESTARTING, EXITED, DEAD, REMOVING, MISSING }
    public enum Health { NONE, STARTING, HEALTHY, UNHEALTHY }

    private final State state;
    private final Health health;
    private final boolean oomKilled;
    private final String containerId;

    public DockerInspection(State state, Health health, boolean oomKilled) {
        this(state, health, oomKilled, null);
    }

    DockerInspection(State state, Health health, boolean oomKilled, String containerId) {
        this.state = Objects.requireNonNull(state, "state");
        this.containerId = containerId;
        this.health = Objects.requireNonNull(health, "health");
        this.oomKilled = oomKilled;
    }

    public static DockerInspection missing() {
        return new DockerInspection(State.MISSING, Health.NONE, false);
    }

    Optional<String> getContainerId() { return Optional.ofNullable(containerId); }

    public State getState() { return state; }
    public Health getHealth() { return health; }
    public boolean isOomKilled() { return oomKilled; }
}
