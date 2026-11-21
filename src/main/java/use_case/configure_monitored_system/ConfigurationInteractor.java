package use_case.configure_monitored_system;

import entity.MonitoredSystem;
import entity.MonitoringPolicy;
import entity.MonitoringTarget;
import entity.Service;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

public final class ConfigurationInteractor implements ConfigurationInputBoundary {
    private final ConfigurationRepository repository;
    private final ConfigurationOutputBoundary output;

    public ConfigurationInteractor(ConfigurationRepository repository, ConfigurationOutputBoundary output) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.output = Objects.requireNonNull(output, "output");
    }

    @Override
    public void loadSystems() {
        List<MonitoredSystem> systems;
        try {
            systems = List.copyOf(repository.findAll());
        } catch (ConfigurationStoreException exception) {
            output.presentError("Could not load systems. " + exception.getMessage());
            return;
        }
        output.presentSystems(systems);
    }

    @Override
    public void save(ConfigurationInput input) {
        MonitoredSystem system;
        try {
            List<Service> services = input.services.stream()
                    .map(service -> toService(input.systemId, service))
                    .collect(Collectors.toList());
            system = new MonitoredSystem(input.systemId, input.name, input.description, services, input.dependencies);
        } catch (IllegalArgumentException exception) {
            output.presentError(exception.getMessage());
            return;
        }

        try {
            repository.save(system);
        } catch (ConfigurationStoreException exception) {
            output.presentError("Could not save configuration. " + exception.getMessage());
            return;
        }
        output.presentSaved(system);
    }

    private static Service toService(UUID systemId, ServiceInput input) {
        try {
            MonitoringTarget target = input.getMethod() == MonitoringTarget.Method.HTTP
                    ? parseEndpoint(input.getTarget()) : MonitoringTarget.docker(input.getTarget());
            Duration interval = parseDuration(input.getIntervalMillis(), "Check interval");
            Duration timeout = parseDuration(input.getTimeoutMillis(), "Timeout");
            int failures = parseThreshold(input.getFailureThreshold(), "Failure threshold");
            int recoveries = parseThreshold(input.getRecoveryThreshold(), "Recovery threshold");
            MonitoringPolicy policy = new MonitoringPolicy(interval, timeout, failures, recoveries);
            return new Service(input.getId(), systemId, input.getName(), target, policy);
        } catch (IllegalArgumentException exception) {
            String service = input.getName().isBlank() ? "Unnamed service" : input.getName().strip();
            throw new IllegalArgumentException(service + " — " + exception.getMessage(), exception);
        }
    }

    private static MonitoringTarget parseEndpoint(String target) {
        try {
            URI endpoint = URI.create(target.strip());
            if (endpoint.getRawAuthority() != null && endpoint.getRawAuthority().endsWith(":")) {
                throw new IllegalArgumentException("Empty port");
            }
            return MonitoringTarget.http(endpoint);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Enter an HTTP or HTTPS URL with a host and a valid port.", exception);
        }
    }

    private static Duration parseDuration(String value, String field) {
        String message = field + " must be a positive duration in milliseconds within the supported range.";
        try {
            // Keep sub-millisecond precision and fit the scheduler's nanosecond representation.
            long nanos = new BigDecimal(value.strip()).scaleByPowerOfTen(6).longValueExact();
            if (nanos > 0) {
                return Duration.ofNanos(nanos);
            }
        } catch (NumberFormatException | ArithmeticException exception) {
            throw new IllegalArgumentException(message, exception);
        }
        throw new IllegalArgumentException(message);
    }

    private static int parseThreshold(String value, String field) {
        String message = field + " must be a positive whole number.";
        try {
            int parsed = Integer.parseInt(value.strip());
            if (parsed > 0) {
                return parsed;
            }
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(message, exception);
        }
        throw new IllegalArgumentException(message);
    }
}
