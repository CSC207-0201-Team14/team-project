package use_case.investigate_incident;

import entity.Dependency;
import entity.FailureEvent;
import entity.HealthCheckResult;
import entity.HealthStatus;
import entity.Incident;
import entity.MonitoredSystem;
import entity.MonitoringPolicy;
import entity.MonitoringTarget;
import entity.RecoveryAction;
import entity.Service;
import entity.ServiceHealth;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Run explicitly after mvn test-compile, following the existing test convention. */
public final class RecoveryRecommendationChecks {
    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
    private static final RecoveryRecommendationProvider PROVIDER = new RecoveryRecommendationProvider();

    public static void main(String[] args) {
        targetsOnlyTheMostUpstreamFailingContainer();
        givesNoActionWithoutSupportedTarget();
        targetsEveryContainerInAFailingCycle();
        skipsUnrelatedOrResolvedIncidents();
        System.out.println("UC3 recovery recommendation checks passed");
    }

    private static void targetsOnlyTheMostUpstreamFailingContainer() {
        Fixture fixture = new Fixture(true, false);
        Incident incident = fixture.incident(fixture.web, fixture.api, fixture.db);
        List<RecoveryAction> actions = PROVIDER.recommend(incident, fixture.configuration());
        check(targets(actions).equals(List.of(fixture.db)), "Only DB, the most upstream failure, is targeted");
        RecoveryAction action = actions.get(0);
        check(action.getActionType() == RecoveryAction.ActionType.RECOVER_DOCKER_CONTAINER
                && action.getIncidentId().equals(incident.getId()) && action.requiresConfirmation(),
                "The action names the container and still requires confirmation");
        check(action.getDescription().contains("db-container") && action.getDescription().contains("suspected"),
                "The description names the container and avoids claiming certainty");
        check(PROVIDER.recommend(incident, fixture.configuration()).get(0).getId().equals(action.getId()),
                "Reopening the incident yields a stable recommendation ID");

        Incident withoutDb = fixture.incident(fixture.web, fixture.api);
        check(targets(PROVIDER.recommend(withoutDb, fixture.configuration())).equals(List.of(fixture.api)),
                "With DB healthy, API becomes the most upstream failure");
    }

    private static void givesNoActionWithoutSupportedTarget() {
        Fixture httpDb = new Fixture(false, false);
        check(PROVIDER.recommend(httpDb.incident(httpDb.api, httpDb.db), httpDb.configuration()).isEmpty(),
                "An HTTP root cause gets manual steps only, and its dependents are not targeted instead");

        Fixture fixture = new Fixture(true, false);
        MonitoredSystem noEdges = new MonitoredSystem(fixture.systemId, "Shop", "", fixture.services, List.of());
        ConfigurationSnapshot unavailable = ConfigurationSnapshot.dependenciesUnavailable(noEdges, "Graph unavailable");
        check(PROVIDER.recommend(fixture.incident(fixture.api, fixture.db), unavailable).isEmpty(),
                "Without dependency information no upstream target can be identified");
    }

    private static void targetsEveryContainerInAFailingCycle() {
        Fixture fixture = new Fixture(true, true);
        List<UUID> targets = targets(PROVIDER.recommend(fixture.incident(fixture.web, fixture.api, fixture.db),
                fixture.configuration()));
        check(Set.copyOf(targets).equals(Set.of(fixture.api, fixture.db)) && targets.size() == 2,
                "API and DB depend on each other, so both are targeted while WEB stays downstream");
        RecoveryAction action = PROVIDER.recommend(fixture.incident(fixture.api, fixture.db),
                fixture.configuration()).get(0);
        check(action.getDescription().contains("cycle"), "The cycle is explained");
    }

    private static void skipsUnrelatedOrResolvedIncidents() {
        Fixture fixture = new Fixture(true, false);
        Incident incident = fixture.incident(fixture.db);
        Fixture other = new Fixture(true, false);
        check(PROVIDER.recommend(incident, other.configuration()).isEmpty(),
                "Configuration from another system is ignored");

        HealthCheckResult passed = new HealthCheckResult(UUID.randomUUID(), fixture.db, START.plusSeconds(20),
                HealthCheckResult.Outcome.PASS, OptionalLong.empty(), "");
        Incident resolved = incident.resolve(START.plusSeconds(20), START.plusSeconds(15),
                Map.of(fixture.db, ServiceHealth.evaluated(HealthStatus.HEALTHY, passed)));
        check(PROVIDER.recommend(resolved, fixture.configuration()).isEmpty(), "Resolved incidents get no actions");
    }

    private static final class Fixture {
        private final UUID systemId = UUID.randomUUID();
        private final UUID web = UUID.randomUUID();
        private final UUID api = UUID.randomUUID();
        private final UUID db = UUID.randomUUID();
        private final List<Service> services;
        private final List<Dependency> dependencies = new ArrayList<>();

        /** WEB -> API -> DB, optionally with DB -> API forming a cycle. */
        private Fixture(boolean dockerDb, boolean cycle) {
            MonitoringPolicy policy = new MonitoringPolicy(Duration.ofSeconds(10), Duration.ofSeconds(2), 2, 2);
            services = List.of(
                    new Service(web, systemId, "Web", MonitoringTarget.docker("web-container"), policy),
                    new Service(api, systemId, "API", MonitoringTarget.docker("api-container"), policy),
                    new Service(db, systemId, "DB", dockerDb ? MonitoringTarget.docker("db-container")
                            : MonitoringTarget.http(URI.create("http://db.local/health")), policy));
            dependencies.add(new Dependency(UUID.randomUUID(), web, api));
            dependencies.add(new Dependency(UUID.randomUUID(), api, db));
            if (cycle) {
                dependencies.add(new Dependency(UUID.randomUUID(), db, api));
            }
        }

        private ConfigurationSnapshot configuration() {
            return ConfigurationSnapshot.available(new MonitoredSystem(systemId, "Shop", "", services, dependencies));
        }

        private Incident incident(UUID... failing) {
            Incident incident = null;
            for (int index = 0; index < failing.length; index++) {
                FailureEvent failure = failure(failing[index], index);
                incident = incident == null
                        ? Incident.open(UUID.randomUUID(), systemId, START.plusSeconds(10), failure)
                        : incident.recordFailure(failure);
            }
            return incident;
        }
    }

    private static FailureEvent failure(UUID serviceId, int offset) {
        HealthCheckResult failed = new HealthCheckResult(UUID.randomUUID(), serviceId, START.plusSeconds(offset),
                HealthCheckResult.Outcome.FAIL, OptionalLong.empty(), "");
        return new FailureEvent(UUID.randomUUID(), serviceId, START.plusSeconds(offset), List.of(failed));
    }

    private static List<UUID> targets(List<RecoveryAction> actions) {
        return actions.stream().map(RecoveryAction::getTargetServiceId).collect(Collectors.toList());
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
