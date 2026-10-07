package tk.glucodata.data.journal

import java.util.Calendar
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * Per-insulin dosing settings kept in the insulin library: the pen's dial step, a default dose,
 * and, for long-acting insulin, the times of day to be reminded of it.
 */
object JournalInsulinDosing {
    /**
     * Whole units: what the common disposable pens (FlexTouch, KwikPen, SoloStar) dial in. Every
     * preset starts here, built-in or not; a half-unit pen is set to 0.5 in the library.
     */
    const val DEFAULT_STEP = 1f

    /** The steps the library offers: syringe tenths, half-unit pens, whole units, U200 pens. */
    val STEP_CHOICES: List<Float> = listOf(0.1f, 0.5f, 1f, 2f)

    private const val MAX_STEP = 10f
    private const val MINUTES_PER_DAY = 24 * 60

    /** Values closer than this to a grid point count as on it (float noise, typed decimals). */
    private const val GRID_TOLERANCE = 1e-3

    fun sanitizeStep(step: Float?): Float =
        step?.takeIf { it.isFinite() && it > 0f && it <= MAX_STEP } ?: DEFAULT_STEP

    /** A default dose is a positive amount or nothing; never 0, which would read as a dose. */
    fun sanitizeDefaultDose(dose: Float?): Float? = dose?.takeIf { it.isFinite() && it > 0f }

    /** [value] rounded to the nearest multiple of [step]. */
    fun roundToStep(value: Float, step: Float): Float {
        val safeStep = sanitizeStep(step).toDouble()
        return (Math.round(value / safeStep) * safeStep).toCleanFloat()
    }

    /**
     * What a -/+ press turns [current] into: the next multiple of [step] in [direction]. A value
     * already on the grid moves a whole step; one between two grid points moves to the neighbour
     * on that side (5.3 with a step of 1: + gives 6, - gives 5). Never below 0. An empty amount
     * counts as 0.
     */
    fun stepped(current: Float?, direction: Int, step: Float): Float {
        val safeStep = sanitizeStep(step).toDouble()
        val value = current?.takeIf { it.isFinite() }?.toDouble()?.coerceAtLeast(0.0) ?: 0.0
        if (direction == 0) return value.toCleanFloat()
        val position = value / safeStep
        val nearest = Math.round(position).toDouble()
        val onGrid = abs(position - nearest) < GRID_TOLERANCE
        val nextIndex = when {
            onGrid && direction > 0 -> nearest + 1
            onGrid -> nearest - 1
            direction > 0 -> ceil(position)
            else -> floor(position)
        }
        return (nextIndex * safeStep).coerceAtLeast(0.0).toCleanFloat()
    }

    /**
     * The amount once the user picks an insulin whose default dose is [selectedDefault]: that
     * default when the amount is empty or still holds [previousDefault], the default of the
     * insulin picked before; otherwise the amount as the user typed it. With no default on the
     * new insulin, an untouched previous default is cleared rather than carried over to a
     * different insulin (25 U of a basal must not become 25 U of a rapid insulin).
     */
    fun amountAfterPresetChange(
        amountText: String,
        previousDefault: Float?,
        selectedDefault: Float?,
        format: (Float) -> String
    ): String {
        val untouched = amountText.isBlank() || previousDefault?.let { previous ->
            amountText.parseAmountOrNull()?.let { abs(it - previous) < GRID_TOLERANCE } == true
        } == true
        if (!untouched) return amountText
        return sanitizeDefaultDose(selectedDefault)?.let(format).orEmpty()
    }

    /** Minutes after midnight, in range, without duplicates, earliest first. */
    fun normalizeReminderTimes(minutes: Collection<Int>): List<Int> =
        minutes.filter { it in 0 until MINUTES_PER_DAY }.distinct().sorted()

    /** The stored form: minutes after midnight, comma-separated ("1260" is 21:00). */
    fun encodeReminderTimes(minutes: Collection<Int>): String =
        normalizeReminderTimes(minutes).joinToString(",")

    fun decodeReminderTimes(stored: String?): List<Int> =
        normalizeReminderTimes(
            stored.orEmpty().split(',').mapNotNull { it.trim().toIntOrNull() }
        )

    /** Reminders belong to long-acting insulin only: a preset counted toward IOB keeps none. */
    fun reminderTimesFor(countsTowardIob: Boolean, minutes: Collection<Int>): List<Int> =
        if (countsTowardIob) emptyList() else normalizeReminderTimes(minutes)

    private fun String.parseAmountOrNull(): Float? = trim().replace(',', '.').toFloatOrNull()

    /** 0.1 * 3 as 0.3 rather than 0.30000000000000004. */
    private fun Double.toCleanFloat(): Float = ((this * 1_000.0).roundToLong() / 1_000.0).toFloat()
}

/** One logged amount, as the recent-values query reads it. */
data class JournalAmountRow(
    val amount: Float,
    val timestamp: Long,
    val insulinPresetId: Long?
)

/** Pure rules behind the entry sheet's quick-entry helpers. */
object JournalQuickEntryPolicy {
    const val RECENT_WINDOW_MILLIS = 7L * 24 * 60 * 60 * 1000
    const val RECENT_CHIP_COUNT = 6

    /**
     * The values offered as chips: the [limit] amounts used most often in the [windowMillis]
     * before [nowMillis], most used first, a tie going to the one used most recently. [rows] are
     * the whole journal's, whatever their source (typed here, the watch, Nightscout, AAPS, Clone);
     * for insulin only the rows of [insulinPresetId] count, and no insulin chosen means no chips.
     * Amounts that differ only by float noise count as one.
     */
    fun recentAmounts(
        rows: List<JournalAmountRow>,
        type: JournalEntryType,
        insulinPresetId: Long?,
        nowMillis: Long,
        windowMillis: Long = RECENT_WINDOW_MILLIS,
        limit: Int = RECENT_CHIP_COUNT
    ): List<Float> {
        if (type == JournalEntryType.INSULIN && insulinPresetId == null) return emptyList()
        val since = nowMillis - windowMillis
        return rows.asSequence()
            .filter { it.amount.isFinite() && it.amount > 0f }
            .filter { it.timestamp in since..nowMillis }
            .filter { type != JournalEntryType.INSULIN || it.insulinPresetId == insulinPresetId }
            .groupBy { (it.amount * 100f).roundToLong() }
            .map { (key, uses) ->
                RankedAmount(
                    amount = key / 100f,
                    uses = uses.size,
                    lastUsedAt = uses.maxOf { it.timestamp }
                )
            }
            .sortedWith(
                compareByDescending<RankedAmount> { it.uses }
                    .thenByDescending { it.lastUsedAt }
                    .thenBy { it.amount }
            )
            .take(limit)
            .map { it.amount }
            .toList()
    }

    private data class RankedAmount(val amount: Float, val uses: Int, val lastUsedAt: Long)

    /**
     * The latest dose of [presetId] at or before [nowMillis] while it is still active, that is
     * while less than the insulin's [durationMinutes] have passed; null otherwise. The entry
     * being edited ([excludeEntryId]) is not its own last dose.
     */
    fun lastActiveInsulin(
        entries: List<JournalEntry>,
        presetId: Long?,
        durationMinutes: Int,
        nowMillis: Long,
        excludeEntryId: Long? = null
    ): JournalEntry? {
        presetId ?: return null
        val last = entries.asSequence()
            .filter { it.type == JournalEntryType.INSULIN && it.insulinPresetId == presetId }
            .filter { it.id != excludeEntryId && it.isPositiveAmount() && it.timestamp <= nowMillis }
            .maxByOrNull { it.timestamp }
            ?: return null
        return last.takeIf { nowMillis - it.timestamp < durationMinutes.coerceAtLeast(0) * 60_000L }
    }

    /**
     * The latest meal at or before [nowMillis] while it is still being absorbed
     * ([mealAbsorptionMinutes]); null otherwise.
     */
    fun lastActiveMeal(
        entries: List<JournalEntry>,
        foodsById: Map<Long, JournalFood>,
        nowMillis: Long,
        excludeEntryId: Long? = null
    ): JournalEntry? {
        val last = entries.asSequence()
            .filter { it.type == JournalEntryType.CARBS }
            .filter { it.id != excludeEntryId && it.isPositiveAmount() && it.timestamp <= nowMillis }
            .maxByOrNull { it.timestamp }
            ?: return null
        val absorption = mealAbsorptionMinutes(last, last.foodId?.let(foodsById::get))
        return last.takeIf { nowMillis - it.timestamp < absorption * 60_000L }
    }

    /**
     * How long a meal is absorbed: the entry's own duration, else its library food's, else the
     * estimate the Nightscout upload sends (JournalTreatmentTransfer.defaultAbsorptionMinutes;
     * the same formula, kept in step by hand while that one is private).
     */
    fun mealAbsorptionMinutes(entry: JournalEntry, food: JournalFood?): Int =
        entry.durationMinutes?.takeIf { it > 0 }
            ?: food?.absorptionMinutes?.takeIf { it > 0 }
            ?: defaultAbsorptionMinutes(entry.amount ?: 0f, entry.proteinGrams, entry.fatGrams)

    internal fun defaultAbsorptionMinutes(grams: Float, protein: Float?, fat: Float?): Int {
        val macroExtra = ((protein ?: 0f) * 1.5f) + ((fat ?: 0f) * 2.5f)
        return (60f + grams * 2f + macroExtra).toInt().coerceIn(30, 360)
    }

    /**
     * Below this a past time reads as time ago, across midnight too: at 00:20, a dose at 23:50
     * is "30 min ago", not "yesterday 23:50".
     */
    const val RELATIVE_TIME_LIMIT_MILLIS = 12L * 60 * 60 * 1000

    /** How a past time reads in the context line. */
    sealed interface Elapsed {
        /** [RELATIVE_TIME_LIMIT_MILLIS] or more ago, on the calendar day before today: "yesterday 21:38". */
        data class Yesterday(val timestampMillis: Long) : Elapsed

        /**
         * Any other time, in whole minutes since: "2 h 10 min ago" under 12 hours, whatever the
         * day; earlier today; "2 d 3 h ago" before yesterday.
         */
        data class Ago(val minutes: Long) : Elapsed
    }

    fun elapsed(timestampMillis: Long, nowMillis: Long, timeZone: TimeZone = TimeZone.getDefault()): Elapsed {
        val sinceMillis = (nowMillis - timestampMillis).coerceAtLeast(0L)
        val yesterday = dayIndex(timestampMillis, timeZone) == dayIndex(nowMillis, timeZone) - 1
        return if (sinceMillis >= RELATIVE_TIME_LIMIT_MILLIS && yesterday) {
            Elapsed.Yesterday(timestampMillis)
        } else {
            Elapsed.Ago(sinceMillis / 60_000L)
        }
    }

    /** Whole days, hours and minutes in [minutes], for "2 d 3 h", "1 h 20 min", "5 min". */
    fun splitMinutes(minutes: Long): Triple<Long, Long, Long> {
        val safe = minutes.coerceAtLeast(0L)
        return Triple(safe / (24 * 60), (safe / 60) % 24, safe % 60)
    }

    private fun dayIndex(millis: Long, timeZone: TimeZone): Long {
        val calendar = Calendar.getInstance(timeZone).apply { timeInMillis = millis }
        val utcDay = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH))
        }
        return utcDay.timeInMillis / (24L * 60 * 60 * 1000)
    }

    /**
     * The write that puts an edited entry back as it was before the edit: the undo of an edit
     * is another edit, so it goes through the repository like any other and is uploaded or
     * synced the same way. A row mirrored from another system keeps its stored source and
     * identity (the repository preserves those); a row of this device's own gets its source and
     * record id back, which an edit in the sheet replaces with a manual one.
     */
    fun restoreInput(previous: JournalEntry): JournalEntryInput {
        val ownRow = !isExternalJournalMirrorSource(previous.source)
        return JournalEntryInput(
            id = previous.id,
            timestamp = previous.timestamp,
            sensorSerial = previous.sensorSerial,
            type = previous.type,
            title = previous.title,
            note = previous.note,
            amount = previous.amount,
            glucoseValueMgDl = previous.glucoseValueMgDl,
            durationMinutes = previous.durationMinutes,
            intensity = previous.intensity,
            insulinPresetId = previous.insulinPresetId,
            foodId = previous.foodId,
            proteinGrams = previous.proteinGrams,
            fatGrams = previous.fatGrams,
            source = if (ownRow) previous.source else JournalEntrySource.MANUAL,
            sourceRecordId = if (ownRow) previous.sourceRecordId else null,
            originSource = if (ownRow) previous.originSource else null
        )
    }

    private fun JournalEntry.isPositiveAmount(): Boolean = amount?.let { it.isFinite() && it > 0f } == true
}
