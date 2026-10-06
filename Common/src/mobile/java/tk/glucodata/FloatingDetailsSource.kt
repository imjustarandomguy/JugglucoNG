package tk.glucodata

import android.content.Context
import android.graphics.Bitmap

/**
 * What the floating glucose's details card shows: a chart, the Δ and the IOB/COB
 * line, from the same history and helpers as the glucose notification (the chart
 * without prediction or other sensors).
 */
object FloatingDetailsSource {
    private const val PREFS = "tk.glucodata_preferences"
    private const val CHART_WINDOW_MS = 3L * 60L * 60L * 1000L

    data class Details(
        val chart: Bitmap?,
        /** Signed change over the notification's Δ interval, or "" when unknown. */
        val delta: String,
        /** The notification's IOB/COB line when it shows one, else null. */
        val iobLine: String?,
    )

    /** Reads history and draws a bitmap: call it off the main thread. */
    @JvmStatic
    fun load(
        context: Context,
        sensorId: String?,
        isMmol: Boolean,
        viewMode: Int,
        chartWidthPx: Int,
        chartHeightPx: Int,
        darkTheme: Boolean,
        displayGlucose: Float,
    ): Details {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val isRawMode = viewMode == 1 || viewMode == 3
        val points = runCatching {
            NotificationHistorySource
                .getDisplayHistory(System.currentTimeMillis() - CHART_WINDOW_MS, isMmol, sensorId)
                .sortedBy { it.timestamp }
        }.getOrDefault(emptyList())
        val chart = if (points.size >= 2) {
            runCatching {
                NotificationChartDrawer.drawChart(
                    context,
                    points,
                    chartWidthPx,
                    chartHeightPx,
                    isMmol,
                    viewMode,
                    prefs.getBoolean("notification_chart_target_range", true),
                    CalibrationAccess.hasActiveCalibration(isRawMode, sensorId),
                    false,
                    sensorId,
                    CHART_WINDOW_MS,
                )
            }.getOrNull()
        } else {
            null
        }
        return Details(
            chart = chart,
            delta = delta(points, isRawMode, isMmol, prefs.getInt("delta_interval_minutes", GlucoseDelta.DEFAULT_INTERVAL_MINUTES)),
            iobLine = iobLine(prefs, darkTheme, displayGlucose, isMmol),
        )
    }

    /** The notification's Δ: the newest reading against the first one old enough for the interval. */
    private fun delta(points: List<GlucosePoint>, isRawMode: Boolean, isMmol: Boolean, intervalMinutes: Int): String {
        if (points.size < 2) return ""
        fun valueOf(point: GlucosePoint) = if (isRawMode && point.rawValue > 0f) point.rawValue else point.value
        val newest = points.last()
        val previous = points.asReversed().drop(1).firstOrNull {
            valueOf(it) > 0.1f && newest.timestamp - it.timestamp >= GlucoseDelta.minGapMillis(intervalMinutes)
        } ?: return ""
        return GlucoseDelta.format(
            GlucoseDelta.delta(newest.timestamp, valueOf(newest), previous.timestamp, valueOf(previous), intervalMinutes),
            isMmol,
        )
    }

    /** Follows the notification's own IOB/COB switches, so both show the same. */
    private fun iobLine(
        prefs: android.content.SharedPreferences,
        darkTheme: Boolean,
        displayGlucose: Float,
        isMmol: Boolean,
    ): String? {
        val showIob = prefs.getBoolean("notification_show_iob", false)
        val showCob = prefs.getBoolean("notification_show_cob", false)
        if (!showIob && !showCob) return null
        return runCatching {
            JournalIobAccess.notificationLine(prefs, showIob, showCob, false, darkTheme, displayGlucose, isMmol)
                ?.toString()
        }.getOrNull()
    }
}
