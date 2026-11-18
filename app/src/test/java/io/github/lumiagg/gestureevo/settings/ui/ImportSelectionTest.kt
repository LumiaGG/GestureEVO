package io.github.lumiagg.gestureevo.settings.ui

import org.junit.Assert.*
import org.junit.Test

class ImportSelectionTest {
    private fun option(value: String, selected: Boolean = false, status: String = "") =
        ImportOption(value, "Same label", "App", "app.pkg", status = status, initiallySelected = selected)

    @Test fun callerDefaultsAreKeptButMarkedItemsRemainManuallySelectable() {
        val selection = ImportSelection(listOf(option("new", true), option("existing", status = "Exists"),
            option("missing", status = "Not installed")))
        assertEquals(listOf("new"), selection.values)
        assertTrue(selection.toggle(2))
        assertTrue(selection.toggle(1))
        assertEquals(listOf("new", "existing", "missing"), selection.values)
        assertEquals(3, selection.count)
    }

    @Test fun bulkSelectionReportsOnlyChangedItemsAndKeepsSameNameItemsSeparate() {
        val selection = ImportSelection(listOf(option("user-0/id", true), option("user-10/id")))
        assertEquals(listOf(1), selection.setAll(true))
        assertTrue(selection.allSelected)
        assertEquals(listOf("user-0/id", "user-10/id"), selection.values)
        assertEquals(emptyList<Int>(), selection.setAll(true))
        assertEquals(listOf(0, 1), selection.setAll(false))
        assertEquals(0, selection.count)
        assertFalse(selection.allSelected)
    }

    @Test fun acceptedValuesAreASnapshotIndependentOfLaterSelectionChanges() {
        val options = mutableListOf(option("first", true), option("second"))
        val selection = ImportSelection(options)
        options.clear()
        val accepted = selection.values
        selection.setAll(true)
        assertEquals(listOf("first"), accepted)
        assertEquals(listOf("first", "second"), selection.values)
    }

    @Test fun emptyOffersHaveNoSelectionAndCannotBeAllSelected() {
        val selection = ImportSelection<String>(emptyList())
        assertEquals(0, selection.count)
        assertFalse(selection.allSelected)
        assertTrue(selection.setAll(true).isEmpty())
        assertTrue(selection.values.isEmpty())
    }
}
