package use_case.monitor_service_health;

import entity.HealthCheckResult;
import entity.HealthStatus;
import entity.MonitoringPolicy;
import entity.MonitoringTarget;
import entity.Service;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;

import static entity.HealthCheckResult.Outcome.FAIL;
import static entity.HealthCheckResult.Outcome.MONITORING_ERROR;
import static entity.HealthCheckResult.Outcome.PASS;

public final class HealthStateEvaluatorChecks {

    private static final HealthStateEvaluator EVALUATOR =
            new HealthStateEvaluator();

    private static final Service SERVICE = new Service(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "Test service",
            MonitoringTarget.http(URI.create("http://localhost/health")),
            new MonitoringPolicy(
                    Duration.ofSeconds(10),
                    Duration.ofSeconds(2),
                    3,
                    2
            )
    );

    public static void main(String[] args) {
        checksThresholdsAndEvents();
        checksRecoveryAndErrors();
        checksMonitoringErrorEventCount();

        System.out.println("HealthStateEvaluatorChecks passed");
    }

    private static void checksThresholdsAndEvents() {
        expectStatus(HealthStatus.UNKNOWN);
        expectStatus(HealthStatus.UNKNOWN, PASS);
        expectStatus(HealthStatus.HEALTHY, PASS, PASS);

        expectStatus(HealthStatus.UNKNOWN, FAIL, FAIL);
        expectStatus(HealthStatus.HEALTHY, PASS, PASS, FAIL, FAIL);

        List<HealthCheckResult> failures = history(FAIL, FAIL, FAIL);
        HealthEvaluation crossed = EVALUATOR.evaluate(SERVICE, failures);

        require(crossed.getHealth().getStatus() == HealthStatus.UNHEALTHY,
                "Three consecutive failures should make the service unhealthy");
        require(crossed.getFailureEvent().isPresent(),
                "Crossing the failure threshold should produce an event");
        require(crossed.getFailureEvent().orElseThrow().getEvidence()
                        .equals(failures),
                "The event should retain the failed checks as evidence");

        HealthEvaluation replayed = EVALUATOR.evaluate(SERVICE, failures);

        require(crossed.getFailureEvent().orElseThrow().getId().equals(
                        replayed.getFailureEvent().orElseThrow().getId()),
                "Reevaluating the same crossing should preserve the event ID");

        HealthEvaluation continuing = evaluate(FAIL, FAIL, FAIL, FAIL);

        require(continuing.getHealth().getStatus() == HealthStatus.UNHEALTHY,
                "Continued failures should keep the service unhealthy");
        require(continuing.getFailureEvent().isPresent(),
                "The current unhealthy episode should retain its event");

        require(continuing.getFailureEvents().size() == 1,
                "Continued failures should retain exactly one historical event");
    }

    private static void checksRecoveryAndErrors() {
        expectStatus(HealthStatus.UNHEALTHY, FAIL, FAIL, FAIL, PASS);
        expectStatus(HealthStatus.HEALTHY, FAIL, FAIL, FAIL, PASS, PASS);

        expectStatus(
                HealthStatus.UNHEALTHY,
                FAIL, FAIL, FAIL, PASS, FAIL, PASS
        );

        expectStatus(
                HealthStatus.UNKNOWN,
                FAIL, FAIL, MONITORING_ERROR, FAIL, FAIL
        );

        expectStatus(
                HealthStatus.UNKNOWN,
                PASS, PASS, MONITORING_ERROR
        );

        expectStatus(
                HealthStatus.HEALTHY,
                MONITORING_ERROR, PASS, PASS
        );

        HealthEvaluation failedAgain = evaluate(
                FAIL, FAIL, FAIL, PASS, PASS, FAIL, FAIL, FAIL
        );

        require(failedAgain.getHealth().getStatus() == HealthStatus.UNHEALTHY,
                "A recovered service can become unhealthy again");
        require(failedAgain.getFailureEvent().isPresent(),
                "A new unhealthy transition should produce an event");

        HealthEvaluation interruptedFailures = evaluate(
                FAIL, FAIL, PASS, FAIL, FAIL
        );

        require(interruptedFailures.getHealth().getStatus() == HealthStatus.UNKNOWN,
                "A pass should reset the failure streak");
        require(interruptedFailures.getFailureEvent().isEmpty(),
                "Interrupted failures should not reach the threshold");
    }

    private static void expectStatus(
            HealthStatus expected,
            HealthCheckResult.Outcome... outcomes
    ) {
        HealthStatus actual = evaluate(outcomes).getHealth().getStatus();

        require(actual == expected,
                "Expected " + expected + " but received " + actual);
    }

    private static HealthEvaluation evaluate(
            HealthCheckResult.Outcome... outcomes
    ) {
        return EVALUATOR.evaluate(SERVICE, history(outcomes));
    }

    private static List<HealthCheckResult> history(
            HealthCheckResult.Outcome... outcomes
    ) {
        List<HealthCheckResult> results = new ArrayList<>();
        Instant start = Instant.parse("2026-11-17T12:00:00Z");

        for (int index = 0; index < outcomes.length; index++) {
            results.add(new HealthCheckResult(
                    UUID.randomUUID(),
                    SERVICE.getId(),
                    start.plusSeconds(index),
                    outcomes[index],
                    OptionalLong.empty(),
                    "Test observation"
            ));
        }

        return results;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void checksMonitoringErrorEventCount() {
        Service service = new Service(
                SERVICE.getId(),
                SERVICE.getSystemId(),
                "Threshold-two service",
                SERVICE.getMonitoringTarget(),
                new MonitoringPolicy(
                        Duration.ofSeconds(10),
                        Duration.ofSeconds(2),
                        2,
                        2
                )
        );

        List<HealthCheckResult> observations = history(
                FAIL, FAIL, MONITORING_ERROR, FAIL, FAIL
        );

        HealthEvaluation evaluation = EVALUATOR.evaluate(service, observations);

        require(evaluation.getFailureEvents().size() == 2,
                "A monitoring error should separate two threshold crossings");

        var first = evaluation.getFailureEvents().get(0);
        var second = evaluation.getFailureEvents().get(1);

        require(!first.getId().equals(second.getId()),
                "Separate failure episodes should have distinct event IDs");

        require(first.getEvidence().equals(observations.subList(0, 2)),
                "The first event should retain the checks before the error");

        require(second.getEvidence().equals(observations.subList(3, 5)),
                "The second event should retain the checks after the error");

        require(evaluation.getHealth().getStatus() == HealthStatus.UNHEALTHY,
                "The final failure streak should make the service unhealthy");
    }
}