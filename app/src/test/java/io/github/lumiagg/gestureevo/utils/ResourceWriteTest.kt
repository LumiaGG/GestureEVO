package io.github.lumiagg.gestureevo.utils

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ResourceWriteTest {
    @Test fun successfulWritePublishesOnlyAfterCompletion() {
        val events = mutableListOf<String>()
        val resource = writeCreatedResource(
            create = { events.add("created"); "image" },
            write = { events.add("written:$it") },
            publish = { events.add("published:$it") },
            rollback = { fail("Successful image must be retained") },
        )
        assertEquals("image", resource)
        assertEquals(listOf("created", "written:image", "published:image"), events)
    }

    @Test fun failedCreationDoesNotWriteOrPublish() {
        assertNull(writeCreatedResource<String>(
            create = { null },
            write = { fail("No resource to write") },
            publish = { fail("No resource to publish") },
            rollback = { fail("No resource to delete") },
        ))
    }

    @Test fun partialWriteFailureDeletesTheResourceAndPreservesTheCause() {
        val image = StringBuilder()
        val failure = IOException("destination full")
        var deleted = false
        try {
            writeCreatedResource(
                create = { image },
                write = { it.append("partial JPEG"); throw failure },
                publish = { fail("Partial data must not be published") },
                rollback = { assertSame(image, it); deleted = true },
            )
            fail("Expected write failure")
        } catch (actual: IOException) {
            assertSame(failure, actual)
        }
        assertTrue(deleted)
    }

    @Test fun publicationFailureAlsoRollsBack() {
        var deleted = false
        try {
            writeCreatedResource(
                create = { "image" }, write = {},
                publish = { throw IOException("provider update failed") },
                rollback = { deleted = true },
            )
            fail("Expected publish failure")
        } catch (_: IOException) {
            assertTrue(deleted)
        }
    }

    @Test fun cleanupFailureDoesNotHideTheWriteFailure() {
        val failure = IOException("write failed")
        val cleanup = SecurityException("delete denied")
        try {
            writeCreatedResource(
                create = { "image" }, write = { throw failure }, publish = {},
                rollback = { throw cleanup },
            )
            fail("Expected write failure")
        } catch (actual: IOException) {
            assertSame(failure, actual)
            assertArrayEquals(arrayOf(cleanup), actual.suppressed)
        }
    }
}
