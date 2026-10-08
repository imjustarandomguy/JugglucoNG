package tk.glucodata

import android.content.Context
import android.text.format.DateUtils
import java.text.DateFormat

/** The words for a [DirectReadingView], shared by the phone's screens and the watch's. */
object DirectReadingText {
    /** The sensor card's status, or null when neither device reads it: that keeps its old status. */
    @JvmStatic
    fun summary(context: Context, summary: DirectReadingSummary): String? = when (summary) {
        DirectReadingSummary.PHONE_AND_WATCH -> context.getString(R.string.direct_status_phone_and_watch)
        DirectReadingSummary.PHONE -> context.getString(R.string.direct_status_phone)
        DirectReadingSummary.WATCH -> context.getString(R.string.direct_status_watch)
        DirectReadingSummary.NEITHER -> null
    }

    /**
     * The phone's line. [compact] leaves out where the values come from instead,
     * for the watch's screen.
     */
    @JvmStatic
    @JvmOverloads
    fun phone(context: Context, view: DirectReadingView, compact: Boolean = false): String =
        text(context, view.phoneLine, view.nowMs, compact, PHONE)

    /** The watch's line; see [phone]. */
    @JvmStatic
    @JvmOverloads
    fun watch(context: Context, view: DirectReadingView, compact: Boolean = false): String =
        text(context, view.watchLine, view.nowMs, compact, WATCH)

    private class Words(
        val reading: Int,
        val notReading: Int,
        val notReadingSince: Int,
        val fromOther: Int,
        val fromOtherSince: Int,
        val unknown: Int,
    )

    private val PHONE = Words(
        reading = R.string.direct_phone_reading,
        notReading = R.string.direct_phone_not_reading,
        notReadingSince = R.string.direct_phone_not_reading_since,
        fromOther = R.string.direct_phone_from_watch,
        fromOtherSince = R.string.direct_phone_from_watch_since,
        unknown = R.string.direct_phone_unknown,
    )

    private val WATCH = Words(
        reading = R.string.direct_watch_reading,
        notReading = R.string.direct_watch_not_reading,
        notReadingSince = R.string.direct_watch_not_reading_since,
        fromOther = R.string.direct_watch_from_phone,
        fromOtherSince = R.string.direct_watch_from_phone_since,
        unknown = R.string.direct_watch_unknown,
    )

    private fun text(
        context: Context,
        line: DirectReadingLine,
        nowMs: Long,
        compact: Boolean,
        words: Words,
    ): String {
        val timed = line.lastMs > 0L
        return when (line.kind) {
            DirectReadingLineKind.UNKNOWN -> context.getString(words.unknown)
            DirectReadingLineKind.READING -> context.getString(words.reading, ago(line.lastMs, nowMs))
            DirectReadingLineKind.FROM_OTHER -> when {
                compact && timed -> context.getString(words.notReadingSince, since(line.lastMs, nowMs))
                compact -> context.getString(words.notReading)
                timed -> context.getString(words.fromOtherSince, since(line.lastMs, nowMs))
                else -> context.getString(words.fromOther)
            }
            DirectReadingLineKind.NOT_READING ->
                if (timed) context.getString(words.notReadingSince, since(line.lastMs, nowMs))
                else context.getString(words.notReading)
        }
    }

    /** "5 min. ago". A reading stamped a moment ahead of this clock is not "in 0 minutes". */
    private fun ago(atMs: Long, nowMs: Long): String =
        DateUtils.getRelativeTimeSpanString(
            minOf(atMs, nowMs),
            nowMs,
            DateUtils.MINUTE_IN_MILLIS,
            DateUtils.FORMAT_ABBREV_RELATIVE,
        ).toString()

    /** The time today, the date before that. */
    private fun since(atMs: Long, nowMs: Long): String =
        DateUtils.formatSameDayTime(atMs, nowMs, DateFormat.SHORT, DateFormat.SHORT).toString()
}
