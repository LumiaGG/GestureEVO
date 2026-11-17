// 来源：wangzhen9331 的 GestureEVO 自用改版（GPL-3.0-only），检测后端按本仓库架构适配。

package io.github.lumiagg.gestureevo.clipboard;

import android.os.Binder;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;

/**
 * Headless process that reports clipboard changes the app itself cannot see.
 *
 * Getting here took three measurements on the target device (HyperOS, Android 17):
 *   - the app's own OnPrimaryClipChangedListener never fires while it is in the background, even
 *     though the process is alive, unfrozen and holds the READ_CLIPBOARD app op;
 *   - reading the clipboard fails for root *and* for shell — only foreground apps, the default IME
 *     and (on stock builds) shell are allowed, and this ROM drops shell too;
 *   - registering the hidden `IClipboard.addPrimaryClipChangedListener` overload from a shell
 *     process *does* receive callbacks while in the background.
 *
 * That last one is the whole point of this class: run under `app_process` via su, register the
 * hidden overload, and print a line for every change. The app reads that stdout and then borrows
 * the foreground through [ClipboardReadActivity] to actually read the content.
 *
 * Started by [ClipboardDaemonHost]; keep this class free of Kotlin and AndroidX so the plain
 * app_process runtime can load it.
 */
public final class ClipboardDaemon {

    /** Attribution used when registering; matches the uid this process runs as. */
    private static final String SHELL_PACKAGE = "com.android.shell";

    private static final String TAG_READY = "READY";
    private static final String TAG_CHANGED = "CHANGED";

    /** Written on a timer purely so a broken pipe is noticed; the host ignores it. */
    private static final String TAG_PING = "PING";

    /** Long enough to be invisible, short enough that an orphan cleans itself up promptly. */
    private static final long PING_INTERVAL_MS = 20_000L;

    private static OutputStream out;

    private ClipboardDaemon() {
    }

    public static void main(String[] args) {
        out = new FileOutputStream(FileDescriptor.out);
        Looper.prepareMainLooper();
        try {
            Object clipboard = clipboardService();
            if (clipboard == null) {
                emit("ERROR no-clipboard-service");
                return;
            }
            Object listener = newChangeListener();
            Method register = null;
            for (Method method : clipboard.getClass().getMethods()) {
                if (method.getName().equals("addPrimaryClipChangedListener")
                        && method.getParameterCount() == 5) {
                    register = method;
                    break;
                }
            }
            if (register == null) {
                emit("ERROR no-register-method");
                return;
            }
            register.invoke(clipboard, listener, SHELL_PACKAGE, null, 0, 0);
            emit(TAG_READY);
        } catch (Throwable t) {
            emit("ERROR " + t.getClass().getSimpleName() + ":" + shortMessage(t));
            return;
        }
        startWatchdog();
        Looper.loop();
    }

    /**
     * Exists only to notice that the app side is gone.
     *
     * Killing the su parent does not necessarily take this process with it, so a daemon started
     * for an earlier session can outlive its reader and pile up. Writing to a closed pipe is the
     * only reliable signal available here — it fails as soon as the app dies — so a line goes out
     * on a timer and any write error ends the process.
     */
    private static void startWatchdog() {
        Thread watchdog = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(PING_INTERVAL_MS);
                } catch (InterruptedException e) {
                    return;
                }
                emit(TAG_PING);
            }
        }, "clipboard-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    private static Object clipboardService() throws Exception {
        Class<?> serviceManager = Class.forName("android.os.ServiceManager");
        IBinder binder =
                (IBinder) serviceManager.getMethod("getService", String.class)
                        .invoke(null, "clipboard");
        if (binder == null) return null;
        Class<?> stub = Class.forName("android.content.IClipboard$Stub");
        return stub.getMethod("asInterface", IBinder.class).invoke(null, binder);
    }

    /**
     * The system only keeps the binder half of this object, so a dynamic proxy is enough: it has
     * to answer asBinder(), and every callback then arrives on the binder's onTransact below.
     */
    private static Object newChangeListener() throws Exception {
        Class<?> listenerInterface =
                Class.forName("android.content.IOnPrimaryClipChangedListener");
        IBinder token = new Binder() {
            @Override
            protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
                emit(TAG_CHANGED);
                if (reply != null) reply.writeNoException();
                return true;
            }
        };
        return Proxy.newProxyInstance(
                listenerInterface.getClassLoader(),
                new Class<?>[]{listenerInterface},
                (proxy, method, methodArgs) ->
                        "asBinder".equals(method.getName()) ? token : null);
    }

    private static String shortMessage(Throwable t) {
        String message = t.getMessage();
        if (message == null) return "";
        int newline = message.indexOf('\n');
        return newline < 0 ? message : message.substring(0, newline);
    }

    /**
     * stdout is the channel back to the app. It is a pipe, not a tty, so nothing is line-buffered
     * for us; and because a closed pipe must end this process rather than be swallowed, the write
     * goes through the stream directly instead of a PrintStream, which ignores IO errors.
     */
    private static synchronized void emit(String line) {
        if (out == null) return;
        try {
            out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (IOException e) {
            // The reader is gone; there is nothing left to serve.
            System.exit(0);
        }
    }
}
