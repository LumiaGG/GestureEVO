package io.github.lumiagg.gestureevo.ocr.coloros.privileged

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import org.junit.Assert.assertThrows
import org.junit.Test

class ColorOcrPacketsTest {
    @Test
    fun requestPacketRejectsBadHeaderSizeAndTrailingData() {
        val png = byteArrayOf(1, 2, 3)
        assertThrows(IOException::class.java) { ColorOcrRequestPacket(0, 720, png) }
        assertThrows(IOException::class.java) { ColorOcrRequestPacket(1, 1, ByteArray(0)) }

        val output = ByteArrayOutputStream()
        ColorOcrRequestPacket(100, 100, png).write(output)
        val corrupt = output.toByteArray().also { it[0] = 0x00 }
        assertThrows(IOException::class.java) { ColorOcrRequestPacket.read(ByteArrayInputStream(corrupt)) }

        val trailing = output.toByteArray() + byteArrayOf(0x7F)
        assertThrows(IOException::class.java) { ColorOcrRequestPacket.read(ByteArrayInputStream(trailing)) }
    }

    @Test
    fun resultPacketRejectsUnknownStatusTruncationAndTrailingData() {
        assertThrows(IOException::class.java) { ColorOcrResultPacket(9, "x", null) }
        val output = ByteArrayOutputStream()
        ColorOcrResultPacket(ColorOcrResultPacket.STATUS_OK, "", "{\"items\":[]}").write(output)
        val bytes = output.toByteArray()
        assertThrows(IOException::class.java) {
            ColorOcrResultPacket.read(ByteArrayInputStream(bytes.copyOf(bytes.size - 1)))
        }
        assertThrows(IOException::class.java) {
            ColorOcrResultPacket.read(ByteArrayInputStream(bytes + byteArrayOf(0x00)))
        }
    }
}
