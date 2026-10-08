package tk.glucodata

import android.content.Context
import tk.glucodata.settings.SettingsRegistry
import kotlin.math.ln

/**
 * How the glucose readings are drawn on every chart: as a line (the default), as
 * one dot per reading, or as a thinner, fainter line with the dots on it.
 *
 * One setting for the app chart (dashboard, history, journal), the chart behind
 * the notification, AOD overlay, widget and floating card, and the watch's chart
 * and complication. It travels to the watch with the other mirrored display
 * settings ([SettingsRegistry.CHART_READINGS_STYLE]). Predictions stay dashed
 * lines in every style: they are not readings.
 */
object ChartReadingsStyle {
    const val LINE = 0
    const val DOTS = 1
    const val LINE_AND_DOTS = 2

    /** "Line and dots": the line is this much thinner and fainter, so the dots carry the readings. */
    const val LINE_WITH_DOTS_WIDTH = 0.5f
    const val LINE_WITH_DOTS_ALPHA = 0.5f

    private const val HOUR_MS = 60L * 60L * 1000L
    private const val MIN_DOT_RADIUS_PX = 0.75f

    /** A stored value from another build falls back to the line. */
    @JvmStatic
    fun fromPreference(value: Int): Int = if (value in LINE..LINE_AND_DOTS) value else LINE

    @JvmStatic
    fun read(context: Context?): Int = fromPreference(SettingsRegistry.CHART_READINGS_STYLE.readInt(context))

    @JvmStatic
    fun drawsLine(style: Int): Boolean = style != DOTS

    @JvmStatic
    fun drawsDots(style: Int): Boolean = style == DOTS || style == LINE_AND_DOTS

    /**
     * The radius of one reading's dot on a chart whose line is [lineWidthPx] wide and
     * which shows [visibleDurationMs]. As wide as the line up to an hour, then smaller
     * on a log scale: 40% at 24 hours, where 5-minute readings sit closer together than
     * a dot is wide and full-size dots would merge into a band, and 30% from three days.
     */
    @JvmStatic
    fun dotRadius(lineWidthPx: Float, visibleDurationMs: Long): Float {
        if (!lineWidthPx.isFinite() || lineWidthPx <= 0f) return MIN_DOT_RADIUS_PX
        val hours = (visibleDurationMs.toDouble() / HOUR_MS).coerceAtLeast(1.0)
        val scale = when {
            hours <= 24.0 -> 1.0 - 0.6 * (ln(hours) / ln(24.0))
            else -> 0.4 - 0.1 * (ln(hours.coerceAtMost(72.0) / 24.0) / ln(3.0))
        }
        return (lineWidthPx * scale.toFloat()).coerceAtLeast(MIN_DOT_RADIUS_PX)
    }
}
