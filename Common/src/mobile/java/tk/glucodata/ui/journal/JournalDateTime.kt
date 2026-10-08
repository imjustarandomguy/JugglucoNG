package tk.glucodata.ui.journal

import java.util.Calendar
import java.util.TimeZone
import tk.glucodata.data.journal.JournalEntrySource

private val utcTimeZone: TimeZone = TimeZone.getTimeZone("UTC")

/**
 * Whether the editor lets the user move an entry's date and time; [source] is the entry's, null
 * for a new one. Not for a treatment received from Nightscout: an edit of it goes back to the
 * document it came from, and API v3 will not move a document's date, so a new time would come
 * back as it was at the next receive. API v1 would take it, but the rule does not depend on the
 * version, so the same treatment never behaves differently on another server.
 */
internal fun timeEditableFor(source: JournalEntrySource?): Boolean =
    source != JournalEntrySource.NIGHTSCOUT

internal fun journalTimestampToPickerUtcDateMillis(
    timestamp: Long,
    localTimeZone: TimeZone = TimeZone.getDefault()
): Long {
    val localDate = Calendar.getInstance(localTimeZone).apply { timeInMillis = timestamp }
    return Calendar.getInstance(utcTimeZone).apply {
        clear()
        set(
            localDate.get(Calendar.YEAR),
            localDate.get(Calendar.MONTH),
            localDate.get(Calendar.DAY_OF_MONTH)
        )
    }.timeInMillis
}

internal fun mergeJournalDate(
    currentTimestamp: Long,
    pickerUtcDateMillis: Long,
    localTimeZone: TimeZone = TimeZone.getDefault()
): Long {
    val current = Calendar.getInstance(localTimeZone).apply { timeInMillis = currentTimestamp }
    val selectedUtcDate = Calendar.getInstance(utcTimeZone).apply {
        timeInMillis = pickerUtcDateMillis
    }
    current.set(
        selectedUtcDate.get(Calendar.YEAR),
        selectedUtcDate.get(Calendar.MONTH),
        selectedUtcDate.get(Calendar.DAY_OF_MONTH)
    )
    return current.timeInMillis
}
