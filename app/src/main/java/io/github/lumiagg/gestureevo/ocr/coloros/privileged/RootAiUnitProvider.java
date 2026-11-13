package io.github.lumiagg.gestureevo.ocr.coloros.privileged;

import android.content.Context;
import android.content.pm.ProviderInfo;
import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Process;
import java.lang.reflect.Method;
import java.util.Arrays;

/**
 * Keeps one real external AIUnit provider alive for the lifetime of one root OCR request.
 * Moved verbatim from the debug probe; the helper process is not registered in AMS, so a
 * normal provider acquisition fails there and this external holder is required. The external
 * token owns the AMS reference; the SDK client close must not release an app connection.
 * Uses framework classes only.
 */
public final class RootAiUnitProvider implements AutoCloseable {
    public static final String AUTHORITY = "com.oplus.aiunit.authority.open";
    private final Object manager;
    private final Class<?> managerType;
    private final ColorOcrLog log;
    private final IBinder token = new Binder();
    private final int userId;
    private boolean acquired;

    public RootAiUnitProvider(Context context, ColorOcrLog log) throws Exception {
        if (Process.myUid() != 0) throw new SecurityException("External provider holder requires actual UID 0");
        this.log = log;
        managerType = Class.forName("android.app.IActivityManager");
        manager = Class.forName("android.app.ActivityManager").getDeclaredMethod("getService").invoke(null);
        userId = context.getApplicationInfo().uid / 100000;
    }

    public void install(Context context) throws Exception {
        Object holder = managerType.getMethod("getContentProviderExternal", String.class, int.class, IBinder.class, String.class)
                .invoke(manager, AUTHORITY, userId, token, "GestureEVO ColorOS OCR");
        if (holder == null) throw new IllegalStateException("AIUnit external Provider returned no holder");
        acquired = true;
        Object provider = holder.getClass().getField("provider").get(holder);
        ProviderInfo info = (ProviderInfo) holder.getClass().getField("info").get(holder);
        if (!(provider instanceof IInterface) || info == null || !"com.oplus.aiunit".equals(info.packageName)
                || info.authority == null || !Arrays.asList(info.authority.split(";")).contains(AUTHORITY)) {
            throw new IllegalStateException("AIUnit external Provider identity mismatch");
        }
        IBinder binder = ((IInterface) provider).asBinder();
        if (binder == null || !binder.isBinderAlive()
                || !"android.content.IContentProvider".equals(binder.getInterfaceDescriptor())) {
            throw new IllegalStateException("AIUnit external Provider Binder is unavailable or unexpected");
        }
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        Object thread = activityThread.getMethod("currentActivityThread").invoke(null);
        if (thread == null) throw new IllegalStateException("Root ActivityThread is unavailable");
        Method install = activityThread.getDeclaredMethod("installProvider", Context.class,
                Class.forName("android.app.ContentProviderHolder"), ProviderInfo.class,
                boolean.class, boolean.class, boolean.class);
        install.setAccessible(true);
        // The external token owns the AMS reference; SDK client close must not release an app connection.
        Object installed = install.invoke(thread, context, holder, info, false, true, true);
        if (installed == null) throw new IllegalStateException("Root Provider cache installation failed");
        log.log("Provider", "External AIUnit Provider installed for user=" + userId
                + ", actual UID=" + Process.myUid() + "; SDK reads real service responses");
    }

    @Override
    public void close() {
        if (acquired) {
            acquired = false;
            try {
                managerType.getMethod("removeContentProviderExternalAsUser", String.class, IBinder.class, int.class)
                        .invoke(manager, AUTHORITY, token, userId);
                log.log("Provider", "External reference released");
            } catch (Exception error) {
                log.log("Provider", "External reference release failed: " + error);
            }
        }
    }
}
