package use_case.investigate_incident;

import entity.Dependency;
import entity.Incident;
import entity.MonitoredSystem;
import entity.MonitoringTarget;
import entity.RecoveryAction;
import entity.Service;
import use_case.shared.ConfigurationSnapshot;
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
        recoveredServicesStopBlockingDownstreamTargets();
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
        check(Set.copyOf(targets(PROVIDER.recommend(fixture.incident(fixture.api, fixture.db),
                        ConfigurationSnapshot.available(noEdges)))).equals(Set.of(fixture.api, fixture.db)),
                "Without configured dependencies, every failing Docker service is its own most upstream failure");
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

    private static void recoveredServicesStopBlockingDownstreamTargets() {
        Fixture fixture = new Fixture(true, false);
        Incident incident = fixture.incident(fixture.web, fixture.api, fixture.db);
        RecoveryAction dbAction = PROVIDER.recommend(incident, fixture.configuration()).get(0);

        List<RecoveryAction> afterDb = PROVIDER.recommend(incident, fixture.configuration(), Set.of(fixture.db));
        check(targets(afterDb).equals(List.of(fixture.api, fixture.db).stream().sorted().collect(Collectors.toList())),
                "Once DB recovers, API is the most upstream service still failing; DB stays for UC4 to judge");
        RecoveryAction apiAction = afterDb.stream().filter(action -> action.getTargetServiceId().equals(fixture.api))
                .findFirst().orElseThrow();
        check(apiAction.getDescription().contains("have recovered, so it is the most upstream service still failing"),
                "API's description explains why it became a target");
        check(afterDb.stream().anyMatch(action -> action.getId().equals(dbAction.getId())),
                "DB keeps its stable action ID");

        check(targets(PROVIDER.recommend(incident, fixture.configuration(), Set.of(fixture.db, fixture.api)))
                        .equals(List.of(fixture.web, fixture.api, fixture.db).stream().sorted()
                                .collect(Collectors.toList())),
                "With API recovered too, Web is no longer held back");
        check(targets(PROVIDER.recommend(incident, fixture.configuration(), Set.of(fixture.api)))
                        .equals(List.of(fixture.db)),
                "A recovered service that still requires a failing one is not a target");
        check(targets(PROVIDER.recommend(incident, fixture.configuration(), Set.of(UUID.randomUUID())))
                        .equals(List.of(fixture.db)),
                "IDs outside the incident change nothing");

        Fixture httpDb = new Fixture(false, false);
        check(targets(PROVIDER.recommend(httpDb.incident(httpDb.api, httpDb.db), httpDb.configuration(),
                        Set.of(httpDb.db))).equals(List.of(httpDb.api)),
                "Once an HTTP root cause recovers, its failing Docker dependent can be recovered");
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
