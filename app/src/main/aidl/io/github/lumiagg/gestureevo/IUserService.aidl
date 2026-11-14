package io.github.lumiagg.gestureevo;

import io.github.lumiagg.gestureevo.utils.ExecuteResult;
import android.os.ParcelFileDescriptor;
import io.github.lumiagg.gestureevo.ITouchInterruptionGuard;

interface IUserService {
    // Shizuku sends its reserved destroy transaction to this method.
    void destroy() = 16777114;
    // Preserve the existing transaction IDs for ordinary client calls.
    void exit() = 1;
    ExecuteResult execute(in List<String> cmdarray, in List<String> envp, in String dir) = 2;
    ExecuteResult readDiagnosticLogs() = 3;
    ParcelFileDescriptor openDiagnosticStream(String token) = 4;
    void closeDiagnosticStream(String token) = 5;
    ParcelFileDescriptor openTouchStream(String token, IBinder owner) = 6;
    void closeTouchStream(String token) = 7;
    boolean interruptTouchForDrag(String token, String sourcePackage, float x, float y,
        int displayId, long deadlineUptimeMs, ITouchInterruptionGuard guard) = 8;
}
