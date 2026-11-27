package data_access.docker;

import java.util.Objects;

/** The subset of an Engine inspect response needed by monitoring; no Docker SDK types cross this seam. */
public final class DockerInspection {
    public enum State { CREATED, RUNNING, PAUSED, RESTARTING, EXITED, DEAD, REMOVING, MISSING }
    public enum Health { NONE, STARTING, HEALTHY, UNHEALTHY }

    private final State state;
    private final Health health;
    private final boolean oomKilled;

    public DockerInspection(State state, Health health, boolean oomKilled) {
        this.state = Objects.requireNonNull(state, "state");
        this.health = Objects.requireNonNull(health, "health");
        this.oomKilled = oomKilled;
    }

    public static DockerInspection missing() {
        return new DockerInspection(State.MISSING, Health.NONE, false);
    }

    public State getState() { return state; }
    public Health getHealth() { return health; }
    public boolean isOomKilled() { return oomKilled; }
}
