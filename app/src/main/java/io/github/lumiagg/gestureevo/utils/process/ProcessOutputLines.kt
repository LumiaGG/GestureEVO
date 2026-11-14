package io.github.lumiagg.gestureevo.utils.process

import java.io.InputStream

internal object ProcessOutputLines {
    /** Discard oversized lines while continuing to drain the stream. No unbounded readLine allocation. */
    fun read(input: InputStream, maxLength: Int = 8192, onDiscard: () -> Unit = {}, onLine: (String) -> Boolean) {
        val reader = input.reader(Charsets.UTF_8)
        val buffer = CharArray(2048)
        val line = StringBuilder()
        var oversized = false
        while (true) {
            val count = reader.read(buffer)
            if (count < 0) break
            for (index in 0 until count) {
                val char = buffer[index]
                if (char == '\n') {
                    if (oversized) onDiscard() else if (!onLine(line.toString().trimEnd('\r'))) return
                    line.setLength(0)
                    oversized = false
                } else if (!oversized) {
                    if (line.length >= maxLength) { oversized = true; line.setLength(0) } else line.append(char)
                }
            }
        }
        if (oversized) onDiscard() else if (line.isNotEmpty()) onLine(line.toString())
    }
}
