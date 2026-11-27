package use_case.recover_service;

import java.util.UUID;
import java.util.concurrent.CompletionStage;

/**
 * UC4 entry points. Invoke off the Swing EDT. Calls are synchronous and read and save incidents,
 * so callers must serialize them with other incident writers (UC3's incident worker).
 * Every outcome, including errors, is reported through the output boundary.
 */
public interface RecoveryInputBoundary {
    /**
     * Step 1: shows the target container, its live state, the target service's health since its latest recorded
     * failure, and the operation they determine. Changes nothing.
     */
    CompletionStage<Void> previewRecovery(UUID incidentId, UUID actionId);

    /**
     * Steps 2–3: executes a confirmed, currently recommended action using the operation chosen from the
     * container's state and the target's health at execution time, and records the attempt. A successful attempt
     * starts verification. If the target has already recovered, nothing runs and no attempt is recorded.
     */
    CompletionStage<Void> executeRecovery(RecoveryRequest request);

    /** Extension 1a: starts verification after the developer performed the manual steps outside Sentinel. */
    CompletionStage<Void> beginManualVerification(UUID incidentId);

    /**
     * Steps 4–6: evaluates checks recorded since verification began and resolves the incident when every
     * required service satisfies its recovery threshold. Otherwise the incident stays unresolved.
     */
    CompletionStage<Void> verifyResolution(UUID incidentId);

    CompletionStage<Void> loadHistory(UUID incidentId);
}
