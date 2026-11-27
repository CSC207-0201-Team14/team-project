package interface_adapter.recover_service;

import entity.IncidentRecoveryRecord;
import use_case.recover_service.ManualRecoveryGuidance;
import use_case.recover_service.RecoveryAttemptResult;
import use_case.recover_service.RecoveryOutputBoundary;
import use_case.recover_service.RecoveryPreview;
import use_case.recover_service.ResolutionResult;
import use_case.recover_service.VerificationResult;

import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** Formats one in-flight request at a time and publishes its complete result on the UI executor. */
public final class RecoveryPresenter implements RecoveryOutputBoundary {
    private final Executor uiThread;
    private final StringBuilder text = new StringBuilder();
    private RecoveryPreview preview;

    public RecoveryPresenter(Executor uiThread) {
        this.uiThread = uiThread;
    }

    void beginRequest() {
        text.setLength(0);
        preview = null;
    }

    void publish(CompletionStage<Void> work, Consumer<RecoveryViewModel> listener) {
        work.whenComplete((ignored, failure) -> {
            if (failure != null) {
                presentError("Could not complete the recovery request. " + failure.getMessage());
            }
            RecoveryViewModel state = new RecoveryViewModel(text.toString(), preview);
            uiThread.execute(() -> listener.accept(state));
        });
    }

    @Override
    public void presentPreview(RecoveryPreview current) {
        append(current.getServiceName() + " (" + current.getContainerId() + "): " + current.getState());
        append(current.getTargetHealth().getExplanation());
        append(current.getDecision().getExplanation());
        current.getManualSteps().forEach(this::append);
        if (current.getDecision().isSupported()) {
            preview = current;
        }
    }

    @Override
    public void presentAttempt(RecoveryAttemptResult result) {
        append(result.getAttempt().getMessage());
    }

    @Override
    public void presentManualSteps(ManualRecoveryGuidance guidance) {
        append(guidance.getReason());
        guidance.getNextSteps().forEach(this::append);
    }

    @Override
    public void presentVerification(VerificationResult result) {
        append("Verifying since " + result.getVerificationStartedAt());
        result.getRemainingServices().forEach(service -> append("Service " + service.getServiceId()
                + " — " + service.getConsecutivePasses() + "/" + service.getRequiredPasses()
                + " passing checks. " + service.getExplanation()));
    }

    @Override
    public void presentResolved(ResolutionResult result) {
        append("Incident resolved. Fresh checks met the required recovery thresholds.");
    }

    @Override
    public void presentHistory(IncidentRecoveryRecord record) {
        if (record.getAttempts().isEmpty()) {
            append("No Docker recovery attempts recorded.");
        }
        record.getAttempts().forEach(attempt -> append(attempt.getCompletedAt() + " — " + attempt.getMessage()));
        record.getVerificationStartedAt().ifPresent(start -> append("Verification started " + start));
    }

    @Override
    public void presentError(String message) {
        append(message);
    }

    private void append(String line) {
        if (text.length() > 0) {
            text.append('\n');
        }
        text.append(line);
    }
}
