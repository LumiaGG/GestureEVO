package io.github.lumiagg.gestureevo.ocr.coloros.privileged;

import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * Hands the app-held AIUnit binder to the vendor SDK when it re-binds from inside the
 * Shizuku helper process. It does not fake the service or invent results: the binder is the real one
 * the app obtained through its own bindService, and the app keeps owning that binding.
 * Uses framework classes only so the helper can load it from the app APK classpath.
 */
public class ColorOcrHeldBinderContext extends ContextWrapper {
    private final IBinder binder;
    private final ColorOcrLog log;
    private final Set<ServiceConnection> connections = Collections.newSetFromMap(new IdentityHashMap<>());

    public ColorOcrHeldBinderContext(Context base, IBinder binder, ColorOcrLog log) {
        super(base);
        this.binder = binder;
        this.log = log;
    }

    @Override
    public Context getApplicationContext() {
        return this;
    }

    @Override
    public boolean bindService(Intent intent, ServiceConnection connection, int flags) {
        return connect(intent, connection, command -> new Handler(Looper.getMainLooper()).post(command));
    }

    @Override
    public boolean bindService(Intent intent, int flags, Executor executor, ServiceConnection connection) {
        return connect(intent, connection, executor);
    }

    private boolean connect(Intent intent, ServiceConnection connection, Executor executor) {
        if (!new ComponentName(ColorOcrAuthorize.SERVICE_PACKAGE, ColorOcrAuthorize.SERVICE_CLASS)
                .equals(intent.getComponent())) {
            throw new SecurityException("May only connect to the already-held AIUnit service");
        }
        log.log("binding", "SDK requested held AIUnit Binder; actual UID=" + Process.myUid());
        synchronized (connections) {
            connections.add(connection);
        }
        executor.execute(() -> {
            synchronized (connections) {
                if (!connections.contains(connection)) return;
            }
            connection.onServiceConnected(intent.getComponent(), binder);
        });
        return true;
    }

    @Override
    public void unbindService(ServiceConnection connection) {
        synchronized (connections) {
            connections.remove(connection);
        }
    }
}
