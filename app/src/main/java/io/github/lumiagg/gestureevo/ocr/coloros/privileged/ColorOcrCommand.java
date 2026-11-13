package io.github.lumiagg.gestureevo.ocr.coloros.privileged;

/**
 * Builds the {@code su -c} command that launches a framework-only entry class inside a root
 * app_process, using the installed APK as CLASSPATH. Quotes every shell argument and rejects
 * control characters at the shell argument boundary.
 */
public final class ColorOcrCommand {
    private ColorOcrCommand() {
    }

    public static String appProcessCommand(String apkPath, String entryClass, String... args) {
        if (apkPath == null || !apkPath.startsWith("/")) throw new IllegalArgumentException("apkPath must be absolute");
        requireShellSafe(apkPath, "apkPath");
        requireShellSafe(entryClass, "entryClass");
        StringBuilder command = new StringBuilder("export CLASSPATH='")
                .append(escapeSingleQuotes(apkPath))
                .append("'; exec /system/bin/app_process /system/bin '")
                .append(escapeSingleQuotes(entryClass)).append('\'');
        for (String arg : args) {
            requireShellSafe(arg, "argument");
            command.append(" '").append(escapeSingleQuotes(arg)).append('\'');
        }
        return command.toString();
    }

    private static void requireShellSafe(String value, String name) {
        if (value == null || value.isEmpty()) throw new IllegalArgumentException(name + " must not be empty");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\n' || c == '\r' || c == '\u0000') {
                throw new IllegalArgumentException(name + " contains control characters");
            }
        }
    }

    private static String escapeSingleQuotes(String value) {
        return value.replace("'", "'\\''");
    }
}
