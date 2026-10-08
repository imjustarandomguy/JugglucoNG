package tk.glucodata.widget

import tk.glucodata.GlucosePoint

/**
 * Everything a widget chart is drawn from. Two renders with equal keys draw the
 * same picture, so the second reuses the first's bitmap: a widget redrawn for
 * its stale look, a new IOB or a settings change keeps its chart.
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
