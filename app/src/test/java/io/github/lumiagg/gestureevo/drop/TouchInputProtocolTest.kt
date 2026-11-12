package io.github.lumiagg.gestureevo.drop

import org.junit.Assert.*
import org.junit.Test

class TouchInputProtocolTest {
    @Test fun discoversScreenWithoutAssumingAnEventNumberAndSkipsWrongAxes() {
        val inventory = """
            N: Name="touchpad"
            H: Handlers=event2
            B: ABS=3
            B: PROP=2

            N: Name="touchpanel"
            H: Handlers=mouse0 event17
            B: ABS=123
            B: PROP=2
        """.trimIndent()
        val ranges = """
            add device 1: /dev/input/event2
              ABS_MT_POSITION_X : value 0, min 0, max 100, fuzz 0
            add device 2: /dev/input/event17
              ABS_MT_SLOT : value 0, min 0, max 9, fuzz 0
              ABS_MT_TRACKING_ID : value 0, min 0, max 65535, fuzz 0
              ABS_MT_POSITION_X : value 0, min 10, max 1010, fuzz 0
              ABS_MT_POSITION_Y : value 0, min 20, max 2020, fuzz 0
        """.trimIndent()
        val device = checkNotNull(TouchInputProtocol.discover(inventory, ranges))
        assertEquals("/dev/input/event17", device.path)
        assertEquals(10, device.minX)
        assertEquals(2020, device.maxY)
        assertNull(TouchInputProtocol.discover("", ranges))
    }
    private val touchpanelRanges = """
        add device 1: /dev/input/event3
          name:     "touchpad"
          events:
            ABS (0003): ABS_MT_SLOT         : value 0, min 0, max 9, fuzz 0, flat 0, resolution 0
                        ABS_MT_POSITION_X  : value 0, min 0, max 1000, fuzz 0, flat 0, resolution 0
                        ABS_MT_POSITION_Y  : value 0, min 0, max 2000, fuzz 0, flat 0, resolution 0
                        ABS_MT_TRACKING_ID : value 0, min 0, max 65535, fuzz 0, flat 0, resolution 0
          input props:
            INPUT_PROP_POINTER
        add device 2: /dev/input/event19
          name:     "touchpanel"
          events:
            KEY (0001): BTN_TOUCH
            ABS (0003): ABS_MT_SLOT         : value 0, min 0, max 9, fuzz 0, flat 0, resolution 0
                        ABS_MT_POSITION_X  : value 0, min 0, max 19840, fuzz 0, flat 0, resolution 0
                        ABS_MT_POSITION_Y  : value 0, min 0, max 44352, fuzz 0, flat 0, resolution 0
                        ABS_MT_TRACKING_ID : value 0, min 0, max 65535, fuzz 0, flat 0, resolution 0
          input props:
            INPUT_PROP_DIRECT
    """.trimIndent()

    @Test fun discoversDirectTouchpanelWhenProcIsDeniedOrEmpty() {
        for (inventory in listOf("", "cat: /proc/bus/input/devices: Permission denied")) {
            assertEquals(TouchInputProtocol.Device("/dev/input/event19", 0, 19840, 0, 44352),
                TouchInputProtocol.discover(inventory, touchpanelRanges))
        }
    }
    @Test fun rejectsTouchpadsVirtualDevicesAndIncompleteMultitouch() {
        val screenOnly = touchpanelRanges.substring(touchpanelRanges.indexOf("add device 2:"))
        assertNull(TouchInputProtocol.discover("", screenOnly.replace("INPUT_PROP_DIRECT", "INPUT_PROP_POINTER")))
        assertNull(TouchInputProtocol.discover("", screenOnly.replace("touchpanel", "virtual-touchscreen")))
        assertNull(TouchInputProtocol.discover("", screenOnly.replace("touchpanel", "uinput-touchscreen")))
        for (axis in listOf("ABS_MT_SLOT", "ABS_MT_TRACKING_ID", "ABS_MT_POSITION_X", "ABS_MT_POSITION_Y")) {
            assertNull(TouchInputProtocol.discover("", screenOnly.lineSequence().filterNot { it.contains(axis) }.joinToString("\n")))
        }
        val falseInventory = "H: Handlers=event19\nB: PROP=2"
        assertNull(TouchInputProtocol.discover(falseInventory, screenOnly.replace("INPUT_PROP_DIRECT", "INPUT_PROP_POINTER")))
    }

    @Test fun parsesTimestampedGeteventAndSignedTrackingEnd() {
        assertArrayEquals(intArrayOf(3, 57, -1), TouchInputProtocol.event("[  5123.001002] 0003 0039 ffffffff"))
        assertArrayEquals(intArrayOf(0, 0, 0), TouchInputProtocol.event("[ 5123.002000] /dev/input/event17: 0000 0000 00000000"))
        assertNull(TouchInputProtocol.event("getevent: permission denied"))
        assertNull(TouchInputProtocol.event("0003 0039 fffffffff"))
    }
    @Test fun rejectsStaleOrMalformedInputTimes() {
        assertEquals(5123001L, TouchInputProtocol.eventTime("[ 5123.001002] 0000 0000 00000000", 5123050))
        assertNull(TouchInputProtocol.eventTime("[ 5120.001002] 0000 0000 00000000", 5123050))
        assertNull(TouchInputProtocol.eventTime("[ 5999.001002] 0000 0000 00000000", 5123050))
        assertNull(TouchInputProtocol.eventTime("[ 99999999999999999999999.01]", 5123050))
        assertNull(TouchInputProtocol.eventTime("input error", 5123050))
    }
}
