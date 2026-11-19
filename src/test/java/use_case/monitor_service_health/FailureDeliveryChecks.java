package use_case.monitor_service_health;

import entity.FailureEvent;
import entity.HealthCheckResult;
import entity.MonitoringPolicy;
import entity.MonitoringTarget;
import entity.Service;
import entity.ServiceHealth;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

public final class FailureDeliveryChecks {

    public static void main(String[] args) {
        checksRetryAndConfirmation(false);
        checksRetryAndConfirmation(true);

        System.out.println("FailureDeliveryChecks passed");
    }

    private static void checksRetryAndConfirmation(
            boolean throwOnFirstDelivery
    ) {
        Service service = new Service(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "Test service",
                MonitoringTarget.http(URI.create("http://localhost/health")),
                new MonitoringPolicy(
                        Duration.ofSeconds(3),
                        Duration.ofSeconds(2),
                        2,
                        2
                )
        );

        AtomicInteger checkNumber = new AtomicInteger();
        Instant start = Instant.parse("2026-11-17T12:00:00Z");

        HealthChecker checker = requestedService -> new HealthCheckResult(
                UUID.randomUUID(),
                requestedService.getId(),
                start.plusSeconds(checkNumber.getAndIncrement()),
                HealthCheckResult.Outcome.FAIL,
                OptionalLong.empty(),
                "Test failure"
        );

        TestObservationStore store = new TestObservationStore();
        List<FailureEvent> deliveries = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        FailureEventListener listener = event -> {
            require(store.findByServiceId(service.getId())
                            .containsAll(event.getEvidence()),
                    "Failure evidence should be stored before delivery");

            deliveries.add(event);

            if (deliveries.size() == 1 && throwOnFirstDelivery) {
                throw new IllegalStateException("Temporary delivery failure");
            }

            return deliveries.size() >= 2;
        };

        MonitoringOutputBoundary output = new MonitoringOutputBoundary() {
            @Override
            public void presentCheckResult(HealthCheckResult result) {
            }

            @Override
            public void presentHealth(ServiceHealth health) {
            }

            @Override
            public void presentError(String message) {
                errors.add(message);
            }
        };

        MonitoringInteractor interactor = new MonitoringInteractor(
                checker,
                store,
                output,
                listener
        );

        interactor.checkOnce(service);
        require(deliveries.isEmpty(),
                "A failure below the threshold should not be delivered");

        interactor.checkOnce(service);
        require(deliveries.size() == 1,
                "Crossing the threshold should attempt delivery once");
        require(deliveries.get(0).getEvidence().size() == 2,
                "The event should retain both failed checks");

        interactor.checkOnce(service);
        require(deliveries.size() == 2,
                "An unconfirmed event should be retried on the next check");
        require(deliveries.get(0) == deliveries.get(1),
                "Retry should reuse the same event object and ID");

        interactor.checkOnce(service);
        require(deliveries.size() == 2,
                "A confirmed event should stop being retried");

        if (throwOnFirstDelivery) {
            require(errors.size() == 1
                            && errors.get(0).contains("Temporary delivery failure"),
                    "A delivery exception should be reported once");
        } else {
            require(errors.isEmpty(),
                    "A retryable result should preserve the event without an exception");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}