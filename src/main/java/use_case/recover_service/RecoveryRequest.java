package use_case.recover_service;

import java.util.Objects;
import java.util.UUID;

/** The exact preview accepted by the developer in the confirmation dialog. */
public final class RecoveryRequest {
    private final RecoveryPreview preview;

    public RecoveryRequest(RecoveryPreview preview) {
        this.preview = Objects.requireNonNull(preview, "preview");
        if (!preview.getDecision().isSupported()) {
            throw new IllegalArgumentException("Only an executable recovery preview can be confirmed");
        }
    }

    public RecoveryPreview getPreview() { return preview; }
    public UUID getIncidentId() { return preview.getAction().getIncidentId(); }
    public UUID getActionId() { return preview.getAction().getId(); }
}
