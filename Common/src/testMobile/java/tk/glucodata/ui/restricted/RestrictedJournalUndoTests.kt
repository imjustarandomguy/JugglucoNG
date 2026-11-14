package tk.glucodata.ui.restricted

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.RestrictedModeConfig
import tk.glucodata.RestrictedModeState
import tk.glucodata.data.journal.JournalEntryType

class RestrictedJournalUndoTests {
    private val restricted = RestrictedModeState(RestrictedModeConfig(enabled = true, allowJournal = true))
    private val unlocked = restricted.copy(unlocked = true)
    private val calibrating = RestrictedModeState(
        RestrictedModeConfig(enabled = true, allowJournal = true, allowCalibration = true)
    )
    private val insulin = listOf(JournalEntryType.INSULIN)
    private val bg = listOf(JournalEntryType.FINGERSTICK)

    @Test fun takingBackAnAddedEntryIsADeleteAndNeedsFullMode() {
        assertFalse(journalUndoAllowed(restricted, null, insulin) { false })
        assertFalse(journalUndoAllowed(calibrating, null, insulin) { false })
        assertTrue(journalUndoAllowed(unlocked, null, insulin) { false })
        assertTrue(journalUndoAllowed(RestrictedModeState(), null, insulin) { false })
    }

    @Test fun writingAnEditBackIsASave() {
        assertTrue(journalUndoAllowed(restricted, JournalEntryType.INSULIN, insulin) { true })
        // A blood-glucose entry that calibrates is a calibration point: it needs full mode, or
        // calibration allowed.
        assertFalse(journalUndoAllowed(restricted, JournalEntryType.FINGERSTICK, bg) { true })
        assertFalse(journalUndoAllowed(restricted, JournalEntryType.INSULIN, bg) { true })
        assertTrue(journalUndoAllowed(restricted, JournalEntryType.FINGERSTICK, bg) { false })
        assertTrue(journalUndoAllowed(calibrating, JournalEntryType.FINGERSTICK, bg) { true })
        assertTrue(journalUndoAllowed(unlocked, JournalEntryType.FINGERSTICK, bg) { true })
    }
}
