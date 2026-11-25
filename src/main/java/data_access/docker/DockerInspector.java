package data_access.docker;

import java.io.IOException;
import java.time.Duration;

/** Outer-layer seam for an Engine transport or a fake used by checker tests. Read-only. */
@FunctionalInterface
public interface DockerInspector {
    DockerInspection inspect(String containerId, Duration timeout) throws IOException;
}
