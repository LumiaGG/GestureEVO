package io.github.lumiagg.gestureevo.utils;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

public class ToastHelper {

    public static void showInUIThread(Context context, String text) {
        new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(context, text, Toast.LENGTH_SHORT).show());
    }

    public static void showInUIThread(Context context, int res) {
        new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(context, res, Toast.LENGTH_SHORT).show());
    }
}
