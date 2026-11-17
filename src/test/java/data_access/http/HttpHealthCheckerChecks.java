package data_access.http;

import com.sun.net.httpserver.HttpServer;
import entity.HealthCheckResult;
import entity.MonitoringPolicy;
import entity.MonitoringTarget;
import entity.Service;

import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.UUID;

public final class HttpHealthCheckerChecks {

    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0), 0
        );

        server.createContext("/healthy", exchange -> {
            try {
                exchange.sendResponseHeaders(200, -1);
            } finally {
                exchange.close();
            }
        });

        server.createContext("/unhealthy", exchange -> {
            try {
                exchange.sendResponseHeaders(503, -1);
            } finally {
                exchange.close();
            }
        });

        server.createContext("/slow", exchange -> {
            try {
                Thread.sleep(500);
                exchange.sendResponseHeaders(200, -1);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });

        server.start();

        try {
            int port = server.getAddress().getPort();
            HttpHealthChecker checker = new HttpHealthChecker();

            Service healthy = service(port, "/healthy");
            HealthCheckResult passed = checker.check(healthy);
            require(passed.getOutcome() == HealthCheckResult.Outcome.PASS,
                    "HTTP 200 should produce PASS");
            require(passed.getServiceId().equals(healthy.getId()),
                    "The result should identify the checked service");
            require(passed.getResponseTimeMs().isPresent(),
                    "An HTTP response should include elapsed time");

            HealthCheckResult failed = checker.check(
                    service(port, "/unhealthy")
            );
            require(failed.getOutcome() == HealthCheckResult.Outcome.FAIL,
                    "HTTP 503 should produce FAIL");
            HealthCheckResult timedOut = checker.check(
                    service(port, "/slow", Duration.ofMillis(100))
            );

            require(timedOut.getOutcome()
                            == HealthCheckResult.Outcome.MONITORING_ERROR,
                    "A timeout should produce MONITORING_ERROR");

            System.out.println("HttpHealthCheckerChecks passed");
        } finally {
            server.stop(0);
        }
    }

    private static Service service(int port, String path) {
        return service(port, path, Duration.ofSeconds(2));
    }

    private static Service service(int port, String path, Duration timeout) {
        return new Service(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "Test service",
                MonitoringTarget.http(
                        URI.create("http://127.0.0.1:" + port + path)
                ),
                new MonitoringPolicy(
                        Duration.ofSeconds(10),
                        timeout,
                        3,
                        2
                )
        );
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}