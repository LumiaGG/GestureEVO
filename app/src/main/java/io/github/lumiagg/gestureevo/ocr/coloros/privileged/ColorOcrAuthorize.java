package io.github.lumiagg.gestureevo.ocr.coloros.privileged;

import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * Handwritten AIUnit first-round authorization, kept byte-for-byte compatible with the
 * transaction layout verified on device (OnePlus PJA110 / Android 16). Uses framework
 * classes only.
 */
public final class ColorOcrAuthorize {
    public static final String SERVICE_ACTION = "oplus.intent.action.AIUNIT_SERVICE";
    public static final String SERVICE_PACKAGE = "com.oplus.aiunit";
    public static final String SERVICE_CLASS = "com.oplus.aiunit.core.AIUnitService";
    public static final String DESCRIPTOR = "com.oplus.aiunit.core.IService";
    public static final int AUTHORIZE_TRANSACTION = 2;

    private ColorOcrAuthorize() {
    }

    /** Identical filter identity is required for the app binding and the helper's peekService. */
    public static Intent aiUnitServiceIntent() {
        return new Intent(SERVICE_ACTION).setComponent(new ComponentName(SERVICE_PACKAGE, SERVICE_CLASS));
    }

    /**
     * Returns the AIUnit binder held by the caller app's live binding. The caller must be the
     * privileged process that issued this transaction; its Linux/Binder UID is never changed.
     */
    public static IBinder peekHeldBinder(String callingPackage) throws Exception {
        Intent intent = aiUnitServiceIntent();
        Object manager = Class.forName("android.app.ActivityManager").getDeclaredMethod("getService").invoke(null);
        Method peek = Class.forName("android.app.IActivityManager").getMethod("peekService",
                Intent.class, String.class, String.class);
        IBinder binder = (IBinder) peek.invoke(manager, intent, intent.getType(), callingPackage);
        if (binder == null) {
            throw new IllegalStateException("AIUnit returned no cached Binder; app binding must remain alive");
        }
        String descriptor = binder.getInterfaceDescriptor();
        if (!DESCRIPTOR.equals(descriptor)) {
            throw new IllegalStateException("Unsupported AIUnit interface: " + descriptor);
        }
        return binder;
    }

    /** First-round handwritten authorize. Throws on protocol violations; returns the vendor result code. */
    public static AuthorizeResult authorize(IBinder binder, int uid, int pid, String packageName) throws Exception {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            Map<String, Object> params = new HashMap<>();
            params.put("package::package_name", packageName);
            params.put("package::sdk_version", "209");
            params.put("package::auth_style", "0");
            params.put("package::client_uid", Integer.toString(uid));
            params.put("package::client_pid", Integer.toString(pid));
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeInt(1);
            data.writeMap(params);
            data.writeBundle(new Bundle());
            if (!binder.transact(AUTHORIZE_TRANSACTION, data, reply, 0)) {
                throw new IllegalStateException("Unsupported authorize transaction");
            }
            reply.readException();
            int code = reply.readInt();
            Object ocsSwitch = null;
            int present = reply.readInt();
            if (present == 1) {
                Map<?, ?> returned = reply.readHashMap(null);
                reply.readBundle(ColorOcrAuthorize.class.getClassLoader());
                ocsSwitch = returned == null ? null : returned.get("package::aiunit_ocs_switch");
            } else if (present != 0) {
                throw new IllegalStateException("Unknown ParamPackage presence: " + present);
            }
            if (reply.dataAvail() != 0) {
                throw new IllegalStateException("Unknown authorize trailing data");
            }
            return new AuthorizeResult(code, ocsSwitch);
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    public static final class AuthorizeResult {
        /** 0 = initial success, 701 = SDK mismatch, 802 = denied. */
        public final int code;
        public final Object ocsSwitch;

        AuthorizeResult(int code, Object ocsSwitch) {
            this.code = code;
            this.ocsSwitch = ocsSwitch;
        }
    }
}
