package use_case.recover_service;

import java.util.UUID;

/**
 * UC4 entry points. Invoke off the Swing EDT. Calls are synchronous and read and save incidents,
 * so callers must serialize them with other incident writers (UC3's incident worker).
 * Every outcome, including errors, is reported through the output boundary.
 */
public interface RecoveryInputBoundary {
    /** Step 1: shows the target container, its live state and the operation it determines. Changes nothing. */
    void previewRecovery(UUID incidentId, UUID actionId);

    /**
     * Steps 2–3: executes a confirmed, currently recommended action using the operation chosen from the
     * container's state at execution time, and records the attempt. A successful attempt starts verification.
     */
    void executeRecovery(RecoveryRequest request);

    /** Extension 1a: starts verification after the developer performed the manual steps outside Sentinel. */
    void beginManualVerification(UUID incidentId);

    /**
     * Steps 4–6: evaluates checks recorded since verification began and resolves the incident when every
     * required service satisfies its recovery threshold. Otherwise the incident stays unresolved.
     */
    void verifyResolution(UUID incidentId);
}
