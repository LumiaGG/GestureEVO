package io.github.lumiagg.gestureevo.ocr.coloros.privileged

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ColorOcrCommandTest {
    @Test
    fun quotesApkAndArgumentsWithoutShellExpansion() {
        assertEquals(
            "export CLASSPATH='/data/app/a'\\''b/`literal`\$value/base.apk'; " +
                "exec /system/bin/app_process /system/bin 'com.example.Entry' " +
                "'--req' '/cache/a'\\''b \$(id);.req'",
            ColorOcrCommand.appProcessCommand(
                "/data/app/a'b/`literal`\$value/base.apk", "com.example.Entry",
                "--req", "/cache/a'b \$(id);.req",
            ),
        )
        assertThrows(IllegalArgumentException::class.java) {
            ColorOcrCommand.appProcessCommand("/data/app/base.apk", "com.example.Entry", "a\u0000b")
        }
    }
}
