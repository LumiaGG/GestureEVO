package io.github.lumiagg.gestureevo.ocr.coloros.privileged;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Production root entry: one app_process run serves exactly one OCR request.
 *
 * Flow (all steps mirror the on-device verified experiment): UID 0 check → peekService of
 * the app-held AIUnit binding → handwritten first-round authorize → load the verified
 * scanner SDK → install the external AIUnit provider → decode the request PNG → ocr_ar
 * inference → write the result packet. The vendor SDK is never loaded inside the app
 * process. Uses framework classes only.
 */
public final class PrivilegedOcrEntry {
    public static final String REQUEST_DIR = "coloros_ocr";
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
        if (Process.myUid() != 0) {
            System.out.println("Root OCR helper refused: actual UID=" + Process.myUid());
            System.out.flush();
            System.exit(2);
            return;
        }
        ParsedArgs parsed;
        try {
            parsed = parseArgs(args);
        } catch (IllegalArgumentException error) {
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
            System.out.println("Root OCR helper exceeded " + WATCHDOG_SECONDS
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
            System.out.println("Root context initialization failed: " + error);
            System.out.flush();
            System.exit(1);
            return;
        }
        new Thread(() -> System.exit(new PrivilegedOcrEntry().run(systemContext, parsed)), "coloros-ocr-run").start();
        Looper.loop();
    }

    static ParsedArgs parseArgs(String[] args) {
        Map<String, String> values = new HashMap<>();
        for (int i = 0; i < args.length; i += 2) {
            if (i + 1 >= args.length) throw new IllegalArgumentException("Missing value for " + args[i]);
            if (!"--host".equals(args[i]) && !"--req".equals(args[i]) && !"--out".equals(args[i])) {
                throw new IllegalArgumentException("Unknown argument: " + args[i]);
            }
            if (values.put(args[i], args[i + 1]) != null) {
                throw new IllegalArgumentException("Duplicate argument: " + args[i]);
            }
        }
        String host = values.get("--host");
        String req = values.get("--req");
        String out = values.get("--out");
        if (host == null || host.isEmpty()) throw new IllegalArgumentException("Missing --host");
        if (req == null || out == null) throw new IllegalArgumentException("Missing --req/--out");
        return new ParsedArgs(host, req, out);
    }

    /** Structural path validation; both files must be a matching .req/.out pair inside the host's request dir. */
    static void validateRequestPaths(File hostCacheDir, ParsedArgs args) throws java.io.IOException {
        File requestDir = new File(hostCacheDir, REQUEST_DIR).getCanonicalFile();
        File req = new File(args.req).getCanonicalFile();
        File out = new File(args.out).getCanonicalFile();
        String reqName = req.getName();
        String outName = out.getName();
        if (!req.getPath().startsWith(requestDir.getPath() + File.separator) || !reqName.endsWith(".req")
                || !out.getPath().startsWith(requestDir.getPath() + File.separator) || !outName.endsWith(".out")
                || !outName.equals(reqName.substring(0, reqName.length() - ".req".length()) + ".out")) {
            throw new IllegalArgumentException("OCR request/result paths must be a matching .req/.out pair under "
                    + requestDir.getPath());
        }
    }

    private int run(Context systemContext, ParsedArgs parsed) {
        final Context hostContext;
        try {
            hostContext = systemContext.createPackageContext(parsed.host, 0);
            validateRequestPaths(hostContext.getCacheDir(), parsed);
        } catch (Exception error) {
            log.log("Failure", describe(error));
            return 1;
        }
        Bitmap bitmap = null;
        RootAiUnitProvider provider = null;
        try {
            if (Process.myUid() != 0) throw new SecurityException("OCR helper requires actual UID 0");
            int uid = Process.myUid();
            int pid = Process.myPid();
            String packageName = systemContext.getPackageManager().getNameForUid(uid);
            if (packageName == null || packageName.isEmpty()) packageName = "root";
            for (String permission : VENDOR_PERMISSIONS) {
                log.log("Permission", permission + "=" + systemContext.checkPermission(permission, pid, uid));
            }
            IBinder binder = ColorOcrAuthorize.peekHeldBinder(packageName);
            log.log("Binder", "descriptor=" + binder.getInterfaceDescriptor());
            ColorOcrAuthorize.AuthorizeResult authorize =
                    ColorOcrAuthorize.authorize(binder, uid, pid, packageName);
            log.log("Authorize", "code=" + authorize.code + ", ocs_switch=" + authorize.ocsSwitch);
            if (authorize.code != 0) {
                throw new AuthorizationDeniedException("authorize=" + authorize.code
                        + " (0=initial success, 701=SDK mismatch, 802=denied)");
            }
            log.log("Authorize", "initial authorization passed; continuing to image OCR");

            Context context = new ColorOcrHeldBinderContext(hostContext, binder, log);
            ClassLoader loader = ColorOcrSdk.load(context, log);
            provider = new RootAiUnitProvider(systemContext, log);
            provider.install(context);

            ColorOcrRequestPacket request;
            try (FileInputStream input = new FileInputStream(parsed.req)) {
                request = ColorOcrRequestPacket.read(input);
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            bitmap = BitmapFactory.decodeByteArray(request.png, 0, request.png.length, options);
            if (bitmap == null || bitmap.getWidth() != request.width || bitmap.getHeight() != request.height) {
                throw new IllegalArgumentException("Request PNG did not decode to the declared dimensions");
            }
            log.log("Image", "decoded " + bitmap.getWidth() + "x" + bitmap.getHeight()
                    + ", config=" + bitmap.getConfig());

            String json = ColorOcrInference.run(context, loader, bitmap, log);
            if (json == null || json.isEmpty()) {
                throw new IllegalStateException("Engine produced no OCR JSON; inspect helper stdout");
            }
            writeResult(parsed.out, new ColorOcrResultPacket(ColorOcrResultPacket.STATUS_OK, "", json));
            log.log("Done", "result packet written");
            return 0;
        } catch (IllegalArgumentException | java.io.IOException error) {
            writeResultQuietly(parsed.out, ColorOcrResultPacket.STATUS_REQUEST, describe(error));
            return 1;
        } catch (ScannerUnsupportedException error) {
            writeResultQuietly(parsed.out, ColorOcrResultPacket.STATUS_SCANNER, describe(error));
            return 1;
        } catch (AuthorizationDeniedException error) {
            writeResultQuietly(parsed.out, ColorOcrResultPacket.STATUS_AUTHORIZED, describe(error));
            return 1;
        } catch (Exception | LinkageError error) {
            writeResultQuietly(parsed.out, ColorOcrResultPacket.STATUS_ENGINE, describe(error));
            error.printStackTrace(System.out);
            System.out.flush();
            return 1;
        } finally {
            if (provider != null) provider.close();
            if (bitmap != null) bitmap.recycle();
        }
    }

    private void writeResult(String path, ColorOcrResultPacket packet) throws Exception {
        try (OutputStream output = new FileOutputStream(path)) {
            packet.write(output);
        }
    }

    private void writeResultQuietly(String path, int status, String message) {
        try {
            writeResult(path, new ColorOcrResultPacket(status, message, null));
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

    static final class ParsedArgs {
        final String host;
        final String req;
        final String out;

        ParsedArgs(String host, String req, String out) {
            this.host = host;
            this.req = req;
            this.out = out;
        }
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
