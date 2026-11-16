package entity;

import java.net.URI;
import java.util.Objects;
import java.util.Optional;

/** A service has one configured monitoring target. */
public final class MonitoringTarget {
    public enum Method { HTTP, DOCKER }

    private final Method method;
    private final URI endpoint;
    private final String containerId;

    private MonitoringTarget(Method method, URI endpoint, String containerId) {
        this.method = method;
        this.endpoint = endpoint;
        this.containerId = containerId;
    }

    public static MonitoringTarget http(URI endpoint) {
        Objects.requireNonNull(endpoint, "endpoint");
        String scheme = endpoint.getScheme();
        if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                || endpoint.getHost() == null || endpoint.getFragment() != null
                || endpoint.getRawUserInfo() != null || endpoint.getPort() > 65535
                || endpoint.getPort() == 0) {
            throw new IllegalArgumentException("Expected a valid HTTP or HTTPS endpoint");
        }
        return new MonitoringTarget(Method.HTTP, endpoint, null);
    }

    public static MonitoringTarget docker(String containerId) {
        Objects.requireNonNull(containerId, "containerId");
        if (containerId.isBlank()) {
            throw new IllegalArgumentException("Container ID must not be blank");
        }
        return new MonitoringTarget(Method.DOCKER, null, containerId.strip());
    }

    public Method getMethod() {
        return method;
    }

    public Optional<URI> getEndpoint() {
        return Optional.ofNullable(endpoint);
    }

    public Optional<String> getContainerId() {
        return Optional.ofNullable(containerId);
    }
}
