package interface_adapter.investigate_incident;

import entity.Dependency;
import entity.DiagnosticFinding;
import entity.FailureEvent;
import entity.HealthCheckResult;
import entity.Incident;
import entity.RecoveryAction;
import use_case.investigate_incident.IncidentDetails;
import use_case.investigate_incident.IncidentSummary;

import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static support.Checks.check;
import static support.TestData.at;
import static support.TestData.failure;
import static support.TestData.observation;

/** Run explicitly after mvn test-compile, following the existing test convention. */
public final class IncidentPresenterChecks {
    private final UUID systemId = UUID.randomUUID();
    private final UUID api = UUID.randomUUID();
    private final UUID db = UUID.randomUUID();
    private final UUID web = UUID.randomUUID();
    private final Map<UUID, String> names = Map.of(api, "API", db, "Database", web, "Web");
    private final List<Runnable> uiQueue = new ArrayList<>();
    private final IncidentViewModel viewModel = new IncidentViewModel();
    private final IncidentPresenter presenter = new IncidentPresenter(viewModel, uiQueue::add, ZoneOffset.UTC);

    public static void main(String[] args) {
        new IncidentPresenterChecks().updatesOnlyOnTheUiThread();
        new IncidentPresenterChecks().keepsUseCaseOrderWithNames();
        new IncidentPresenterChecks().statesUnknownCauseAndManualGuidance();
        new IncidentPresenterChecks().labelsSuspectedCauseAndEvidence();
        System.out.println("UC3 incident presenter checks passed");
    }

    private void updatesOnlyOnTheUiThread() {
        List<String> events = new ArrayList<>();
        viewModel.addPropertyChangeListener(event -> events.add(event.getPropertyName()));
        Incident incident = Incident.open(UUID.randomUUID(), systemId, at(10), failure(api, at(10)));
        presenter.presentProcessed(incident);
        presenter.presentError("Disk unavailable");
        check(events.isEmpty(), "Nothing changes until the UI thread runs the update");
        runUi();
        check(events.equals(List.of(IncidentViewModel.PROCESSED, IncidentViewModel.ERROR))
                && viewModel.getError().equals("Disk unavailable"), "Updates reach the view model on the UI thread");
    }

    private void keepsUseCaseOrderWithNames() {
        Incident older = Incident.open(UUID.randomUUID(), systemId, at(10), failure(api, at(10)));
        Incident newer = Incident.open(UUID.randomUUID(), systemId, at(50), failure(db, at(50)))
                .recordFailure(failure(UUID.randomUUID(), at(51)));
        presenter.presentIncidents(List.of(new IncidentSummary(newer, names), new IncidentSummary(older, names)));
        runUi();
        List<IncidentListItem> items = viewModel.getIncidents();
        check(items.size() == 2 && items.get(0).getIncidentId().equals(newer.getId()), "Rows keep the order chosen by the use case");
        check(items.get(0).getStatus().equals("Open")
                        && items.get(0).getLabel().startsWith("Open · opened 2026-01-01 00:00:50 · Database, "),
                "Rows show status, time and configured names");
        check(items.get(0).getLabel().contains("Unconfigured service "),
                "A removed service is shown as unconfigured instead of disappearing");
    }

    /** Extensions 1a and 5a, plus evidence that could not be loaded. */
    private void statesUnknownCauseAndManualGuidance() {
        FailureEvent apiFailure = failure(api, at(10));
        HealthCheckResult failedCheck = apiFailure.getEvidence().get(0);
        UUID lost = UUID.randomUUID();
        DiagnosticFinding observed = new DiagnosticFinding(UUID.randomUUID(), "observed-failure", api, at(11),
                Optional.empty(), List.of(failedCheck.getId(), lost), List.of(),
                "API failed its health checks. Dependency-based impact analysis is unavailable.",
                List.of("Inspect the recorded checks and service logs."));
        Incident incident = Incident.open(UUID.randomUUID(), systemId, at(10), apiFailure)
                .updateAssessment(Set.of(), List.of(observed));
        presenter.presentDetails(new IncidentDetails(incident, Map.of(failedCheck.getId(), failedCheck), Set.of(lost),
                List.of(), names));
        runUi();
        IncidentDetailsState state = viewModel.getDetails().orElseThrow();
        check(state.getSuspectedCause().startsWith("Cause unknown."), "1a: no suspected cause means unknown");
        check(state.getEvidence().contains("API failed")
                        && state.getEvidence().contains("Unavailable: 1 referenced check result is no longer"),
                "Loaded evidence is shown and missing evidence is listed");
        check(state.getRecommendedActions().isEmpty()
                        && state.getRecommendationNote().startsWith("No recovery action is supported"),
                "5a: without a supported action, manual next steps apply");
        check(state.getNextSteps().equals("• Inspect the recorded checks and service logs."),
                "Next steps come from the findings");
        check(state.getPotentialImpact().equals("No other services are known to be affected."),
                "Empty impact is stated plainly");
    }

    private void labelsSuspectedCauseAndEvidence() {
        FailureEvent apiFailure = failure(api, at(10));
        HealthCheckResult dbCheck = observation(db, at(8), HealthCheckResult.Outcome.FAIL);
        Dependency edge = new Dependency(UUID.randomUUID(), api, db);
        DiagnosticFinding first = new DiagnosticFinding(UUID.randomUUID(), "observed-failure", api, at(11),
                Optional.empty(), List.of(apiFailure.getEvidence().get(0).getId()), List.of(), "API failed.",
                List.of("Inspect the API."));
        DiagnosticFinding suspected = new DiagnosticFinding(UUID.randomUUID(), "dependency-failure", api, at(12),
                Optional.of(db), List.of(dbCheck.getId()), List.of(edge), "Database failed before API.",
                List.of("Inspect the required service and the recorded dependency path."));
        DiagnosticFinding impact = new DiagnosticFinding(UUID.randomUUID(), "potential-impact", web, at(12),
                Optional.of(api), List.of(apiFailure.getEvidence().get(0).getId()), List.of(), "Web may be affected.",
                List.of("Check the dependent service."));
        Incident incident = Incident.open(UUID.randomUUID(), systemId, at(10), apiFailure)
                .updateAssessment(Set.of(), List.of(first))
                .updateAssessment(Set.of(web), List.of(suspected, impact))
                .startInvestigation(at(20));
        RecoveryAction action = new RecoveryAction(UUID.randomUUID(), incident.getId(), db,
                RecoveryAction.ActionType.RECOVER_DOCKER_CONTAINER, "Recover container db for Database.");
        presenter.presentDetails(new IncidentDetails(incident, Map.of(dbCheck.getId(), dbCheck), Set.of(),
                List.of(action), names));
        runUi();
        IncidentDetailsState state = viewModel.getDetails().orElseThrow();
        check(state.getSuspectedCause().startsWith("• Database may explain the failure of API (suspected, not confirmed)."),
                "A suspected cause is labelled as a hypothesis");
        check(state.getSuspectedCause().contains("• API may be affecting Web (suspected, not confirmed)."),
                "A service without a recorded failure is described as possibly affected, not as failed");
        check(state.getEvidence().contains("Database failed") && state.getEvidence().contains("• API requires Database")
                        && !state.getEvidence().contains("Unavailable"),
                "Evidence lists observations and dependency relationships");
        check(state.getPotentialImpact().startsWith("• Web\n"), "Potentially affected services are listed");
        check(state.getStatus().equals("Investigating")
                        && state.getTimeline().startsWith("Opened 2026-01-01 00:00:10\nInvestigation started"),
                "Status matches the list's status name and the timeline shows lifecycle timestamps");
        check(state.getRecommendedActions().equals(List.of("Recover container db for Database."))
                        && state.getRecommendationNote().contains("only after you review and confirm"),
                "Recommendations are suggestions that need confirmation");
        check(state.getHistory().startsWith("1 earlier finding is kept"), "Superseded findings stay in history");
    }

    private void runUi() {
        List<Runnable> pending = new ArrayList<>(uiQueue);
        uiQueue.clear();
        pending.forEach(Runnable::run);
    }
}
