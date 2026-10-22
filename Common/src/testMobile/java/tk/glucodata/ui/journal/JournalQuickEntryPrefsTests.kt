package tk.glucodata.ui.journal

import org.junit.Assert.assertEquals
import org.junit.Test
import tk.glucodata.data.journal.JournalEntryType

class JournalQuickEntryPrefsTests {
    @Test
    fun theSheetOpensOnTheStoredType() {
        JournalEntryType.entries.forEach { type ->
            assertEquals(type, JournalQuickEntryPrefs.parseLastType(type.storageValue))
        }
    }

    @Test
    fun nothingStoredOpensOnInsulin() {
        assertEquals(JournalEntryType.INSULIN, JournalQuickEntryPrefs.parseLastType(null))
        // Not JournalEntryType.fromStorage's NOTE fallback: an unknown value is no reason to
        // open anywhere but the type logged most.
        assertEquals(JournalEntryType.INSULIN, JournalQuickEntryPrefs.parseLastType("bolus"))
    }
}
