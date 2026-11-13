package io.github.lumiagg.gestureevo.ocr.coloros.privileged;

/**
 * Stage logger for the framework-only Shizuku helper. Logs go to stderr while stdout
 * carries result packets. Kept framework-only to avoid app runtime dependencies.
 */
public interface ColorOcrLog {
    void log(String stage, String message);
}
