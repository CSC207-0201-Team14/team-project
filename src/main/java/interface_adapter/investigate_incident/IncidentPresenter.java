package interface_adapter.investigate_incident;

import entity.Dependency;
import entity.DiagnosticFinding;
import entity.FailureEvent;
import entity.HealthCheckResult;
import entity.Incident;
import entity.RecoveryAction;
import use_case.investigate_incident.IncidentDetails;
import use_case.investigate_incident.IncidentOutputBoundary;
import use_case.investigate_incident.IncidentSummary;

import javax.swing.SwingUtilities;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

/**
 * Turns UC3 output into display text and updates the view model on the UI thread.
 * It keeps observed facts and hypotheses apart: suspected causes are labelled as unconfirmed, an incident
 * without one says the cause is unknown, and evidence that could not be loaded is listed as unavailable.
 */
public final class IncidentPresenter implements IncidentOutputBoundary {
    private static final String NONE = "None.";

    private final IncidentViewModel viewModel;
    private final Executor uiThread;
    private final DateTimeFormatter time;

    /** Presents on the Swing EDT in the system time zone. */
    public IncidentPresenter(IncidentViewModel viewModel) {
        this(viewModel, SwingUtilities::invokeLater, ZoneId.systemDefault());
    }

    public IncidentPresenter(IncidentViewModel viewModel, Executor uiThread, ZoneId zone) {
        this.viewModel = Objects.requireNonNull(viewModel, "viewModel");
        this.uiThread = Objects.requireNonNull(uiThread, "uiThread");
        this.time = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(Objects.requireNonNull(zone, "zone"));
    }

    @Override
    public void presentProcessed(Incident incident) {
        UUID systemId = incident.getSystemId();
        uiThread.execute(() -> viewModel.announceProcessed(systemId));
    }

    @Override
    public void presentIncidents(List<IncidentSummary> incidents) {
        // The use case already orders incidents newest first; the presenter keeps that order.
        List<IncidentListItem> items = incidents.stream()
                .map(this::listItem)
                .collect(Collectors.toList());
        uiThread.execute(() -> viewModel.setIncidents(items));
    }

    @Override
    public void presentDetails(IncidentDetails details) {
        IncidentDetailsState state = detailsState(details);
        uiThread.execute(() -> viewModel.setDetails(state));
    }

    @Override
    public void presentError(String message) {
        uiThread.execute(() -> viewModel.setError(message));
    }

    private IncidentListItem listItem(IncidentSummary summary) {
        String status = statusName(summary.getStatus());
        String services = summary.getFailingServiceIds().stream()
                .map(id -> name(summary.getServiceNames(), id)).sorted().collect(Collectors.joining(", "));
        return new IncidentListItem(summary.getIncidentId(), status,
                status + " · opened " + time.format(summary.getOpenedAt()) + " · " + services);
    }

    private IncidentDetailsState detailsState(IncidentDetails details) {
        Incident incident = details.getIncident();
        Map<UUID, String> names = details.getServiceNames();
        String failing = incident.getFailingServiceIds().stream().map(id -> name(names, id)).sorted()
                .collect(Collectors.joining(", "));
        List<String> actions = details.getRecommendedActions().stream()
                .map(RecoveryAction::getDescription).collect(Collectors.toList());
        return new IncidentDetailsState(incident.getId(), "Incident affecting " + failing,
                statusName(incident.getStatus()), timeline(incident),
                suspectedCause(incident, names), recordedFailures(incident, names), evidence(details, names),
                potentialImpact(incident, names), nextSteps(incident), actions, recommendationNote(actions),
                history(incident));
    }

    private String timeline(Incident incident) {
        StringBuilder text = new StringBuilder("Opened ").append(time.format(incident.getOpenedAt()));
        incident.getInvestigatingAt().ifPresent(at -> text.append("\nInvestigation started ").append(time.format(at)));
        incident.getResolvedAt().ifPresent(at -> text.append("\nResolved ").append(time.format(at)));
        return text.toString();
    }

    /** Extension 1a: without a finding that names a cause, the cause is stated as unknown. */
    private static String suspectedCause(Incident incident, Map<UUID, String> names) {
        List<String> causes = new ArrayList<>();
        for (DiagnosticFinding finding : incident.getActiveFindings()) {
            UUID affected = finding.getAffectedServiceId();
            // A potentially affected service has no recorded failure, so there is no failure to explain yet.
            String relation = incident.getFailingServiceIds().contains(affected)
                    ? " may explain the failure of " : " may be affecting ";
            finding.getSuspectedCauseServiceId().ifPresent(cause -> causes.add("• " + name(names, cause)
                    + relation + name(names, affected) + " (suspected, not confirmed). " + finding.getExplanation()));
        }
        if (causes.isEmpty()) {
            return "Cause unknown. No finding has enough evidence to suggest a cause; "
                    + "the recorded failures and available evidence are shown below.";
        }
        return String.join("\n", causes);
    }

    private String recordedFailures(Incident incident, Map<UUID, String> names) {
        return incident.getFailures().stream()
                .sorted(Comparator.comparing(FailureEvent::getTimestamp))
                .map(failure -> "• " + name(names, failure.getServiceId()) + " became unhealthy at "
                        + time.format(failure.getTimestamp()) + " after " + failure.getEvidence().size()
                        + " failing check" + (failure.getEvidence().size() == 1 ? "" : "s"))
                .collect(Collectors.joining("\n"));
    }

    /** Observations and dependency relationships behind the active findings; unavailable evidence is listed. */
    private String evidence(IncidentDetails details, Map<UUID, String> names) {
        List<String> lines = new ArrayList<>();
        details.getObservations().values().stream()
                .sorted(Comparator.comparing(HealthCheckResult::getTimestamp).thenComparing(HealthCheckResult::getId))
                .forEach(result -> lines.add("• " + observation(result, names)));
        Set<String> edges = new LinkedHashSet<>();
        for (DiagnosticFinding finding : details.getIncident().getActiveFindings()) {
            for (Dependency edge : finding.getDependencyEvidence()) {
                edges.add("• " + name(names, edge.getDependentServiceId()) + " requires "
                        + name(names, edge.getRequiredServiceId()));
            }
        }
        if (!edges.isEmpty()) {
            lines.add("Dependencies:");
            lines.addAll(edges);
        }
        int missing = details.getMissingObservationIds().size();
        if (missing > 0) {
            lines.add("Unavailable: " + missing + " referenced check result" + (missing == 1 ? " is" : "s are")
                    + " no longer in monitoring history, so " + (missing == 1 ? "it is" : "they are")
                    + " not shown.");
        }
        return lines.isEmpty() ? NONE : String.join("\n", lines);
    }

    private String observation(HealthCheckResult result, Map<UUID, String> names) {
        StringBuilder text = new StringBuilder(time.format(result.getTimestamp())).append("  ")
                .append(name(names, result.getServiceId())).append(' ').append(outcome(result.getOutcome()));
        result.getResponseTimeMs().ifPresent(ms -> text.append(" in ").append(ms).append(" ms"));
        if (!result.getMessage().isBlank()) {
            text.append(" — ").append(result.getMessage());
        }
        return text.toString();
    }

    private static String potentialImpact(Incident incident, Map<UUID, String> names) {
        Set<UUID> affected = incident.getPotentiallyAffectedServiceIds();
        if (affected.isEmpty()) {
            return "No other services are known to be affected.";
        }
        return affected.stream().map(id -> "• " + name(names, id)).sorted().collect(Collectors.joining("\n"))
                + "\nThese services depend on a failing service; no failure has been recorded for them.";
    }

    private static String nextSteps(Incident incident) {
        Set<String> steps = new LinkedHashSet<>();
        incident.getActiveFindings().forEach(finding -> steps.addAll(finding.getNextSteps()));
        return steps.isEmpty() ? NONE : steps.stream().map(step -> "• " + step).collect(Collectors.joining("\n"));
    }

    /** Extension 5a: with no supported action, only the manual next steps apply. */
    private static String recommendationNote(List<String> actions) {
        return actions.isEmpty()
                ? "No recovery action is supported for this incident. Follow the manual next steps above."
                : "Recovery is a suggestion. It runs only after you review and confirm it.";
    }

    private static String history(Incident incident) {
        int earlier = incident.getFindingHistory().size() - incident.getActiveFindings().size();
        return earlier <= 0 ? "No earlier assessments."
                : earlier + " earlier finding" + (earlier == 1 ? " is" : "s are")
                + " kept in the incident history from previous assessments.";
    }

    private static String statusName(Incident.Status status) {
        switch (status) {
            case OPEN:
                return "Open";
            case INVESTIGATING:
                return "Investigating";
            default:
                return "Resolved";
        }
    }

    private static String outcome(HealthCheckResult.Outcome outcome) {
        switch (outcome) {
            case PASS:
                return "passed";
            case FAIL:
                return "failed";
            default:
                return "could not be checked";
        }
    }

    /** Configured name, or a short ID for a service that is no longer configured. */
    private static String name(Map<UUID, String> names, UUID serviceId) {
        String name = names.get(serviceId);
        return name != null ? name : "Unconfigured service " + serviceId.toString().substring(0, 8);
    }
}
