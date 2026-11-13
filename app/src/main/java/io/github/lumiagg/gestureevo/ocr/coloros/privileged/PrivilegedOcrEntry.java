package io.github.lumiagg.gestureevo.ocr.coloros.privileged;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import java.io.FileOutputStream;
import java.io.FileDescriptor;

/**
 * One Shizuku-launched app_process serves exactly one OCR request over stdin/stdout.
 *
 * Flow: privileged UID check → peekService of
 * the app-held AIUnit binding → handwritten first-round authorize → load the verified
 * scanner SDK → install the external AIUnit provider → decode the request PNG → ocr_ar
 * inference → write the result packet. The vendor SDK is never loaded inside the app
 * process. Uses framework classes only.
 */
public final class PrivilegedOcrEntry {
    private static final int WATCHDOG_SECONDS = 60;
    private static final String[] VENDOR_PERMISSIONS = {
            "com.oppo.permission.safe.AUTHENTICATE", "com.oplus.permission.safe.AUTHENTICATE",
            "oppo.permission.OPPO_COMPONENT_SAFE", "oplus.permission.OPLUS_COMPONENT_SAFE",
    };

    private final long started = SystemClock.elapsedRealtime();
    private final ColorOcrLog log = new ColorOcrLog() {
        @Override
        public void log(String stage, String message) {
            System.out.println("[ColorOS OCR " + (SystemClock.elapsedRealtime() - started) + " ms] "
                    + stage + ": " + message);
            System.out.flush();
        }
    };

    public static void main(String[] args) {
        System.setOut(System.err);
        PrivilegedOcrEntry entry = new PrivilegedOcrEntry();
        entry.log.timing("main_enter", entry.started);
        final String host;
        try {
            host = parseHost(args);
            validateCaller(Process.myUid());
        } catch (IllegalArgumentException | SecurityException error) {
            System.out.println("Invalid OCR helper arguments: " + error.getMessage());
            System.out.flush();
            System.exit(2);
            return;
        }
        Looper.prepareMainLooper();
        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(WATCHDOG_SECONDS * 1000L);
            } catch (InterruptedException ignored) {
                return;
            }
            System.out.println("Shizuku OCR helper exceeded " + WATCHDOG_SECONDS
                    + "s; terminating itself. Completion is NOT confirmed.");
            System.out.flush();
            System.exit(3);
        }, "coloros-ocr-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
        final Context systemContext;
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object thread = activityThread.getMethod("systemMain").invoke(null);
            systemContext = (Context) activityThread.getMethod("getSystemContext").invoke(thread);
        } catch (Exception | LinkageError error) {
            System.out.println("Shizuku context initialization failed: " + error);
            System.out.flush();
            System.exit(1);
            return;
        }
        entry.log.timing("bootstrap", entry.started);
        new Thread(() -> System.exit(entry.run(systemContext, host)), "coloros-ocr-run").start();
        Looper.loop();
    }

    static String parseHost(String[] args) {
        if (args.length != 2 || !"--host".equals(args[0]) || args[1].isEmpty()) {
            throw new IllegalArgumentException("Expected --host <package>");
        }
        return args[1];
    }

    static void validateCaller(int uid) {
        if (uid != 0 && uid != 2000) {
            throw new SecurityException("OCR helper requires Shizuku Root or shell identity");
        }
    }

    private int run(Context systemContext, String host) {
        final Context hostContext;
        try {
            hostContext = systemContext.createPackageContext(host, 0);
        } catch (Exception error) {
            log.log("Failure", describe(error));
            return 1;
        }
        Bitmap bitmap = null;
        RootAiUnitProvider provider = null;
        try {
            long stageStarted = SystemClock.elapsedRealtime();
            validateCaller(Process.myUid());
            int uid = Process.myUid();
            int pid = Process.myPid();
            String packageName = systemContext.getPackageManager().getNameForUid(uid);
            if (packageName == null || packageName.isEmpty()) packageName = uid == 0 ? "root" : "com.android.shell";
            for (String permission : VENDOR_PERMISSIONS) {
                log.log("Permission", permission + "=" + systemContext.checkPermission(permission, pid, uid));
            }
            IBinder binder = ColorOcrAuthorize.peekHeldBinder(packageName);
            log.log("Binder", "descriptor=" + binder.getInterfaceDescriptor());
            ColorOcrAuthorize.AuthorizeResult authorize =
                    ColorOcrAuthorize.authorize(binder, uid, pid, packageName);
            log.timing("authorize", stageStarted);
            log.log("Authorize", "code=" + authorize.code + ", ocs_switch=" + authorize.ocsSwitch);
            if (authorize.code != 0) {
                throw new AuthorizationDeniedException("authorize=" + authorize.code
                        + " (0=initial success, 701=SDK mismatch, 802=denied)");
            }
            log.log("Authorize", "initial authorization passed; continuing to image OCR");

            Context context = new ColorOcrHeldBinderContext(hostContext, binder, log);
            stageStarted = SystemClock.elapsedRealtime();
            ClassLoader loader = ColorOcrSdk.load(context, log);
            log.timing("sdk_load", stageStarted);
            stageStarted = SystemClock.elapsedRealtime();
            provider = new RootAiUnitProvider(systemContext, log);
            provider.install(context);
            log.timing("provider_install", stageStarted);

            stageStarted = SystemClock.elapsedRealtime();
            ColorOcrRequestPacket request = ColorOcrRequestPacket.read(System.in);
            log.timing("request_read", stageStarted);
            stageStarted = SystemClock.elapsedRealtime();
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            bitmap = BitmapFactory.decodeByteArray(request.png, 0, request.png.length, options);
            if (bitmap == null || bitmap.getWidth() != request.width || bitmap.getHeight() != request.height) {
                throw new IllegalArgumentException("Request PNG did not decode to the declared dimensions");
            }
            log.timing("png_decode", stageStarted);
            log.log("Image", "decoded " + bitmap.getWidth() + "x" + bitmap.getHeight()
                    + ", config=" + bitmap.getConfig());

            String json = ColorOcrInference.run(context, loader, bitmap, log);
            if (json == null || json.isEmpty()) {
                throw new IllegalStateException("Engine produced no OCR JSON; inspect helper stdout");
            }
            writeResult(new ColorOcrResultPacket(ColorOcrResultPacket.STATUS_OK, "", json));
            log.log("Done", "result packet written");
            return 0;
        } catch (IllegalArgumentException | java.io.IOException error) {
            writeResultQuietly(ColorOcrResultPacket.STATUS_REQUEST, describe(error));
            return 1;
        } catch (ScannerUnsupportedException error) {
            writeResultQuietly(ColorOcrResultPacket.STATUS_SCANNER, describe(error));
            return 1;
        } catch (AuthorizationDeniedException error) {
            writeResultQuietly(ColorOcrResultPacket.STATUS_AUTHORIZED, describe(error));
            return 1;
        } catch (Exception | LinkageError error) {
            writeResultQuietly(ColorOcrResultPacket.STATUS_ENGINE, describe(error));
            error.printStackTrace(System.out);
            System.out.flush();
            return 1;
        } finally {
            long cleanupStarted = SystemClock.elapsedRealtime();
            if (provider != null) provider.close();
            if (bitmap != null) bitmap.recycle();
            log.timing("helper_cleanup", cleanupStarted);
            log.timing("helper_total", started);
        }
    }

    private void writeResult(ColorOcrResultPacket packet) throws Exception {
        long resultStarted = SystemClock.elapsedRealtime();
        try {
            // Keep stdout open until provider/bitmap cleanup and process exit are complete.
            packet.write(new FileOutputStream(FileDescriptor.out));
        } finally {
            log.timing("result_write", resultStarted);
        }
    }

    private void writeResultQuietly(int status, String message) {
        try {
            writeResult(new ColorOcrResultPacket(status, message, null));
        } catch (Exception failure) {
            log.log("Result", "writing failure packet failed: " + failure);
        }
        log.log("Failure", message);
    }

    private static String describe(Throwable error) {
        StringBuilder text = new StringBuilder();
        for (Throwable current = error; current != null && text.length() < 500; current = current.getCause()) {
            if (text.length() > 0) text.append(" ← ");
            text.append(current.getClass().getSimpleName()).append(": ").append(current.getMessage());
        }
        return text.toString();
    }

    static final class AuthorizationDeniedException extends RuntimeException {
        AuthorizationDeniedException(String message) {
            super(message);
        }
    }

    static final class ScannerUnsupportedException extends RuntimeException {
        ScannerUnsupportedException(String message) {
            super(message);
        }

        ScannerUnsupportedException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
