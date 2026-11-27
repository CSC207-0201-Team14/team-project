package interface_adapter.monitor_service_health;

import entity.HealthCheckResult;
import entity.HealthStatus;
import entity.MonitoredSystem;
import entity.MonitoringPolicy;
import entity.MonitoringTarget;
import entity.Service;
import entity.ServiceHealth;
import use_case.monitor_service_health.MonitoringSessionInputBoundary;
import view.monitor_service_health.MonitoringView;

import javax.swing.JComboBox;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;

/** Headless checks for service-specific display, configuration updates and EDT delivery. */
public final class MonitoringUiChecks {
    private static final UUID SYSTEM_ID = UUID.randomUUID();

    public static void main(String[] args) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            keepsResultsWithTheirService();
            stopsObsoleteSessions();
            updatesTheSavedRoster();
        });
        presenterDeliversOnEdt();
        System.out.println("MonitoringUiChecks passed");
    }

    private static void keepsResultsWithTheirService() {
        MonitoringViewModel model = new MonitoringViewModel();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        HealthCheckResult pass = observation(first, HealthCheckResult.Outcome.PASS);
        HealthCheckResult fail = observation(second, HealthCheckResult.Outcome.FAIL);
        model.selectService(first);
        model.showResult(pass);
        model.showHealth(ServiceHealth.evaluated(HealthStatus.HEALTHY, pass));
        model.showResult(fail);
        model.showHealth(ServiceHealth.evaluated(HealthStatus.UNHEALTHY, fail));
        require(model.getResults().equals(List.of(pass)) && model.getHealthStatus() == HealthStatus.HEALTHY,
                "A background result for another service must not replace the selected display");
        model.selectService(second);
        require(model.getResults().equals(List.of(fail)) && model.getHealthStatus() == HealthStatus.UNHEALTHY,
                "Switching services selects that service's own history and health");
        model.selectService(null);
        require(model.getResults().isEmpty() && model.getHealthStatus() == HealthStatus.UNKNOWN,
                "No selection has no results or known health");
    }

    private static void stopsObsoleteSessions() {
        Sessions sessions = new Sessions();
        MonitoringViewModel model = new MonitoringViewModel();
        MonitoringController controller = controller(sessions, model);
        UUID id = UUID.randomUUID();
        Service original = service(id, "/original", 2);
        controller.startMonitoring(original);
        require(controller.reconcileServices(List.of(service(id, "/original", 2))) == 0
                        && sessions.isMonitoring(id),
                "Reloading equivalent configuration must preserve its active session");
        require(controller.reconcileServices(List.of(service(id, "/edited", 2))) == 1
                        && !sessions.isMonitoring(id),
                "Changing the endpoint stops the old schedule instead of polling an obsolete target");
        controller.startMonitoring(original);
        require(controller.reconcileServices(List.of(service(id, "/original", 3))) == 1,
                "Changing a threshold also invalidates the old session");
        controller.startMonitoring(original);
        require(controller.reconcileServices(List.of()) == 1 && !sessions.isMonitoring(id),
                "Removing a service stops its schedule");
    }

    private static void updatesTheSavedRoster() {
        Sessions sessions = new Sessions();
        MonitoringViewModel model = new MonitoringViewModel();
        MonitoringController controller = controller(sessions, model);
        MonitoringView view = new MonitoringView(controller, model);
        Service first = service(UUID.randomUUID(), "/first", 2);
        Service second = service(UUID.randomUUID(), "/second", 2);
        view.showSystems(List.of(system(List.of(first, second))));
        JComboBox<?> services = (JComboBox<?>) find(view, "monitorService");
        require(services.getItemCount() == 2, "Both saved services must be selectable");
        services.setSelectedIndex(1);
        HealthCheckResult pass = observation(second.getId(), HealthCheckResult.Outcome.PASS);
        model.showResult(pass);
        controller.startMonitoring(second);
        Service edited = service(second.getId(), "/updated", 2);
        view.showSavedSystem(system(List.of(first, edited)));
        require(((Service) services.getSelectedItem()).getId().equals(second.getId())
                        && model.getResults().equals(List.of(pass)) && !sessions.isMonitoring(second.getId()),
                "Saving an edit preserves selection and history but cancels the obsolete session");
        view.showSavedSystem(system(List.of()));
        require(services.getItemCount() == 0 && model.getResults().isEmpty(),
                "Removing every service must clear the selected display");
    }

    private static void presenterDeliversOnEdt() throws Exception {
        MonitoringViewModel model = new MonitoringViewModel();
        SwingUtilities.invokeAndWait(() -> model.addPropertyChangeListener(event ->
                require(SwingUtilities.isEventDispatchThread(), "Display updates must run on the EDT")));
        HealthCheckResult pass = observation(UUID.randomUUID(), HealthCheckResult.Outcome.PASS);
        MonitoringPresenter presenter = new MonitoringPresenter(model);
        // The main thread stands in for a scheduler/network worker.
        presenter.presentCheckResult(pass);
        presenter.presentHealth(ServiceHealth.evaluated(HealthStatus.HEALTHY, pass));
        presenter.presentError("A storage error");
        SwingUtilities.invokeAndWait(() -> {
            model.selectService(pass.getServiceId());
            require(model.getResults().equals(List.of(pass)) && model.getHealthStatus() == HealthStatus.HEALTHY
                            && model.getErrorMessage().equals("A storage error"),
                    "The queued presenter updates must arrive before the EDT barrier");
        });
    }

    private static MonitoringController controller(Sessions sessions, MonitoringViewModel model) {
        return new MonitoringController(service -> { }, sessions, new MonitoringPresenter(model));
    }

    private static Service service(UUID id, String path, int recoveryThreshold) {
        return new Service(id, SYSTEM_ID, "Test service", MonitoringTarget.http(URI.create("http://localhost" + path)),
                new MonitoringPolicy(Duration.ofSeconds(3), Duration.ofSeconds(1), 2, recoveryThreshold));
    }

    private static MonitoredSystem system(List<Service> services) {
        // The domain requires distinct names as well as IDs.
        List<Service> named = new java.util.ArrayList<>();
        for (int i = 0; i < services.size(); i++) {
            Service service = services.get(i);
            named.add(new Service(service.getId(), SYSTEM_ID, "Service " + i,
                    service.getMonitoringTarget(), service.getMonitoringPolicy()));
        }
        return new MonitoredSystem(SYSTEM_ID, "UI check", "", named, List.of());
    }

    private static HealthCheckResult observation(UUID serviceId, HealthCheckResult.Outcome outcome) {
        return new HealthCheckResult(UUID.randomUUID(), serviceId, Instant.parse("2026-11-24T12:00:00Z"),
                outcome, OptionalLong.empty(), outcome.name());
    }

    private static Component find(Container parent, String name) {
        for (Component child : parent.getComponents()) {
            if (name.equals(child.getName())) {
                return child;
            }
            if (child instanceof Container) {
                Component found = find((Container) child, name);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static final class Sessions implements MonitoringSessionInputBoundary {
        private final Map<UUID, Service> active = new HashMap<>();

        @Override public void startMonitoring(Service service) { active.putIfAbsent(service.getId(), service); }
        @Override public void stopMonitoring(UUID serviceId) { active.remove(serviceId); }
        @Override public void stopAll() { active.clear(); }
        @Override public boolean isMonitoring(UUID serviceId) { return active.containsKey(serviceId); }
    }
}
