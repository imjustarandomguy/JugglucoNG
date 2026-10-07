package tk.glucodata.ui.journal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.data.journal.JournalEntrySource

class JournalTimeEditableTests {

    @Test
    fun aTreatmentFromNightscoutKeepsItsTime() {
        // Its edit goes back to the Nightscout document, and API v3 will not move a date.
        assertFalse(timeEditableFor(JournalEntrySource.NIGHTSCOUT))
    }

    @Test
    fun aNewEntryCanBeTimed() {
        assertTrue(timeEditableFor(null))
    }

    @Test
    fun everyOtherSourceCanBeRetimed() {
        JournalEntrySource.entries
            .filter { it != JournalEntrySource.NIGHTSCOUT }
            .forEach { source -> assertTrue("$source should be editable", timeEditableFor(source)) }
    }
}
