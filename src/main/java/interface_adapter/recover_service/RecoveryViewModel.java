package interface_adapter.recover_service;

import use_case.recover_service.RecoveryPreview;

/** One recovery request's display text and optional confirmable preview. */
public final class RecoveryViewModel {
    private final String text;
    private final RecoveryPreview preview;

    public RecoveryViewModel(String text, RecoveryPreview preview) {
        this.text = text;
        this.preview = preview;
    }

    public String text() { return text; }

    public RecoveryPreview preview() { return preview; }
}
