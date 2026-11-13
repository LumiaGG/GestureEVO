package io.github.lumiagg.gestureevo.ocr.coloros.privileged;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.os.Process;
import dalvik.system.PathClassLoader;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Loads the vendor OCR client SDK from the installed system scanner app. The obfuscated
 * symbols used by {@link ColorOcrInference} are bound to this exact scanner version, so any
 * other version must fail closed. Uses framework classes only.
 */
public final class ColorOcrSdk {
    public static final String SCANNER_PACKAGE = "com.coloros.ocrscanner";
    public static final String VERIFIED_SCANNER_VERSION = "16.3.15";
    public static final String DETECTOR_NAME = "ocr_ar";

    private ColorOcrSdk() {
    }

    public static ClassLoader load(Context context, ColorOcrLog log) throws Exception {
        PackageInfo scanner;
        try {
            scanner = context.getPackageManager().getPackageInfo(SCANNER_PACKAGE, 0);
        } catch (android.content.pm.PackageManager.NameNotFoundException error) {
            throw new PrivilegedOcrEntry.ScannerUnsupportedException(
                    "Scanner " + SCANNER_PACKAGE + " is not installed", error);
        }
        log.log("Scanner", scanner.versionName);
        if (!VERIFIED_SCANNER_VERSION.equals(scanner.versionName)) {
            throw new PrivilegedOcrEntry.ScannerUnsupportedException("Scanner SDK version not verified: "
                    + scanner.versionName + ", requires " + VERIFIED_SCANNER_VERSION);
        }
        ApplicationInfo app = scanner.applicationInfo;
        if (app == null || (app.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) == 0) {
            throw new PrivilegedOcrEntry.ScannerUnsupportedException("Scanner must be a system app");
        }
        String paths = app.sourceDir;
        if (app.splitSourceDirs != null) {
            paths += java.io.File.pathSeparator + String.join(java.io.File.pathSeparator, app.splitSourceDirs);
        }
        ClassLoader loader = new PathClassLoader(paths, app.nativeLibraryDir, ClassLoader.getSystemClassLoader().getParent());
        initializeSdkLogging(loader, context, log);
        return loader;
    }

    private static void initializeSdkLogging(ClassLoader loader, Context context, ColorOcrLog log) throws Exception {
        Class<?> logging = loader.loadClass("com.oplus.aiunit.core.utils.AILog");
        try {
            logging.getMethod("h", Context.class, boolean.class).invoke(null, context, false);
            log.log("SDK logging", "Log-switch initialization completed");
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (!isLogObserverPidFailure(cause, Process.myPid())) throw error;
            Field initialized = logging.getDeclaredField("f");
            if (initialized.getType() != boolean.class || !Modifier.isStatic(initialized.getModifiers())) {
                throw new IllegalStateException("Unverified Scanner logging initialization field", error);
            }
            initialized.setAccessible(true);
            // Scanner 16.3.15 sets this only after its optional log-switch observer is registered.
            initialized.setBoolean(null, true);
            log.log("SDK logging", "Standalone PID rejected only by log-switch observer; skipping that observer. "
                    + "AIUnit authorization and inference checks remain active.");
        }
    }

    /**
     * Matches only the log-switch observer rejection seen for standalone helper processes.
     * Everything else (authorization, provider, inference) must never be suppressed here.
     */
    public static boolean isLogObserverPidFailure(Throwable error, int pid) {
        return error instanceof SecurityException && error.getMessage() != null
                && error.getMessage().equals("Failed to find PID " + pid)
                && java.util.Arrays.stream(error.getStackTrace()).anyMatch(frame ->
                frame.getClassName().equals("android.content.ContentResolver")
                        && frame.getMethodName().equals("registerContentObserver"))
                && java.util.Arrays.stream(error.getStackTrace()).anyMatch(frame ->
                frame.getClassName().equals("com.oplus.aiunit.core.utils.AILog")
                        && frame.getMethodName().equals("n"));
    }
}
