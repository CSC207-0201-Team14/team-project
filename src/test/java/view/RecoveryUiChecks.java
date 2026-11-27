package view;

import entity.RecoveryAction;
import entity.ServiceHealth;
import interface_adapter.investigate_incident.IncidentDetailsState;
import interface_adapter.investigate_incident.IncidentDetailsState.RecoveryChoice;
import interface_adapter.recover_service.RecoveryController;
import interface_adapter.recover_service.RecoveryPresenter;
import use_case.recover_service.ContainerState;
import use_case.recover_service.RecoveryDecision;
import use_case.recover_service.RecoveryOperation;
import use_case.recover_service.RecoveryPreview;
import use_case.recover_service.ServiceVerification;
import use_case.recover_service.RecoveryInputBoundary;
import use_case.recover_service.RecoveryRequest;

import javax.swing.JButton;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class RecoveryUiChecks {
    public static void main(String[] args) throws Exception {
        RecoveryPresenter presenter = new RecoveryPresenter(SwingUtilities::invokeLater);
        PendingRecovery pending = new PendingRecovery();
        RecoveryController controller = new RecoveryController(pending, presenter);
        AtomicReference<RecoveryPanel> panel = new AtomicReference<>();
        AtomicBoolean deliveredOnEdt = new AtomicBoolean();
        UUID incidentId = UUID.randomUUID();
        UUID actionId = UUID.randomUUID();
        SwingUtilities.invokeAndWait(() -> {
            RecoveryPanel recovery = new RecoveryPanel(controller, () -> { });
            panel.set(recovery);
            recovery.showDetails(details(incidentId, List.of(new RecoveryChoice(actionId, "Recover DB"))));
            button(recovery, "Preview").doClick();
            require(actionId.equals(pending.action), "The selected recommendation ID crosses the boundary");
            recovery.showDetails(details(UUID.randomUUID(), List.of()));
            require(!button(recovery, "Preview").isEnabled(), "No recommendation means no action to preview");
        });

        UUID serviceId = UUID.randomUUID();
        presenter.presentPreview(new RecoveryPreview(new RecoveryAction(actionId, incidentId, serviceId,
                RecoveryAction.ActionType.RECOVER_DOCKER_CONTAINER, "Recover DB"), "DB", "db",
                new ContainerState(ContainerState.Status.EXITED, false, "canonical-id"),
                new ServiceVerification(serviceId, ServiceHealth.unobserved(serviceId), 0, 2, "Awaiting checks"),
                RecoveryDecision.perform(RecoveryOperation.START, "Container has stopped"), List.of()));
        pending.work.complete(null);
        SwingUtilities.invokeAndWait(() -> {
            RecoveryPanel recovery = panel.get();
            require(!button(recovery, "Confirm operation").isEnabled(),
                    "A late preview cannot enable confirmation for a different selection");
            require(button(recovery, "Begin manual verification").isEnabled(),
                    "Manual verification stays available without a Docker recommendation");

            pending.work = new CompletableFuture<>();
            controller.history(incidentId, state -> deliveredOnEdt.set(SwingUtilities.isEventDispatchThread()));
        });
        presenter.presentError("History unavailable");
        pending.work.complete(null);
        SwingUtilities.invokeAndWait(() -> require(deliveredOnEdt.get(), "The presenter publishes on the EDT"));

        AtomicReference<CompletionStage<Void>> drained = new AtomicReference<>();
        AtomicBoolean deliveredAfterClose = new AtomicBoolean();
        SwingUtilities.invokeAndWait(() -> {
            pending.work = new CompletableFuture<>();
            controller.history(incidentId, state -> deliveredAfterClose.set(true));
            drained.set(controller.stopAccepting());
            require(!drained.get().toCompletableFuture().isDone(), "Shutdown waits for accepted work");
        });
        pending.work.complete(null);
        drained.get().toCompletableFuture().join();
        SwingUtilities.invokeAndWait(() -> require(!deliveredAfterClose.get(), "Closed UI discards the response"));
        System.out.println("UC4 Swing adapter checks passed");
    }

    private static IncidentDetailsState details(UUID id, List<RecoveryChoice> choices) {
        return new IncidentDetailsState(id, "", "Investigating", "", "", "", "", "", "", choices, "", "");
    }

    private static JButton button(Container container, String label) {
        for (Component child : container.getComponents()) {
            if (child instanceof JButton && ((JButton) child).getText().equals(label)) {
                return (JButton) child;
            }
            if (child instanceof Container) {
                JButton nested = button((Container) child, label);
                if (nested != null) {
                    return nested;
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

    private static final class PendingRecovery implements RecoveryInputBoundary {
        private CompletableFuture<Void> work = new CompletableFuture<>();
        private UUID action;

        @Override
        public CompletionStage<Void> previewRecovery(UUID incidentId, UUID actionId) {
            action = actionId;
            return work;
        }

        @Override
        public CompletionStage<Void> executeRecovery(RecoveryRequest request) { return work; }

        @Override
        public CompletionStage<Void> beginManualVerification(UUID incidentId) { return work; }

        @Override
        public CompletionStage<Void> verifyResolution(UUID incidentId) { return work; }

        @Override
        public CompletionStage<Void> loadHistory(UUID incidentId) { return work; }
    }
}
