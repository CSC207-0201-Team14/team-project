package data_access.http;

import entity.HealthCheckResult;
import entity.MonitoringTarget;
import entity.Service;
import use_case.monitor_service_health.HealthChecker;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public final class HttpHealthChecker implements HealthChecker {

    private final HttpClient client = HttpClient.newHttpClient();

    @Override
    public HealthCheckResult check(Service service) {
        Objects.requireNonNull(service, "service");

        if (service.getMonitoringTarget().getMethod()
                != MonitoringTarget.Method.HTTP) {
            return monitoringError(service, "Expected an HTTP monitoring target");
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(service.getMonitoringTarget().getEndpoint().orElseThrow())
                .timeout(service.getMonitoringPolicy().getTimeout())
                .GET()
                .build();

        long startedAt = System.nanoTime();

        try {
            HttpResponse<Void> response = client.send(
                    request,
                    HttpResponse.BodyHandlers.discarding()
            );

            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(
                    System.nanoTime() - startedAt
            );

            int statusCode = response.statusCode();
            HealthCheckResult.Outcome outcome;

            if (statusCode >= 200 && statusCode < 300) {
                outcome = HealthCheckResult.Outcome.PASS;
            } else {
                outcome = HealthCheckResult.Outcome.FAIL;
            }

            return new HealthCheckResult(
                    UUID.randomUUID(),
                    service.getId(),
                    Instant.now(),
                    outcome,
                    OptionalLong.of(elapsedMs),
                    "HTTP " + statusCode
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return monitoringError(service, "HTTP check was interrupted");
        } catch (IOException exception) {
            return monitoringError(
                    service,
                    "HTTP check could not complete: " + exception.getMessage()
            );
        }
    }

    private HealthCheckResult monitoringError(Service service, String message) {
        return new HealthCheckResult(
                UUID.randomUUID(),
                service.getId(),
                Instant.now(),
                HealthCheckResult.Outcome.MONITORING_ERROR,
                OptionalLong.empty(),
                message
        );
    }
}