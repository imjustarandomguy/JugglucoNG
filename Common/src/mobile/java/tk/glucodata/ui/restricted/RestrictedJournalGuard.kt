package tk.glucodata.ui.restricted

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import tk.glucodata.RestrictedMode
import tk.glucodata.RestrictedModeState
import tk.glucodata.data.calibration.CalibrationManager
import tk.glucodata.data.journal.JournalEntry
import tk.glucodata.data.journal.JournalEntryType

/**
 * The journal editor's save and delete on a restricted phone, shared by every place the
 * editor opens (dashboard, History, Journal).
 *
 * - Deleting an entry is deleting data: it needs full mode.
 * - With "calibrate from journal BG" on, a blood-glucose entry is a calibration point. Adding,
 *   changing or removing one needs full mode unless calibration is allowed. Background
 *   meter imports do not come through here and keep working.
 * - The Undo after a save is one of those: a delete of what it added, or a save of the entry
 *   it edited, as it was.
 *
 * Refused, the editor stays open with what was typed, and a short message says full mode is
 * in Settings: going there would throw the entry away.
 */
internal class RestrictedJournalGuard(private val needFullMode: (() -> Unit) -> Unit) {
    fun save(existing: JournalEntry?, savedTypes: Collection<JournalEntryType>, action: () -> Unit) {
        val state = RestrictedMode.current()
        val needsFullMode = state.locked && !state.canCalibrate &&
            journalChangeTouchesCalibration(existing?.type, savedTypes) &&
            CalibrationManager.shouldCalibrateFromJournal()
        if (needsFullMode) needFullMode(action) else action()
    }

    fun delete(action: () -> Unit) {
        if (RestrictedMode.current().canClearData) action() else needFullMode(action)
    }

    /**
     * Taking a save back (the Undo after "Saved"): deleting what it added, as [delete], or
     * writing an edited entry back as it was ([previous]), which is a save of it, as [save].
     */
    fun undo(previous: JournalEntry?, savedTypes: Collection<JournalEntryType>, action: () -> Unit) {
        if (canUndo(previous, savedTypes)) action() else needFullMode(action)
    }

    /** Whether [undo] goes through now: a restricted phone is not offered an Undo it refuses. */
    fun canUndo(previous: JournalEntry?, savedTypes: Collection<JournalEntryType>): Boolean =
        journalUndoAllowed(RestrictedMode.current(), previous?.type, savedTypes) {
            CalibrationManager.shouldCalibrateFromJournal()
        }
}

/**
 * Whether a save can be taken back on [state]. Deleting what it added ([previousType] null) is
 * deleting data. Writing an edited entry back is a save, refused only when it moves a
 * calibration point ([calibratesFromJournal]) that the restrictions keep.
 */
internal fun journalUndoAllowed(
    state: RestrictedModeState,
    previousType: JournalEntryType?,
    savedTypes: Collection<JournalEntryType>,
    calibratesFromJournal: () -> Boolean,
): Boolean = when {
    previousType == null -> state.canClearData
    !state.locked || state.canCalibrate -> true
    else -> !(journalChangeTouchesCalibration(previousType, savedTypes) && calibratesFromJournal())
}

/** Whether saving these entries over [existingType] can add, move or drop a calibration point. */
internal fun journalChangeTouchesCalibration(
    existingType: JournalEntryType?,
    savedTypes: Collection<JournalEntryType>,
): Boolean = existingType == JournalEntryType.FINGERSTICK || JournalEntryType.FINGERSTICK in savedTypes

@Composable
internal fun rememberRestrictedJournalGuard(): RestrictedJournalGuard {
    val guard = rememberFullModeGuard()
    return remember(guard) { RestrictedJournalGuard(guard) }
}
