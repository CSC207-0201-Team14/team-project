package use_case.investigate_incident;

import entity.Dependency;
import entity.Incident;
import entity.MonitoredSystem;
import entity.MonitoringTarget;
import entity.RecoveryAction;
import entity.Service;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static support.Checks.check;
import static support.TestData.at;
import static support.TestData.failure;
import static support.TestData.healthy;
import static support.TestData.service;

/** Run explicitly after mvn test-compile, following the existing test convention. */
public final class RecoveryRecommendationChecks {
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

        Incident resolved = incident.resolve(at(20), at(15), Map.of(fixture.db, healthy(fixture.db, at(20))));
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
            services = List.of(
                    service(web, systemId, "Web", MonitoringTarget.docker("web-container")),
                    service(api, systemId, "API", MonitoringTarget.docker("api-container")),
                    service(db, systemId, "DB", dockerDb ? MonitoringTarget.docker("db-container")
                            : MonitoringTarget.http(URI.create("http://db.local/health"))));
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
                incident = incident == null
                        ? Incident.open(UUID.randomUUID(), systemId, at(10), failure(failing[index], at(index)))
                        : incident.recordFailure(failure(failing[index], at(index)));
            }
            return incident;
        }
    }

    private static List<UUID> targets(List<RecoveryAction> actions) {
        return actions.stream().map(RecoveryAction::getTargetServiceId).collect(Collectors.toList());
    }
}
