package io.github.lumiagg.gestureevo.ocr.coloros.privileged;

/**
 * Stage logger shared by the app-side client and the framework-only root helper.
 * The helper prints to stdout; the app forwards to its log module. Kept framework-only
 * so the root helper can call it without any app runtime dependency.
 */
public interface ColorOcrLog {
    void log(String stage, String message);
}
