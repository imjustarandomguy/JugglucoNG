package tk.glucodata.data.journal

import java.util.Calendar
import java.util.TimeZone

/**
 * When a long-acting insulin's reminder speaks up: the pure rules behind the "Tresiba due at
 * 21:00" notification. The alarms, the notification and the journal reads live in
 * tk.glucodata.journal.InsulinReminders; everything that decides lives here.
 *
 * A preset with reminder times (long-acting only, see [JournalInsulinDosing.reminderTimesFor])
 * is checked at each of its times T. The dose due at T counts as logged when the journal holds a
 * dose of that insulin from the middle of the gap back to the previous reminder time of the same
 * insulin, across midnight, onwards: with 08:00 and 20:00, the 20:00 dose may be logged from
 * 14:00; with one time a day, from 12 hours before it. Whoever logged it does not matter: typed
 * here, the watch, Nightscout, AAPS or Clone.
 */
object InsulinReminderPolicy {
    const val SNOOZE_MILLIS = 30L * 60_000L

    /**
     * A reminder whose alarm comes this much after its time is not shown: the clock moved, or the
     * phone was off. The next one is scheduled as usual.
     */
    const val MAX_LATENESS_MILLIS = 60L * 60_000L

    /** A dose logged a little ahead of the check (a clock off on another device) still counts. */
    const val FUTURE_TOLERANCE_MILLIS = 60L * 60_000L

    private const val MINUTES_PER_DAY = 24 * 60

    /** Whether [preset] has reminders that apply: long-acting, in use, with at least one time. */
    fun remindsFor(preset: JournalInsulinPreset): Boolean =
        !preset.countsTowardIob && !preset.isArchived && preset.reminderTimes.isNotEmpty()

    /**
     * How long before the reminder at [minuteOfDay] its window opens, in minutes: half the gap
     * back to the previous of [times], across midnight. One time a day gives 12 hours.
     */
    fun windowMinutesBefore(minuteOfDay: Int, times: List<Int>): Int {
        val sorted = JournalInsulinDosing.normalizeReminderTimes(times + minuteOfDay)
        val index = sorted.indexOf(minuteOfDay)
        val previous = if (index > 0) sorted[index - 1] else sorted.last() - MINUTES_PER_DAY
        return (minuteOfDay - previous) / 2
    }

    /** When the window of the reminder due at [reminderAtMillis] (its [minuteOfDay]) opens. */
    fun windowStart(reminderAtMillis: Long, minuteOfDay: Int, times: List<Int>): Long =
        reminderAtMillis - windowMinutesBefore(minuteOfDay, times) * 60_000L

    /**
     * Whether [entry] is a dose of [preset]: an insulin entry with an amount, either filed under
     * the preset or named after it. The name matters for a dose received from Nightscout or AAPS,
     * which is filed under whatever insulin the import guessed but keeps the insulin's name.
     */
    fun isDoseOf(entry: JournalEntry, preset: JournalInsulinPreset): Boolean {
        if (entry.type != JournalEntryType.INSULIN) return false
        val amount = entry.amount ?: return false
        if (!amount.isFinite() || amount <= 0f) return false
        if (entry.insulinPresetId == preset.id) return true
        val name = preset.displayName.trim()
        return name.isNotEmpty() && entry.title.trim().equals(name, ignoreCase = true)
    }

    /** Whether the journal holds a dose of [preset] between the two times, both included. */
    fun hasDoseBetween(
        entries: List<JournalEntry>,
        preset: JournalInsulinPreset,
        fromMillis: Long,
        untilMillis: Long
    ): Boolean = entries.any { it.timestamp in fromMillis..untilMillis && isDoseOf(it, preset) }

    /**
     * Whether the reminder of [preset] at [minuteOfDay], due at [reminderAtMillis], is to be
     * shown when checked at [checkAtMillis]: at its time, or when a snooze of it ends. A snoozed
     * reminder keeps the window of the time it is for, so a dose logged during the snooze counts.
     */
    fun shouldNotify(
        preset: JournalInsulinPreset,
        minuteOfDay: Int,
        reminderAtMillis: Long,
        checkAtMillis: Long,
        entries: List<JournalEntry>
    ): Boolean {
        if (!remindsFor(preset) || minuteOfDay !in preset.reminderTimes) return false
        return loggedDose(preset, minuteOfDay, reminderAtMillis, checkAtMillis, entries) == null
    }

    /**
     * The dose of [preset] that answers its reminder due at [reminderAtMillis], if one is logged
     * by [checkAtMillis]: the latest in the window. The notification's one-tap Log checks it first,
     * so a second tap, or a tap on a reminder already answered from the app, logs nothing twice.
     */
    fun loggedDose(
        preset: JournalInsulinPreset,
        minuteOfDay: Int,
        reminderAtMillis: Long,
        checkAtMillis: Long,
        entries: List<JournalEntry>
    ): JournalEntry? {
        val from = windowStart(reminderAtMillis, minuteOfDay, preset.reminderTimes)
        val until = checkAtMillis + FUTURE_TOLERANCE_MILLIS
        return entries
            .filter { it.timestamp in from..until && isDoseOf(it, preset) }
            .maxByOrNull { it.timestamp }
    }

    /** What a tap on the reminder's one-tap "Log 25 U" does. */
    sealed interface LogDecision {
        /** Log [dose], the amount the button showed. */
        data class Log(val dose: Float) : LogDecision

        /**
         * Log nothing: open the entry sheet to confirm, on [presetId] when that insulin is still
         * in use, with [dose] filled in when there is one to show.
         */
        data class Review(val presetId: Long?, val dose: Float?) : LogDecision
    }

    /**
     * The one-tap Log of a reminder posted for [labelledName] with the button "Log [labelledDose]",
     * tapped when the insulin is [preset] (null once deleted). The dose logged is always the one
     * the button showed, never the preset's default as it is now: a default changed meanwhile is
     * not the dose the user confirmed. When the insulin is no longer the one named (renamed,
     * archived, deleted), or the button's dose is unknown, nothing is logged and the user checks.
     */
    fun logDecision(labelledDose: Float?, labelledName: String?, preset: JournalInsulinPreset?): LogDecision {
        val dose = JournalInsulinDosing.sanitizeDefaultDose(labelledDose)
        val inUse = preset?.takeIf { !it.isArchived }
        val sameInsulin = inUse != null && labelledName != null &&
            inUse.displayName.trim() == labelledName.trim()
        return if (sameInsulin && dose != null) {
            LogDecision.Log(dose)
        } else {
            LogDecision.Review(inUse?.id, dose.takeIf { inUse != null })
        }
    }

    /** Whether an alarm meant for [scheduledForMillis] that fires at [nowMillis] still counts. */
    fun firesInTime(scheduledForMillis: Long, nowMillis: Long): Boolean =
        nowMillis - scheduledForMillis <= MAX_LATENESS_MILLIS

    /** When a snooze pressed at [nowMillis] ends. */
    fun snoozeUntil(nowMillis: Long): Long = nowMillis + SNOOZE_MILLIS

    /** How far back the reminder looks for the last dose it names: within 6 days a weekday is unambiguous. */
    const val LAST_DOSE_LOOKBACK_MILLIS = 6L * 24L * 60L * 60_000L

    /**
     * The dose of [preset] the reminder names as the last one: the latest at or before
     * [nowMillis], within [LAST_DOSE_LOOKBACK_MILLIS]; null when there is none.
     */
    fun lastDose(entries: List<JournalEntry>, preset: JournalInsulinPreset, nowMillis: Long): JournalEntry? =
        entries
            .filter { it.timestamp in (nowMillis - LAST_DOSE_LOOKBACK_MILLIS)..nowMillis && isDoseOf(it, preset) }
            .maxByOrNull { it.timestamp }

    /**
     * Calendar days from [thenMillis] to [nowMillis] in [timeZone]: 0 the same day, 1 yesterday.
     * Counted on dates, not 24-hour blocks, so 23:50 seen at 00:10 is yesterday.
     */
    fun daysAgo(thenMillis: Long, nowMillis: Long, timeZone: TimeZone = TimeZone.getDefault()): Int {
        fun dayNumber(millis: Long): Long = Math.floorDiv(millis + timeZone.getOffset(millis), 24L * 60L * 60_000L)
        return (dayNumber(nowMillis) - dayNumber(thenMillis)).toInt()
    }

    /**
     * The first time strictly after [afterMillis] the clock in [timeZone] reads [minuteOfDay].
     * A time the clock skips at a daylight-saving change comes at the first minute after the gap.
     */
    fun nextOccurrence(afterMillis: Long, minuteOfDay: Int, timeZone: TimeZone = TimeZone.getDefault()): Long {
        val safeMinute = minuteOfDay.coerceIn(0, MINUTES_PER_DAY - 1)
        val calendar = Calendar.getInstance(timeZone).apply { timeInMillis = afterMillis }
        repeat(3) {
            calendar.set(Calendar.HOUR_OF_DAY, safeMinute / 60)
            calendar.set(Calendar.MINUTE, safeMinute % 60)
            calendar.set(Calendar.SECOND, 0)
            calendar.set(Calendar.MILLISECOND, 0)
            if (calendar.timeInMillis > afterMillis) return calendar.timeInMillis
            calendar.add(Calendar.DAY_OF_YEAR, 1)
        }
        return calendar.timeInMillis
    }

    /** One reminder: a time of day of one insulin, and the key its alarm is filed under. */
    data class Slot(val presetId: Long, val minuteOfDay: Int) {
        val key: String get() = "$presetId:$minuteOfDay"

        companion object {
            fun fromKey(key: String): Slot? {
                val parts = key.split(':')
                if (parts.size != 2) return null
                val presetId = parts[0].toLongOrNull() ?: return null
                val minute = parts[1].toIntOrNull()?.takeIf { it in 0 until MINUTES_PER_DAY } ?: return null
                return Slot(presetId, minute)
            }
        }
    }

    /** Every reminder the presets ask for: one alarm each, at its next time. */
    fun slots(presets: List<JournalInsulinPreset>): List<Slot> =
        presets.filter(::remindsFor).flatMap { preset ->
            JournalInsulinDosing.normalizeReminderTimes(preset.reminderTimes).map { Slot(preset.id, it) }
        }
}
