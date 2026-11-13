package io.github.lumiagg.gestureevo.ocr.coloros.privileged;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Process;
import android.os.SystemClock;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;

/**
 * Runs one ocr_ar inference through the vendor scanner SDK client inside the Shizuku helper
 * process. Symbol names (detector, action interface, error/status accessors) are bound to
 * scanner {@link ColorOcrSdk#VERIFIED_SCANNER_VERSION}; a client version bump fails closed
 * in {@link ColorOcrSdk#load}. Returns the raw result JSON, or null when the SDK produced
 * no JSON (callers decide how to treat that). Uses framework classes only.
 */
public final class ColorOcrInference {
    private ColorOcrInference() {
    }

    public static String run(Context context, ClassLoader loader, Bitmap bitmap, ColorOcrLog log) throws Exception {
        long clientStarted = SystemClock.elapsedRealtime();
        Class<?> detectorType = loader.loadClass("Y3.a");
        Object detector = detectorType.getConstructor(Context.class, String.class)
                .newInstance(context, ColorOcrSdk.DETECTOR_NAME);
        Object client = loader.loadClass("com.oplus.aiunit.ocr.client.OcrCompatClient")
                .getConstructor(Context.class, detectorType).newInstance(context, detector);
        try {
            log.log("SDK identity", "host package=" + context.getPackageName() + ", actual UID=" + Process.myUid()
                    + "; SDK uses host metadata but never changes Linux/Binder UID");
            Class<?> callbackType = loader.loadClass("com.oplus.aiunit.core.callback.a");
            Object callback = Proxy.newProxyInstance(loader, new Class<?>[]{callbackType}, (proxy, method, args) -> {
                if (method.getDeclaringClass() == Object.class) return objectMethod(proxy, method.getName(), args);
                log.log("SDK callback", method.getName() + ": " + Arrays.toString(args));
                return null;
            });
            invoke(client, "add", callback);
            log.log("Capability query", String.valueOf(invoke(client, "checkSupported")) + " (not inference success)");
            Class<?> actionType = loader.loadClass("u3.c$a");
            log.timing("client_init", clientStarted);
            final long actionStarted = SystemClock.elapsedRealtime();
            Object action = Proxy.newProxyInstance(loader, new Class<?>[]{actionType}, (proxy, method, args) -> {
                if (method.getDeclaringClass() == Object.class) return objectMethod(proxy, method.getName(), args);
                if (method.getName().equals("failure")) {
                    log.log("SDK failure", Arrays.toString(args));
                    return null;
                }
                if (!method.getName().equals("run")) throw new IllegalStateException("Unknown SDK action: " + method);
                log.timing("engine_ready", actionStarted);
                log.log("Engine", ColorOcrSdk.DETECTOR_NAME + " started; inference not yet confirmed");
                long transferStarted = SystemClock.elapsedRealtime();
                Object input = invoke(detector, "createInputSlot");
                Object output = invoke(detector, "createOutputSlot");
                Object inputCode = invoke(input, "setTargetBitmap", bitmap);
                log.timing("bitmap_transfer", transferStarted);
                log.log("Bitmap transfer", String.valueOf(inputCode));
                if (!Integer.valueOf(0).equals(inputCode)) throw new IllegalStateException("Bitmap transfer failed");
                long inferenceStarted = SystemClock.elapsedRealtime();
                Object result;
                try {
                    result = invoke(detector, "process", input, output);
                } finally {
                    log.timing("inference", inferenceStarted);
                }
                Object resultCode = invoke(result, "d");
                log.log("Inference status", result + " (" + resultCode + ")");
                long jsonStarted = SystemClock.elapsedRealtime();
                try {
                    return Integer.valueOf(0).equals(resultCode) ? invoke(output, "getJsonResult") : null;
                } finally {
                    log.timing("json_extract", jsonStarted);
                }
            });
            try {
                return (String) invoke(client, "runAction", action);
            } finally {
                log.timing("sdk_action", actionStarted);
            }
        } finally {
            long cleanupStarted = SystemClock.elapsedRealtime();
            try {
                invoke(client, "destroy");
                log.log("Cleanup", "SDK client destroyed");
            } catch (Exception error) {
                log.log("Cleanup", "SDK client destroy failed: " + error);
            } finally {
                log.timing("client_cleanup", cleanupStarted);
            }
        }
    }

    private static Object objectMethod(Object proxy, String name, Object[] args) {
        if (name.equals("hashCode")) return System.identityHashCode(proxy);
        if (name.equals("equals")) return proxy == args[0];
        return "GestureEVO ColorOS OCR";
    }

    private static Object invoke(Object target, String name, Object... args) throws Exception {
        for (Method method : target.getClass().getMethods()) {
            if (!method.getName().equals(name) || method.getParameterTypes().length != args.length) continue;
            boolean matches = true;
            for (int i = 0; i < args.length; i++) matches &= method.getParameterTypes()[i].isInstance(args[i]);
            if (matches) {
                try {
                    return method.invoke(target, args);
                } catch (InvocationTargetException error) {
                    throw new IllegalStateException("Vendor SDK " + name + " failed", error.getCause());
                }
            }
        }
        throw new NoSuchMethodException("Vendor SDK " + name + "/" + args.length);
    }
}
