package tk.glucodata.widget

import android.content.Context
import tk.glucodata.Applic
import tk.glucodata.CurrentReadingHub
import tk.glucodata.DisplayTrendSource
import tk.glucodata.DisplayedReading
import tk.glucodata.GlucoseDelta
import tk.glucodata.GlucosePoint
import tk.glucodata.JournalIobAccess
import tk.glucodata.Natives
import tk.glucodata.NotificationHistorySource
import tk.glucodata.WidgetDisplaySource

/**
 * The reading a widget shows: the device's current reading as CurrentReadingHub
 * publishes it, so the widgets show the value, the arrow and the stale moment every
 * other surface shows.
 */
data class WidgetReading(
    /** The value, or [DisplayedReading.NO_VALUE_TEXT] once it is taken down. */
    val valueText: String,
    /** In the display unit; NaN without a value. */
    val value: Float,
    val timeMillis: Long,
    /** mg/dL per minute; NaN without an arrow (the reading is not current, or has none). */
    val rate: Float,
    val freshness: DisplayedReading.Freshness,
    /** The hub's revision of the reading. */
    val revision: Long = 0L,
) {
    /** The next reading is not yet overdue. */
    val isFresh: Boolean get() = freshness == DisplayedReading.Freshness.FRESH

    /** The value is shown, but the next reading is overdue: struck through, without arrow. */
    val isStale: Boolean get() = freshness == DisplayedReading.Freshness.STALE && hasValue

    val hasValue: Boolean get() = value.isFinite()

    companion object {
        /** What the widgets show of the hub's [reading]; null when there is no reading at all. */
        fun of(reading: DisplayedReading): WidgetReading? {
            if (reading.timeMillis <= 0L || reading.freshness == DisplayedReading.Freshness.NO_SENSOR) return null
            return WidgetReading(
                valueText = reading.shownText,
                value = if (reading.showsValue) reading.value else Float.NaN,
                timeMillis = reading.timeMillis,
                rate = reading.shownRate,
                freshness = reading.freshness,
                revision = reading.revision,
            )
        }
    }
}

/** One load of everything the placed widgets show, shared by all of them in a render pass. */
class WidgetData(
    val reading: WidgetReading?,
    /** Oldest first; covers the longest chart window asked for. */
    val history: List<GlucosePoint>,
    val isMmol: Boolean,
    val sensorSerial: String?,
    val viewMode: Int,
    val hasCalibration: Boolean,
    val deltaText: String?,
    /** Units, NaN when the journal has none. */
    val iobUnits: Float,
    val nowMillis: Long,
    // The range bands, in display units, for range colours.
    val targetLow: Float = Float.NaN,
    val targetHigh: Float = Float.NaN,
    val veryLow: Float = Float.NaN,
    val veryHigh: Float = Float.NaN,
    /** The settings the chart is drawn with, for its cache key. */
    val chartStyle: WidgetChartStyle = WidgetChartStyle(),
) {
    val isFresh: Boolean
        get() = reading?.isFresh == true

    fun historySince(startMillis: Long): List<GlucosePoint> {
        val first = history.indexOfFirst { it.timestamp >= startMillis }
        return if (first <= 0) {
            if (first == 0) history else emptyList()
        } else {
            history.subList(first, history.size)
        }
    }
}

object WidgetDataLoader {
    /** How long a render at process start waits for the hub's first publish. */
    private const val FIRST_PUBLISH_WAIT_MS = 2_000L

    /**
     * Reads the current reading, CurrentReadingHub's, and [historyWindowMillis] of
     * history (at least the trend window, which the change is computed from, as the
     * notification and dashboard do). Room is queried, so not on the main thread.
     */
    fun load(context: Context, historyWindowMillis: Long, withIob: Boolean): WidgetData {
        val now = System.currentTimeMillis()
        val shown = CurrentReadingHub.awaitCurrent(FIRST_PUBLISH_WAIT_MS)
        val serial = WidgetDisplaySource.resolveActiveSensorSerial(shown.sensorId)
        val window = maxOf(historyWindowMillis, DisplayTrendSource.TREND_WINDOW_MS)
        val isMmol = if (shown.showsValue) shown.isMmol else Applic.unit == 1
        val history = try {
            NotificationHistorySource.getDisplayHistory((now - window).coerceAtLeast(0L), isMmol, serial)
        } catch (_: Throwable) {
            emptyList()
        }
        val viewMode = if (shown.showsValue) shown.viewMode else WidgetDisplaySource.resolveViewMode(serial)
        val trendPoints = DisplayTrendSource.resolveTrendPoints(history, null, serial)
        val deltaInterval = GlucoseDelta.sanitizeIntervalMinutes(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt("delta_interval_minutes", GlucoseDelta.DEFAULT_INTERVAL_MINUTES)
        )
        val iob = if (withIob) {
            JournalIobAccess.snapshot(now)?.firstOrNull()?.takeIf { it.isFinite() } ?: Float.NaN
        } else {
            Float.NaN
        }
        val targetLow = threshold { Natives.targetlow() }
        val targetHigh = threshold { Natives.targethigh() }
        val veryLow = threshold { Natives.alarmverylow() }
        val veryHigh = threshold { Natives.alarmveryhigh() }
        return WidgetData(
            reading = WidgetReading.of(shown),
            history = history,
            isMmol = isMmol,
            sensorSerial = serial,
            viewMode = viewMode,
            hasCalibration = WidgetDisplaySource.hasCalibration(serial, viewMode),
            deltaText = WidgetDelta.text(trendPoints, rawMode = viewMode == 1 || viewMode == 3, deltaInterval, isMmol),
            iobUnits = iob,
            nowMillis = now,
            targetLow = targetLow,
            targetHigh = targetHigh,
            veryLow = veryLow,
            veryHigh = veryHigh,
            chartStyle = WidgetChartStyle.read(context, serial, targetLow, targetHigh, veryLow, veryHigh),
        )
    }

    private inline fun threshold(read: () -> Float): Float = try {
        read()
    } catch (_: Throwable) {
        Float.NaN
    }

    const val PREFS = "tk.glucodata_preferences"
}

/** The "Δ" readout, paired the way the notification pairs it (Notify.renderGlucoseNotification). */
object WidgetDelta {
    fun text(points: List<GlucosePoint>, rawMode: Boolean, intervalMinutes: Int, isMmol: Boolean): String? {
        if (points.size < 2) return null
        val newest = points.last()
        val minGap = GlucoseDelta.minGapMillis(intervalMinutes)
        // The tail can hold near-duplicates of one reading, so walk back to the first
        // point old enough for the window rather than taking the one before last.
        var previous: GlucosePoint? = null
        for (index in points.size - 2 downTo 0) {
            val point = points[index]
            if (laneValue(point, rawMode) > 0.1f && newest.timestamp - point.timestamp >= minGap) {
                previous = point
                break
            }
        }
        if (previous == null) return null
        val delta = GlucoseDelta.delta(
            newest.timestamp, laneValue(newest, rawMode),
            previous.timestamp, laneValue(previous, rawMode),
            intervalMinutes,
        )
        return GlucoseDelta.format(delta, isMmol).takeIf { it.isNotEmpty() }
    }

    private fun laneValue(point: GlucosePoint, rawMode: Boolean): Float =
        if (rawMode && point.rawValue > 0f) point.rawValue else point.value
}

/** A made-up reading and history for the settings preview when there is no current value. */
object WidgetSamples {
    private const val MGDL_PER_MMOL = 18.0182f
    private const val STEP_MILLIS = 5L * 60L * 1000L

    fun data(nowMillis: Long, isMmol: Boolean): WidgetData {
        val scale = if (isMmol) 1f / MGDL_PER_MMOL else 1f
        val newest = nowMillis - 60_000L
        val history = (0 until 72).map { index ->
            val mgdl = 125f + 40f * kotlin.math.sin((index - 60) / 9.0).toFloat()
            GlucosePoint(newest - (71 - index) * STEP_MILLIS, mgdl * scale, 0f)
        }
        val value = history.last().value
        val text = if (isMmol) String.format(java.util.Locale.getDefault(), "%.1f", value) else value.toInt().toString()
        return WidgetData(
            reading = WidgetReading(text, value, newest, rate = 0.8f, freshness = DisplayedReading.Freshness.FRESH),
            history = history,
            isMmol = isMmol,
            sensorSerial = null,
            viewMode = 0,
            hasCalibration = false,
            deltaText = GlucoseDelta.format(4f * scale, isMmol),
            iobUnits = 1.2f,
            nowMillis = nowMillis,
            targetLow = 70f * scale,
            targetHigh = 180f * scale,
            veryLow = 54f * scale,
            veryHigh = 250f * scale,
        )
    }
}
