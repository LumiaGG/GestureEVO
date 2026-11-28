package io.github.lumiagg.gestureevo.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class ActionDelayTest {
    @Test
    fun acceptsMillisecondsWithinRange() {
        assertEquals(0L, parseActionDelayMs("0"))
        assertEquals(300L, parseActionDelayMs("300"))
        assertEquals(305L, parseActionDelayMs("305"))
        assertEquals(2_000L, parseActionDelayMs("2000"))
    }

    @Test
    fun absentOrInvalidDelayExecutesImmediately() {
        listOf(null, "", "-1", "1.5", "2001", "9223372036854775808").forEach {
            assertEquals(0L, parseActionDelayMs(it))
        }
    }
}
