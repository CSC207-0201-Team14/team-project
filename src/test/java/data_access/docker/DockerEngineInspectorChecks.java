package data_access.docker;

import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Real Docker HTTP transport against a local simulated API. No daemon or real container IDs required. */
public final class DockerEngineInspectorChecks {
    private static final Duration TIMEOUT = Duration.ofSeconds(2);
    private static final String HEALTHY = "{\"Id\":\"fake-id\",\"State\":{\"Status\":\"running\","
            + "\"OOMKilled\":false,\"Health\":{\"Status\":\"healthy\"}}}";

    public static void main(String[] args) throws Exception {
        AtomicInteger status = new AtomicInteger(200);
        AtomicReference<String> body = new AtomicReference<>(HEALTHY);
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> method = new AtomicReference<>();
        AtomicInteger delayMs = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            path.set(exchange.getRequestURI().getRawPath());
            method.set(exchange.getRequestMethod());
            try {
                if (delayMs.get() > 0) {
                    try {
                        Thread.sleep(delayMs.get());
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                byte[] bytes = body.get().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status.get(), bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (IOException expectedWhenClientTimesOut) {
                // The timeout check closes its connection before the deliberately delayed response arrives.
            } finally {
                exchange.close();
            }
        });
        server.start();
        URI host = URI.create("tcp://127.0.0.1:" + server.getAddress().getPort());
        DockerEngineInspector inspector = new DockerEngineInspector(timeout -> new ApacheDockerHttpClient.Builder()
                .dockerHost(host).maxConnections(1).connectionTimeout(timeout).responseTimeout(timeout).build());
        try {
            DockerInspection inspection = inspector.inspect("test-db", TIMEOUT);
            require(inspection.getState() == DockerInspection.State.RUNNING
                            && inspection.getHealth() == DockerInspection.Health.HEALTHY
                            && !inspection.isOomKilled(), "Inspect data must be decoded correctly");
            require(path.get().equals("/containers/test-db/json") && method.get().equals("GET"),
                    "The adapter must use only the read-only Engine inspect endpoint");
            inspector.inspect("db/name ?", TIMEOUT);
            require(path.get().equals("/containers/db%2Fname%20%3F/json"), "Target text must stay inside one path segment");
            body.set("{\"Id\":\"fake-id\",\"State\":{\"Status\":\"paused\",\"OOMKilled\":false}}");
            require(inspector.inspect("db", TIMEOUT).getState() == DockerInspection.State.PAUSED,
                    "Status must be used; a paused container is not treated as running");
            body.set("{\"Id\":\"fake-id\",\"State\":{\"Status\":\"running\",\"OOMKilled\":false}}");
            require(inspector.inspect("db", TIMEOUT).getHealth() == DockerInspection.Health.NONE,
                    "The absent Health object means no HEALTHCHECK is configured");
            status.set(404);
            require(inspector.inspect("missing", TIMEOUT).getState() == DockerInspection.State.MISSING,
                    "HTTP 404 is a known missing container");
            for (int code : new int[]{401, 403, 500}) {
                status.set(code);
                expectReadFailure(() -> inspector.inspect("db", TIMEOUT), "HTTP " + code + " must be an access error");
            }
            status.set(200);
            for (String invalid : new String[]{"not JSON", "{}", HEALTHY.replace("healthy", "unexpected"),
                    HEALTHY.replace("false", "\"false\"")}) {
                body.set(invalid);
                expectReadFailure(() -> inspector.inspect("db", TIMEOUT), "Malformed state must not invent health");
            }
            body.set(HEALTHY);
            delayMs.set(500);
            long started = System.nanoTime();
            expectReadFailure(() -> inspector.inspect("slow", Duration.ofMillis(100)), "Slow responses must time out");
            require(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(3)) < 0,
                    "The configured timeout must prevent an unbounded wait");
        } finally {
            server.stop(0);
        }
        expectReadFailure(() -> inspector.inspect("db", Duration.ofMillis(200)),
                "An unreachable engine must be reported as an access failure");
        System.out.println("DockerEngineInspectorChecks passed");
    }

    private static void expectReadFailure(Read action, String message) throws Exception {
        try {
            action.run();
        } catch (IOException expected) {
            return;
        }
        throw new AssertionError(message);
    }

    @FunctionalInterface
    private interface Read { void run() throws Exception; }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
