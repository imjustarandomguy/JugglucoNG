package tk.glucodata.glucosecomplication

import android.app.PendingIntent
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ColorRamp
import androidx.wear.watchface.complications.data.ComplicationText
import androidx.wear.watchface.complications.data.CountUpTimeReference
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.data.SmallImage
import androidx.wear.watchface.complications.data.SmallImageType
import androidx.wear.watchface.complications.data.TimeDifferenceComplicationText
import androidx.wear.watchface.complications.data.TimeDifferenceStyle
import tk.glucodata.Applic
import tk.glucodata.CurrentReadingHub
import tk.glucodata.DisplayedReading
import tk.glucodata.GlucoseRangeColors
import tk.glucodata.GlucoseRanges
import tk.glucodata.Notify
import tk.glucodata.R
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

internal object GlucoseComplicationData {
    /** The current reading as the complications draw it: only while a value is shown. */
    data class Reading(
        val value: Float,
        val text: String,
        val isMmol: Boolean,
        val timeMillis: Long,
        /** The arrow's rate in mg/dL per minute; NaN draws none (a stale reading has none). */
        val rate: Float,
        val index: Int,
        /** Drawn by the watch face under the value. */
        val sensorId: String? = null,
        /** The next reading is overdue: the value is drawn dimmed. */
        val stale: Boolean = false,
        /** The hub's revision it was drawn from, 0 for a sample. */
        val revision: Long = 0L,
        /**
         * When the newest reading was taken, 0 for a sample: a stale value's age counts
         * from it.
         */
        val lastReadingMillis: Long = 0L,
    )

    private data class Thresholds(
        val low: Float,
        val high: Float,
        val veryLow: Float,
        val veryHigh: Float,
    )

    fun tapAction(): PendingIntent = Notify.mkpending()

    /**
     * A complication request can be what started the process: it waits this long for the
     * hub's first reading rather than answer "no value" and be redrawn a moment later.
     */
    private const val FIRST_READING_WAIT_MS = 1_500L

    /** The watch's current reading ([CurrentReadingHub]); null while no value is shown. */
    fun currentReading(): Reading? = reading(CurrentReadingHub.awaitCurrent(FIRST_READING_WAIT_MS))

    /**
     * [sensor]'s reading under the same rules: the hub's when it is the sensor the watch
     * displays, else resolved for it (the multi-sensor complication's other sensors).
     */
    fun currentReading(sensor: String): Reading? {
        val shown = CurrentReadingHub.awaitCurrent(FIRST_READING_WAIT_MS)
        if (tk.glucodata.SensorIdentity.matches(sensor, shown.sensorId)) return reading(shown)
        return reading(runCatching { CurrentReadingHub.resolveFor(sensor) }.getOrNull() ?: return null)
    }

    /** What a complication draws of [shown]: its value while one is shown, the arrow while fresh. */
    fun reading(shown: DisplayedReading): Reading? {
        if (!shown.showsValue) return null
        return Reading(
            value = shown.value,
            text = shown.valueText,
            isMmol = shown.isMmol,
            timeMillis = shown.timeMillis,
            rate = shown.shownRate,
            index = shown.index,
            sensorId = shown.sensorId,
            stale = shown.isDimmed,
            revision = shown.revision,
            lastReadingMillis = shown.lastReadingMillis,
        )
    }

    fun previewReading(): Reading {
        currentReading()?.let { return it }
        val isMmol = Applic.unit == 1
        val valueText = if (isMmol) "5.6" else "101"
        return Reading(
            value = if (isMmol) 5.6f else 101.0f,
            text = valueText,
            isMmol = isMmol,
            timeMillis = System.currentTimeMillis(),
            rate = 1.0f,
            index = 0,
        )
    }

    /** The reading's own time, for the forms that show when it was taken. */
    fun readingTimeText(reading: Reading?): String? {
        val at = reading?.timeMillis?.takeIf { it > 0L } ?: return null
        return runCatching {
            android.text.format.DateFormat.getTimeFormat(Applic.app).format(java.util.Date(at))
        }.getOrNull()
    }

    fun shortValueData(
        reading: Reading?,
        contentDescription: String,
        tapAction: PendingIntent? = tapAction(),
        icon: Icon? = null,
        /** Shown above the value by faces that render a title. */
        title: String? = null,
    ): ShortTextComplicationData {
        val builder = ShortTextComplicationData.Builder(
            text = valueText(reading, SHORT_AGE_SEPARATOR),
            contentDescription = text(contentDescription),
        ).setTapAction(tapAction)
        title?.let { builder.setTitle(text(it)) }
        if (reading != null && icon != null) {
            builder.setSmallImage(SmallImage.Builder(icon, SmallImageType.PHOTO).build())
            builder.setMonochromaticImage(MonochromaticImage.Builder(icon).build())
        }
        return builder.build()
    }

    /**
     * The long form every provider offers.
     *
     * A slot lists only the providers that support a type it accepts, so a
     * provider missing LONG_TEXT simply does not appear in a long slot's picker
     * — which is why none of these showed up for the wide slot on the dial.
     */
    fun longTextData(
        reading: Reading?,
        contentDescription: String,
        tapAction: PendingIntent? = tapAction(),
        icon: Icon? = null,
        title: String? = null,
    ): LongTextComplicationData {
        val builder = LongTextComplicationData.Builder(
            text = valueText(reading, LONG_AGE_SEPARATOR),
            contentDescription = text(contentDescription),
        ).setTapAction(tapAction)
        title?.let { builder.setTitle(text(it)) }
        icon?.let {
            builder.setSmallImage(SmallImage.Builder(it, SmallImageType.PHOTO).build())
            builder.setMonochromaticImage(MonochromaticImage.Builder(it).build())
        }
        return builder.build()
    }

    fun rangedValueData(
        reading: Reading?,
        contentDescription: String,
        tapAction: PendingIntent? = tapAction(),
        icon: Icon? = null,
    ): RangedValueComplicationData {
        val builder = if (reading == null || !reading.value.isFinite() || reading.value <= 0.0f) {
            RangedValueComplicationData.Builder(
                value = 0.0f,
                min = 0.0f,
                max = 1.0f,
                contentDescription = text(noValueDescription()),
            ).setText(text(noValueText()))
        } else {
            val thresholds = thresholds(reading.isMmol)
            RangedValueComplicationData.Builder(
                value = reading.value.coerceIn(thresholds.veryLow, thresholds.veryHigh),
                min = thresholds.veryLow,
                max = thresholds.veryHigh,
                contentDescription = text(contentDescription),
            )
                .setText(valueText(reading, SHORT_AGE_SEPARATOR))
                .setColorRamp(
                    ColorRamp(
                        intArrayOf(
                            GlucoseRangeColors.veryLow(true),
                            GlucoseRangeColors.low(true),
                            GlucoseRangeColors.inRange(true),
                            GlucoseRangeColors.high(true),
                            GlucoseRangeColors.veryHigh(true),
                        ),
                        true,
                    ),
                )
        }
        if (reading != null && icon != null) {
            builder.setSmallImage(SmallImage.Builder(icon, SmallImageType.PHOTO).build())
            builder.setMonochromaticImage(MonochromaticImage.Builder(icon).build())
        }
        return builder.setTapAction(tapAction).build()
    }

    private fun thresholds(isMmol: Boolean): Thresholds {
        val defaultHigh = GlucoseRangeColors.defaultHigh(isMmol)
        val defaultVeryLow = GlucoseRangeColors.defaultVeryLow(isMmol)
        val defaultVeryHigh = GlucoseRangeColors.defaultVeryHigh(isMmol)
        // The phone's ranges, as every other watch surface cuts its bands.
        val ranges = GlucoseRanges.current(isMmol).orDefaults()
        val low = ranges.targetLow
        var high = ranges.targetHigh
        var veryLow = ranges.veryLow
        var veryHigh = ranges.veryHigh
        if (high <= low) {
            high = max(defaultHigh, low + 0.1f)
        }
        if (veryLow >= low) {
            veryLow = min(defaultVeryLow, low - 0.1f)
        }
        if (veryHigh <= high) {
            veryHigh = max(defaultVeryHigh, high + 0.1f)
        }
        if (veryHigh <= veryLow) {
            veryHigh = veryLow + 0.1f
        }
        return Thresholds(
            low = low,
            high = high,
            veryLow = veryLow,
            veryHigh = veryHigh,
        )
    }

    private fun text(value: String): ComplicationText =
        PlainComplicationText.Builder(text = value).build()

    /** What the text forms show for [reading]: its value, or the no-value mark every surface shows. */
    internal fun shownText(reading: Reading?): String = reading?.text ?: noValueText()

    /** Between a stale value and its age where room is short ("112·6m"), and where it is not. */
    private const val SHORT_AGE_SEPARATOR = "·"
    private const val LONG_AGE_SEPARATOR = " · "

    /**
     * The text of the text forms: [shownText], and once the reading is stale its age after
     * it ("112 · 6m"). A text form cannot dim the value, which then looked current until it
     * was taken down; the face counts the age itself, so the watch does not wake to keep it
     * right.
     */
    internal fun valueText(reading: Reading?, separator: String): ComplicationText {
        if (reading == null || !reading.stale || reading.lastReadingMillis <= 0L) {
            return text(shownText(reading))
        }
        return TimeDifferenceComplicationText.Builder(
            TimeDifferenceStyle.SHORT_SINGLE_UNIT,
            CountUpTimeReference(Instant.ofEpochMilli(reading.lastReadingMillis)),
        )
            .setText(reading.text + separator + "^1")
            .setMinimumTimeUnit(TimeUnit.MINUTES)
            .build()
    }

    private fun noValueText(): String = DisplayedReading.NO_VALUE_TEXT

    private fun noValueDescription(): String = Applic.app.getString(R.string.novalue)
}
