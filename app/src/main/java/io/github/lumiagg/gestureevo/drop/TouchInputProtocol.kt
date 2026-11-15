package io.github.lumiagg.gestureevo.drop

/** Discovery and rotation mapping adapted from MMGG RootTouchSource/GestureMath (GPL-3.0). */
internal object TouchInputProtocol {
    data class Device(val path: String, val minX: Int, val maxX: Int, val minY: Int, val maxY: Int)
    fun discover(inventory: String, ranges: String): Device? {
        val inventoryDirect = inventory.split(Regex("(?:\\r?\\n){2,}")).filter {
            val properties = Regex("(?m)^B: PROP=([0-9a-fA-F]+)\\s*$").find(it)?.groupValues?.get(1)?.toLongOrNull(16)
            properties != null && properties and 2L != 0L
        }.mapNotNull { Regex("H: Handlers=.*?\\b(event\\d+)\\b").find(it)?.groupValues?.get(1) }.toSet()
        val headers = Regex("(?m)^add device \\d+: (/dev/input/(event\\d+))[ \\t]*\\r?$").findAll(ranges).toList()
        for ((index, header) in headers.withIndex()) {
            val path = header.groupValues[1]
            val block = ranges.substring(header.range.last + 1, headers.getOrNull(index + 1)?.range?.first ?: ranges.length)
            val name = Regex("name:\\s*\"([^\"]*)\"").find(block)?.groupValues?.get(1).orEmpty()
            if (Regex("virtual|uinput|touchpad", RegexOption.IGNORE_CASE).containsMatchIn(name)) continue
            val direct = Regex("\\bINPUT_PROP_DIRECT\\b").containsMatchIn(block)
            // /proc is optional; use it only when getevent omits the property section entirely.
            val propertiesReported = Regex("(?m)^\\s*input props:").containsMatchIn(block)
            if (!direct && (propertiesReported || header.groupValues[2] !in inventoryDirect)) continue
            fun axis(name: String) = Regex("$name\\s*:.*?min\\s+(-?\\d+),\\s*max\\s+(-?\\d+)").find(block)?.groupValues?.let { it[1].toInt() to it[2].toInt() }
            val x = axis("ABS_MT_POSITION_X") ?: continue
            val y = axis("ABS_MT_POSITION_Y") ?: continue
            if (x.second <= x.first || y.second <= y.first || !block.contains("ABS_MT_TRACKING_ID") || !block.contains("ABS_MT_SLOT")) continue
            return Device(path, x.first, x.second, y.first, y.second)
        }
        return null
    }
    fun eventTime(line: String, now: Long): Long? {
        val timestamp = Regex("\\[\\s*(\\d+)\\.(\\d+)\\]").find(line) ?: return null
        val seconds = timestamp.groupValues[1].toLongOrNull() ?: return null
        if (seconds > Long.MAX_VALUE / 1000) return null
        val time = seconds * 1000 + timestamp.groupValues[2].padEnd(3, '0').take(3).toLong()
        return time.takeIf { it >= now - 1000 && it <= now + 100 }
    }
    fun event(line: String): IntArray? {
        val fields = Regex("(?:^|\\s)([0-9a-fA-F]{4})\\s+([0-9a-fA-F]{4})\\s+([0-9a-fA-F]{8})\\s*$").find(line)?.groupValues ?: return null
        return intArrayOf(fields[1].toInt(16), fields[2].toInt(16), fields[3].toLong(16).toInt())
    }
    fun map(x: Float, y: Float, device: Device, width: Int, height: Int, rotation: Int): FloatArray {
        val nx = ((x - device.minX) / (device.maxX - device.minX)).coerceIn(0f, 1f)
        val ny = ((y - device.minY) / (device.maxY - device.minY)).coerceIn(0f, 1f)
        val w = (width - 1).coerceAtLeast(1).toFloat()
        val h = (height - 1).coerceAtLeast(1).toFloat()
        return when (rotation) {
            1 -> floatArrayOf(ny * w, (1 - nx) * h)
            2 -> floatArrayOf((1 - nx) * w, (1 - ny) * h)
            3 -> floatArrayOf((1 - ny) * w, nx * h)
            else -> floatArrayOf(nx * w, ny * h)
        }
    }
}
