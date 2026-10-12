package tk.glucodata.widget

import android.content.Context
import tk.glucodata.CalibrationAccess
import tk.glucodata.DataSmoothing
import tk.glucodata.GlucosePoint
import tk.glucodata.GlucoseRangeColors
import tk.glucodata.SensorVisuals

/**
 * Everything a widget chart is drawn from: its arguments to the chart drawer and
 * the settings the drawer reads ([style]). Two renders with equal keys draw the
 * same picture, so the second reuses the first's bitmap: a widget redrawn for
 * its stale look or a new IOB keeps its chart.
 */
data class WidgetChartKey(
    val readingMillis: Long,
    /** Covers points that change without a new reading (a backfill, a calibration). */
    val historyFingerprint: Long,
    val widthPx: Int,
    val heightPx: Int,
    val windowMillis: Long,
    val dark: Boolean,
    val isMmol: Boolean,
    val viewMode: Int,
    val hasCalibration: Boolean,
    val sensorSerial: String?,
    val style: WidgetChartStyle,
) {
    companion object {
        fun fingerprint(points: List<GlucosePoint>): Long {
            var hash = points.size.toLong()
            for (point in points) {
                hash = hash * 31L + point.timestamp
                hash = hash * 31L + point.value.toRawBits()
                hash = hash * 31L + point.rawValue.toRawBits()
            }
            return hash
        }
    }
}

/**
 * The settings NotificationChartDrawer reads for itself rather than from its
 * arguments. Kept in step with drawChartInternal: an input missing here leaves a
 * widget on its old chart after the setting changes.
 */
data class WidgetChartStyle(
    /** The line in the range colours. */
    val rangeColoredLine: Boolean = false,
    /** The bands as set; the drawer falls back to its defaults for any not set. */
    val targetLow: Float = Float.NaN,
    val targetHigh: Float = Float.NaN,
    val veryLow: Float = Float.NaN,
    val veryHigh: Float = Float.NaN,
    /** The band colours, light then dark: the palette and any colour picked for a band. */
    val bandColors: List<Int> = emptyList(),
    /** The colour picked for the sensor's line, null when none was. */
    val sensorColor: Int? = null,
    val smoothingMinutes: Int = 0,
    val collapseChunks: Boolean = false,
    val hideInitialWhenCalibrated: Boolean = false,
    val calibrationRevision: Long = 0L,
) {
    companion object {
        /** The drawer's own key for [rangeColoredLine]. */
        const val RANGE_COLORED_LINE_KEY = "glucose_chart_range_colors_enabled"

        fun read(
            context: Context,
            sensorSerial: String?,
            targetLow: Float,
            targetHigh: Float,
            veryLow: Float,
            veryHigh: Float,
        ) = WidgetChartStyle(
            rangeColoredLine = context.getSharedPreferences(WidgetDataLoader.PREFS, Context.MODE_PRIVATE)
                .getBoolean(RANGE_COLORED_LINE_KEY, false),
            targetLow = targetLow,
            targetHigh = targetHigh,
            veryLow = veryLow,
            veryHigh = veryHigh,
            bandColors = listOf(false, true).flatMap { dark ->
                listOf(
                    GlucoseRangeColors.veryLow(dark),
                    GlucoseRangeColors.low(dark),
                    GlucoseRangeColors.inRange(dark),
                    GlucoseRangeColors.high(dark),
                    GlucoseRangeColors.veryHigh(dark),
                )
            },
            sensorColor = SensorVisuals.colorOverrideArgb(sensorSerial),
            smoothingMinutes = DataSmoothing.graphSmoothingMinutes(context),
            collapseChunks = DataSmoothing.collapseChunks(context),
            hideInitialWhenCalibrated = CalibrationAccess.shouldHideInitialWhenCalibrated(),
            calibrationRevision = CalibrationAccess.getRevision(),
        )
    }
}

/**
 * What the render thread keeps of each widget: the model last sent and its chart.
 * A forced redraw forgets both, so the widget is sent again with its chart drawn
 * afresh, whatever its key. Render thread only.
 */
class WidgetRenderCache<M : Any, B : Any> {
    private val models = HashMap<Int, M>()
    private val charts = WidgetChartCache<B>()

    /** Whether [appWidgetId] already shows [model]: nothing to send. */
    fun shows(appWidgetId: Int, model: M): Boolean = models[appWidgetId] == model

    fun chart(appWidgetId: Int, key: WidgetChartKey, draw: () -> B?): B? = charts.get(appWidgetId, key, draw)

    fun sent(appWidgetId: Int, model: M) {
        models[appWidgetId] = model
    }

    fun forget(appWidgetId: Int) {
        models.remove(appWidgetId)
        charts.remove(appWidgetId)
    }

    fun forgetAll() {
        models.clear()
        charts.clear()
    }

    /** Low memory: the charts go, drawn again at the next change. */
    fun dropCharts() {
        charts.clear()
    }
}

/** The last chart of each widget. Used from the render thread only. */
class WidgetChartCache<B : Any> {
    private val entries = HashMap<Int, Pair<WidgetChartKey, B>>()

    /** The cached chart when [key] matches, else [draw]'s, which is kept for next time. */
    fun get(appWidgetId: Int, key: WidgetChartKey, draw: () -> B?): B? {
        entries[appWidgetId]?.let { (cachedKey, chart) ->
            if (cachedKey == key) return chart
        }
        val chart = draw()
        if (chart == null) {
            entries.remove(appWidgetId)
        } else {
            entries[appWidgetId] = key to chart
        }
        return chart
    }

    fun remove(appWidgetId: Int) {
        entries.remove(appWidgetId)
    }

    fun retainOnly(appWidgetIds: Collection<Int>) {
        entries.keys.retainAll(appWidgetIds.toSet())
    }

    fun clear() {
        entries.clear()
    }

    val size: Int get() = entries.size
}
