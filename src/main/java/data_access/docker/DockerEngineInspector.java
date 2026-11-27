package data_access.docker;

import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;
import com.google.gson.JsonObject;
import data_access.persistence.JsonCodec;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;

/**
 * Reads GET /containers/{id-or-name}/json using Docker Engine's HTTP API. The transport supports Windows
 * named pipes and Unix sockets, plus explicitly configured TCP/TLS hosts. Configuration and connections
 * are created lazily during the check, off the EDT. No CLI process or container mutation is used.
 */
public final class DockerEngineInspector implements DockerInspector {
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    private final TransportFactory transports;

    public DockerEngineInspector() {
        this(DockerEngineInspector::defaultTransport);
    }

    /** Package seam: adapter tests use the real transport against a local, simulated Engine endpoint. */
    DockerEngineInspector(TransportFactory transports) {
        this.transports = Objects.requireNonNull(transports, "transports");
    }

    @Override
    public DockerInspection inspect(String containerId, Duration timeout) throws IOException {
        Objects.requireNonNull(containerId, "containerId");
        Objects.requireNonNull(timeout, "timeout");
        if (containerId.isBlank() || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("Container ID and positive timeout are required");
        }
        // Unversioned Engine requests use the daemon's current API. Decode only stable inspect fields.
        DockerHttpClient.Request request = DockerHttpClient.Request.builder()
                .method(DockerHttpClient.Request.Method.GET)
                .path(containerPath(containerId, "json"))
                .build();
        try (DockerHttpClient client = transports.open(timeout);
             DockerHttpClient.Response response = client.execute(request)) {
            int status = response.getStatusCode();
            if (status == 404) {
                return DockerInspection.missing();
            }
            requirePermission(status);
            if (status != 200) {
                throw new IOException("Docker Engine could not inspect the container (HTTP " + status + ")");
            }
            byte[] body = response.getBody().readNBytes(MAX_RESPONSE_BYTES + 1);
            if (body.length > MAX_RESPONSE_BYTES) {
                throw new IOException("Docker inspect response exceeded the supported size");
            }
            try (InputStreamReader reader = new InputStreamReader(new ByteArrayInputStream(body),
                    StandardCharsets.UTF_8)) {
                return decode(JsonCodec.readObject(reader));
            }
        } catch (RuntimeException exception) {
            throw new IOException("Docker connection configuration or inspect response was invalid: "
                    + exception.getMessage(), exception);
        }
    }

    private static DockerInspection decode(JsonObject json) {
        String containerId = JsonCodec.readString(json, "Id");
        if (containerId.isBlank()) {
            throw new IllegalArgumentException("Missing container identity in inspect response");
        }
        JsonObject state = json.getAsJsonObject("State");
        DockerInspection.State lifecycle = DockerInspection.State.valueOf(
                JsonCodec.readString(state, "Status").toUpperCase(Locale.ROOT));
        if (lifecycle == DockerInspection.State.MISSING) {
            throw new IllegalArgumentException("An existing container cannot report a missing state");
        }
        if (!state.has("OOMKilled") || !state.get("OOMKilled").isJsonPrimitive()
                || !state.getAsJsonPrimitive("OOMKilled").isBoolean()) {
            throw new IllegalArgumentException("Expected a boolean OOMKilled field");
        }
        DockerInspection.Health health = DockerInspection.Health.NONE;
        if (state.has("Health") && !state.get("Health").isJsonNull()) {
            health = DockerInspection.Health.valueOf(JsonCodec.readString(state.getAsJsonObject("Health"), "Status")
                    .toUpperCase(Locale.ROOT));
        }
        return new DockerInspection(lifecycle, health, state.get("OOMKilled").getAsBoolean(), containerId);
    }

    static String containerPath(String containerId, String operation) {
        String encodedId = URLEncoder.encode(containerId, StandardCharsets.UTF_8).replace("+", "%20");
        return "/containers/" + encodedId + "/" + operation;
    }

    static void requirePermission(int status) throws DockerPermissionException {
        if (status == 401 || status == 403) {
            throw new DockerPermissionException(status);
        }
    }

    static final class DockerPermissionException extends IOException {
        private DockerPermissionException(int status) {
            super("Docker Engine denied permission (HTTP " + status + ")");
        }
    }

    static DockerHttpClient defaultTransport(Duration timeout) {
        DefaultDockerClientConfig.Builder builder = DefaultDockerClientConfig.createDefaultConfigBuilder();
        String override = System.getProperty("sentinel.docker.host");
        if (override != null && !override.isBlank()) {
            builder.withDockerHost(override);
        }
        DockerClientConfig config = builder.build();
        // HttpClient expresses timeouts in milliseconds; rounding down to zero could mean no timeout.
        Duration bounded = timeout.compareTo(Duration.ofMillis(1)) < 0 ? Duration.ofMillis(1) : timeout;
        return new ApacheDockerHttpClient.Builder()
                .dockerHost(config.getDockerHost())
                .sslConfig(config.getSSLConfig())
                .maxConnections(1)
                .connectionTimeout(bounded)
                .responseTimeout(bounded)
                .build();
    }

    @FunctionalInterface
    interface TransportFactory {
        DockerHttpClient open(Duration timeout) throws IOException;
    }
}
