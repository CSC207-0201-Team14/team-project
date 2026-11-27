package interface_adapter.recover_service;

import use_case.recover_service.RecoveryInputBoundary;
import use_case.recover_service.RecoveryPreview;
import use_case.recover_service.RecoveryRequest;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** EDT entry point. Keeps each presenter response associated with the request that produced it. */
public final class RecoveryController {
    private final RecoveryInputBoundary recovery;
    private final RecoveryPresenter presenter;
    private CompletionStage<Void> active = CompletableFuture.completedFuture(null);
    private boolean accepting = true;
    private boolean busy;

    public RecoveryController(RecoveryInputBoundary recovery, RecoveryPresenter presenter) {
        this.recovery = recovery;
        this.presenter = presenter;
    }

    public void preview(UUID incidentId, UUID actionId, Consumer<RecoveryViewState> show) {
        submit(() -> recovery.previewRecovery(incidentId, actionId), show);
    }

    public void confirm(RecoveryPreview preview, Consumer<RecoveryViewState> show) {
        submit(() -> recovery.executeRecovery(new RecoveryRequest(preview)), show);
    }

    public void beginManualVerification(UUID incidentId, Consumer<RecoveryViewState> show) {
        submit(() -> recovery.beginManualVerification(incidentId), show);
    }

    public void verify(UUID incidentId, Consumer<RecoveryViewState> show) {
        submit(() -> recovery.verifyResolution(incidentId), show);
    }

    public void history(UUID incidentId, Consumer<RecoveryViewState> show) {
        submit(() -> recovery.loadHistory(incidentId), show);
    }

    /** Stops UI submissions and returns the work whose recording must finish before shutdown. */
    public CompletionStage<Void> stopAccepting() {
        accepting = false;
        return active;
    }

    private void submit(Supplier<CompletionStage<Void>> task, Consumer<RecoveryViewState> show) {
        if (!accepting || busy) {
            return;
        }
        busy = true;
        presenter.beginRequest();
        try {
            active = task.get();
        } catch (RuntimeException failure) {
            active = CompletableFuture.failedFuture(failure);
        }
        presenter.publish(active, result -> {
            busy = false;
            if (accepting) {
                show.accept(result);
            }
        });
    }
}
