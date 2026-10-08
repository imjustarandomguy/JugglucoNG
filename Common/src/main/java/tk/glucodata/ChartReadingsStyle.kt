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

    /** Readings this far apart or more keep the duration-scaled dot: five minutes, less jitter. */
    private const val FULL_SIZE_INTERVAL_MS = 4L * 60L * 1000L
    private const val SPACING_GAPS = 15

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

    /**
     * [dotRadius] for readings drawn [readingSpacingPx] apart on a plot [plotWidthPx]
     * wide. The duration scale above is tuned on five-minute readings, so readings
     * [FULL_SIZE_INTERVAL_MS] or more apart keep it. A faster series' dots shrink until
     * neighbours just touch, so they do not merge into a band, but no smaller than the
     * duration size in proportion to the interval.
     */
    @JvmStatic
    fun dotRadius(lineWidthPx: Float, visibleDurationMs: Long, plotWidthPx: Float, readingSpacingPx: Float): Float {
        val byDuration = dotRadius(lineWidthPx, visibleDurationMs)
        if (!(plotWidthPx > 0f) || visibleDurationMs <= 0L || !(readingSpacingPx > 0f)) return byDuration
        val fullSizeSpacingPx = plotWidthPx * FULL_SIZE_INTERVAL_MS / visibleDurationMs
        val intervalRatio = readingSpacingPx / fullSizeSpacingPx
        if (intervalRatio >= 1f) return byDuration
        val touching = readingSpacingPx / 2f
        return minOf(byDuration, maxOf(touching, byDuration * intervalRatio)).coerceAtLeast(MIN_DOT_RADIUS_PX)
    }

    /**
     * The usual distance between consecutive dots: the median of the newest gaps (up to
     * [SPACING_GAPS]) between the x coordinates in [xs], one every [stride] floats of the
     * first [count]. Zero when there are none. The median ignores a missed reading or a
     * gap in the data. [scratch] holds the gaps, so a caller drawing every frame can keep one.
     */
    @JvmStatic
    @JvmOverloads
    fun typicalSpacing(xs: FloatArray, count: Int, stride: Int, scratch: FloatArray = FloatArray(SPACING_GAPS)): Float {
        val gaps = scratch
        val limit = minOf(gaps.size, SPACING_GAPS)
        var found = 0
        var index = minOf(count, xs.size) - stride
        while (index - stride >= 0 && found < limit) {
            val gap = kotlin.math.abs(xs[index] - xs[index - stride])
            if (gap > 0f) gaps[found++] = gap
            index -= stride
        }
        if (found == 0) return 0f
        gaps.sort(0, found)
        return gaps[(found - 1) / 2]
    }
}
