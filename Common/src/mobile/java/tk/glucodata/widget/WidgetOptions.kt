package tk.glucodata.widget

import android.content.SharedPreferences

/** The two home-screen widgets. Their provider classes stay as they were, since placed widgets are bound to them. */
enum class WidgetKind(val defaults: WidgetOptions, val fallbackSize: WidgetSizeDp) {
    /** tk.glucodata.GlucoseWidget: the value, trend arrow and reading time. */
    VALUE(WidgetOptions.VALUE_DEFAULTS, WidgetSizeDp(180, 50)),

    /** tk.glucodata.widget.ExpressiveWidgetReceiver: the value and, when tall enough, a chart. */
    CHART(WidgetOptions.CHART_DEFAULTS, WidgetSizeDp(250, 110));

    /** Only the chart widget offers the chart settings. */
    val offersChart: Boolean get() = this == CHART
}

enum class WidgetBackground(val code: Int) {
    NONE(0),
    DARK(1),
    LIGHT(2),

    /** Dynamic colours on Android 12 and later, dark before. */
    THEME(3);

    companion object {
        fun fromCode(code: Int): WidgetBackground = entries.firstOrNull { it.code == code } ?: NONE
    }
}

/** What one placed widget shows. Stored per appWidgetId by [WidgetOptionsStore]. */
data class WidgetOptions(
    val rangeColors: Boolean,
    val showArrow: Boolean,
    val showDelta: Boolean,
    val showTime: Boolean,
    val showIob: Boolean,
    val textScale: Float,
    val showChart: Boolean,
    val chartHours: Int,
    val background: WidgetBackground,
    val opacityPercent: Int,
) {
    val chartWindowMillis: Long get() = chartHours * HOUR_MILLIS

    fun sanitized(): WidgetOptions = copy(
        textScale = if (textScale.isFinite()) textScale.coerceIn(MIN_TEXT_SCALE, MAX_TEXT_SCALE) else 1f,
        chartHours = if (chartHours in CHART_HOURS) chartHours else DEFAULT_CHART_HOURS,
        opacityPercent = opacityPercent.coerceIn(0, 100),
    )

    companion object {
        const val MIN_TEXT_SCALE = 0.8f
        const val MAX_TEXT_SCALE = 1.3f
        val CHART_HOURS = listOf(1, 3, 6)
        const val DEFAULT_CHART_HOURS = 3
        private const val HOUR_MILLIS = 60L * 60L * 1000L

        /** The classic widget's look: white value on the wallpaper, arrow and time, coloured by range. */
        val VALUE_DEFAULTS = WidgetOptions(
            rangeColors = true,
            showArrow = true,
            showDelta = false,
            showTime = true,
            showIob = false,
            textScale = 1f,
            showChart = false,
            chartHours = DEFAULT_CHART_HOURS,
            background = WidgetBackground.NONE,
            opacityPercent = 100,
        )

        /** The chart widget's look: on a theme-coloured card, with the change and a three-hour chart. */
        val CHART_DEFAULTS = WidgetOptions(
            rangeColors = false,
            showArrow = true,
            showDelta = true,
            showTime = true,
            showIob = false,
            textScale = 1f,
            showChart = true,
            chartHours = DEFAULT_CHART_HOURS,
            background = WidgetBackground.THEME,
            opacityPercent = 100,
        )
    }
}

/**
 * Per-widget settings, keyed by appWidgetId. A widget with nothing stored (every
 * widget placed before these settings existed) shows its kind's defaults, so it
 * keeps its look without a migration step.
 */
class WidgetOptionsStore(private val prefs: SharedPreferences) {

    fun load(appWidgetId: Int, kind: WidgetKind): WidgetOptions {
        val defaults = kind.defaults
        if (!prefs.contains(key(appWidgetId, SAVED))) return defaults
        return WidgetOptions(
            rangeColors = prefs.getBoolean(key(appWidgetId, RANGE_COLORS), defaults.rangeColors),
            showArrow = prefs.getBoolean(key(appWidgetId, ARROW), defaults.showArrow),
            showDelta = prefs.getBoolean(key(appWidgetId, DELTA), defaults.showDelta),
            showTime = prefs.getBoolean(key(appWidgetId, TIME), defaults.showTime),
            showIob = prefs.getBoolean(key(appWidgetId, IOB), defaults.showIob),
            textScale = prefs.getFloat(key(appWidgetId, TEXT_SCALE), defaults.textScale),
            showChart = prefs.getBoolean(key(appWidgetId, CHART), defaults.showChart),
            chartHours = prefs.getInt(key(appWidgetId, CHART_HOURS), defaults.chartHours),
            background = WidgetBackground.fromCode(
                prefs.getInt(key(appWidgetId, BACKGROUND), defaults.background.code)
            ),
            opacityPercent = prefs.getInt(key(appWidgetId, OPACITY), defaults.opacityPercent),
        ).sanitized()
    }

    fun save(appWidgetId: Int, options: WidgetOptions) {
        val editor = prefs.edit()
        put(editor, appWidgetId, options.sanitized())
        editor.apply()
    }

    /** onDeleted: a removed widget leaves nothing behind. */
    fun delete(appWidgetIds: IntArray) {
        val editor = prefs.edit()
        for (id in appWidgetIds) {
            for (field in FIELDS) editor.remove(key(id, field))
        }
        editor.apply()
    }

    /**
     * onRestored: a restore hands out new ids for the same widgets. Every old id
     * is read before anything is written, since an old id can be reused as a new one.
     */
    fun move(oldIds: IntArray, newIds: IntArray) {
        val count = minOf(oldIds.size, newIds.size)
        val moved = (0 until count).mapNotNull { index ->
            val oldId = oldIds[index]
            if (!prefs.contains(key(oldId, SAVED))) null else newIds[index] to readRaw(oldId)
        }
        val editor = prefs.edit()
        for (index in 0 until count) {
            for (field in FIELDS) editor.remove(key(oldIds[index], field))
        }
        for ((newId, options) in moved) put(editor, newId, options)
        editor.apply()
    }

    private fun readRaw(appWidgetId: Int): WidgetOptions {
        // The kind does not matter here: every field of a saved widget is present.
        return load(appWidgetId, WidgetKind.VALUE)
    }

    private fun put(editor: SharedPreferences.Editor, appWidgetId: Int, options: WidgetOptions) {
        editor.putBoolean(key(appWidgetId, RANGE_COLORS), options.rangeColors)
            .putBoolean(key(appWidgetId, ARROW), options.showArrow)
            .putBoolean(key(appWidgetId, DELTA), options.showDelta)
            .putBoolean(key(appWidgetId, TIME), options.showTime)
            .putBoolean(key(appWidgetId, IOB), options.showIob)
            .putFloat(key(appWidgetId, TEXT_SCALE), options.textScale)
            .putBoolean(key(appWidgetId, CHART), options.showChart)
            .putInt(key(appWidgetId, CHART_HOURS), options.chartHours)
            .putInt(key(appWidgetId, BACKGROUND), options.background.code)
            .putInt(key(appWidgetId, OPACITY), options.opacityPercent)
            .putInt(key(appWidgetId, SAVED), VERSION)
    }

    companion object {
        const val FILE = "glucose_widgets"
        private const val VERSION = 1

        private const val SAVED = "saved"
        private const val RANGE_COLORS = "rangeColors"
        private const val ARROW = "arrow"
        private const val DELTA = "delta"
        private const val TIME = "time"
        private const val IOB = "iob"
        private const val TEXT_SCALE = "textScale"
        private const val CHART = "chart"
        private const val CHART_HOURS = "chartHours"
        private const val BACKGROUND = "background"
        private const val OPACITY = "opacity"
        private val FIELDS = listOf(
            SAVED, RANGE_COLORS, ARROW, DELTA, TIME, IOB, TEXT_SCALE, CHART, CHART_HOURS, BACKGROUND, OPACITY,
        )

        internal fun key(appWidgetId: Int, field: String) = "w$appWidgetId.$field"
    }
}
