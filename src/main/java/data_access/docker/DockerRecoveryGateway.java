package data_access.docker;

import com.github.dockerjava.transport.DockerHttpClient;
import use_case.recover_service.ContainerAccessException;
import use_case.recover_service.ContainerOperator;
import use_case.recover_service.ContainerState;
import use_case.recover_service.ContainerStateReader;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Objects;

import static data_access.docker.DockerEngineInspector.containerPath;
import static data_access.docker.DockerEngineInspector.requirePermission;
import static use_case.recover_service.ContainerAccessException.Kind.FAILED;
import static use_case.recover_service.ContainerAccessException.Kind.PERMISSION_DENIED;
import static use_case.recover_service.ContainerAccessException.Kind.UNAVAILABLE;

/** Adapts PR #13's Engine client to the recovery ports. */
public final class DockerRecoveryGateway implements ContainerStateReader, ContainerOperator {
    private final DockerInspector inspector;
    private final DockerEngineInspector.TransportFactory transports;
    private final Duration timeout;

    public DockerRecoveryGateway(DockerEngineInspector inspector, Duration timeout) {
        this(inspector, DockerEngineInspector::defaultTransport, timeout);
    }

    DockerRecoveryGateway(DockerInspector inspector, DockerEngineInspector.TransportFactory transports,
                          Duration timeout) {
        this.inspector = Objects.requireNonNull(inspector, "inspector");
        this.transports = Objects.requireNonNull(transports, "transports");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("Docker recovery timeout must be positive");
        }
    }

    @Override
    public ContainerState inspect(String containerId) {
        try {
            DockerInspection result = inspector.inspect(containerId, timeout);
            return new ContainerState(ContainerState.Status.valueOf(result.getState().name()),
                    result.isOomKilled(), result.getContainerId().orElse(null));
        } catch (DockerEngineInspector.DockerPermissionException exception) {
            throw new ContainerAccessException(PERMISSION_DENIED, exception.getMessage(), exception);
        } catch (IOException exception) {
            throw new ContainerAccessException(UNAVAILABLE, exception.getMessage(), exception);
        }
    }

    @Override
    public void start(String containerId) {
        operate(containerId, "start");
    }

    @Override
    public void unpause(String containerId) {
        operate(containerId, "unpause");
    }

    @Override
    public void restart(String containerId) {
        operate(containerId, "restart");
    }

    private void operate(String containerId, String operation) {
        DockerHttpClient.Request request = DockerHttpClient.Request.builder()
                .method(DockerHttpClient.Request.Method.POST)
                .path(containerPath(containerId, operation))
                // A streamed entity prevents Apache HttpClient from replaying the POST on retryable responses.
                .body(InputStream.nullInputStream())
                .build();
        try (DockerHttpClient client = transports.open(timeout);
             DockerHttpClient.Response response = client.execute(request)) {
            int status = response.getStatusCode();
            requirePermission(status);
            if (status == 204 || status == 304 && operation.equals("start")) {
                return;
            }
            String detail = status == 404 ? "Container was not found" : "Docker Engine rejected " + operation;
            throw new ContainerAccessException(FAILED, detail + " (HTTP " + status + ")");
        } catch (DockerEngineInspector.DockerPermissionException exception) {
            throw new ContainerAccessException(PERMISSION_DENIED, exception.getMessage(), exception);
        } catch (ContainerAccessException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new ContainerAccessException(UNAVAILABLE,
                    "The result is uncertain because Docker did not complete the request: " + exception.getMessage(),
                    exception);
        }
    }
}
