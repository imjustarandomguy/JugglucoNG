package tk.glucodata.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Build
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.style.StrikethroughSpan
import android.text.style.TypefaceSpan
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt
import tk.glucodata.GlucoseRangeColors
import tk.glucodata.MainActivity
import tk.glucodata.R
import tk.glucodata.TrendArrowAngle

/** Colours for one background choice. [dark]: light text on a dark surface; range colours and the chart follow it. */
data class WidgetPalette(
    /** Opaque background colour, null for none. */
    val backgroundArgb: Int?,
    val backgroundAlpha: Int,
    val textArgb: Int,
    val metaArgb: Int,
    val dark: Boolean,
) {
    companion object {
        private const val DARK_SURFACE = 0xFF1F1F1F.toInt()
        private const val LIGHT_SURFACE = 0xFFFFFFFF.toInt()
        private const val DARK_TEXT = 0xFF1F1F1F.toInt()
        private const val LIGHT_META = 0xFF5F6368.toInt()

        fun resolve(context: Context, background: WidgetBackground, opacityPercent: Int, night: Boolean): WidgetPalette {
            val alpha = (opacityPercent.coerceIn(0, 100) * 255 + 50) / 100
            return when (background) {
                // White on the wallpaper, as the classic widget always drew.
                WidgetBackground.NONE -> WidgetPalette(null, 0, Color.WHITE, 0xE6FFFFFF.toInt(), dark = true)
                WidgetBackground.DARK -> darkPalette(alpha)
                WidgetBackground.LIGHT -> WidgetPalette(LIGHT_SURFACE, alpha, DARK_TEXT, LIGHT_META, dark = false)
                WidgetBackground.THEME -> if (Build.VERSION.SDK_INT >= 31) {
                    themePalette(context, alpha, night)
                } else {
                    darkPalette(alpha)
                }
            }
        }

        private fun darkPalette(alpha: Int) = WidgetPalette(DARK_SURFACE, alpha, Color.WHITE, 0xB3FFFFFF.toInt(), dark = true)

        private fun themePalette(context: Context, alpha: Int, night: Boolean): WidgetPalette {
            if (Build.VERSION.SDK_INT < 31) return darkPalette(alpha)
            return if (night) {
                WidgetPalette(
                    context.getColor(android.R.color.system_neutral1_900), alpha,
                    context.getColor(android.R.color.system_neutral1_50),
                    context.getColor(android.R.color.system_neutral2_200), dark = true,
                )
            } else {
                WidgetPalette(
                    context.getColor(android.R.color.system_accent2_50), alpha,
                    context.getColor(android.R.color.system_neutral1_900),
                    context.getColor(android.R.color.system_neutral2_700), dark = false,
                )
            }
        }
    }
}

/**
 * The value's font, from the notification's font settings, which the widgets
 * have always followed. A named system font crosses to the launcher as a span;
 * the bundled IBM Plex cannot (hosts inflate widgets in a restricted context,
 * where font resources are not loaded), nor can a light weight, so those are
 * painted into a bitmap, as the notification does (CustomGlucoseNotification).
 */
class WidgetFonts private constructor(
    val nativeValue: Boolean,
    /** The span's family for native text; null for the bitmap. */
    val systemFamily: String?,
    val weight: Int,
    private val valueTypeface: Typeface,
    private val valueVariation: String?,
) {
    private val valuePaint = paint(valueTypeface, valueVariation)
    private val metaPaint = paint(Typeface.DEFAULT, null)

    val valueMeasure = TextMeasure { text -> valuePaint.measureText(text) / MEASURE_SIZE }
    val metaMeasure = TextMeasure { text -> metaPaint.measureText(text) / MEASURE_SIZE }

    fun drawValue(text: String, sizePx: Float, strike: Boolean): Bitmap =
        WidgetValueBitmap.draw(text, valueTypeface, valueVariation, sizePx, strike)

    /** Same settings, same fonts. */
    val key: String get() = "$nativeValue/$systemFamily/$weight"

    companion object {
        private const val MEASURE_SIZE = 100f

        private fun paint(typeface: Typeface, variation: String?) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = MEASURE_SIZE
            this.typeface = typeface
            if (variation != null) fontVariationSettings = variation
        }

        fun resolve(context: Context): WidgetFonts {
            val prefs = context.getSharedPreferences(WidgetDataLoader.PREFS, Context.MODE_PRIVATE)
            val systemFont = prefs.getInt("notification_font_family", 0) == 1
            val stored = prefs.getInt("notification_font_weight", 400)
            val weight = if (stored == 300 || stored == 500) stored else 400
            if (systemFont) {
                val family = systemFamily(context, weight)
                var face = Typeface.create(systemFamily(context, 400), Typeface.NORMAL)
                if (Build.VERSION.SDK_INT >= 28) face = Typeface.create(face, weight, false)
                return WidgetFonts(weight != 300, family, weight, face, null)
            }
            val plex = try {
                context.resources.getFont(R.font.ibm_plex_sans_var)
            } catch (_: Throwable) {
                Typeface.DEFAULT
            }
            return WidgetFonts(false, null, weight, plex, "'wght' $weight, 'wdth' 100")
        }

        /** As CustomGlucoseNotification.systemFamily: the system's headline family, by name. */
        private fun systemFamily(context: Context, weight: Int): String {
            val resource = if (weight == 500) "config_headlineFontFamilyMedium" else "config_headlineFontFamily"
            val id = context.resources.getIdentifier(resource, "string", "android")
            if (id != 0) {
                val name = context.resources.getString(id)
                if (name.isNotEmpty()) return name
            }
            return if (weight == 500) "sans-serif-medium" else "sans-serif"
        }
    }
}

/** The value painted in white (tinted by the host's colour filter), cropped to the digits. */
object WidgetValueBitmap {
    fun draw(text: String, typeface: Typeface, variation: String?, sizePx: Float, strike: Boolean): Bitmap {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            textSize = sizePx.coerceAtLeast(1f)
            this.typeface = typeface
            if (variation != null) fontVariationSettings = variation
            color = Color.WHITE
            isStrikeThruText = strike
        }
        val digit = Rect()
        paint.getTextBounds("0", 0, 1, digit)
        val bounds = Rect()
        paint.getTextBounds(text, 0, text.length, bounds)
        // Centred on the digits, so a decimal comma below the baseline does not move them.
        val digitCentre = (digit.top + digit.bottom) / 2f
        val margin = ceil(paint.textSize * 0.04f)
        val half = max(digitCentre - bounds.top, bounds.bottom - digitCentre) + margin
        val width = max(1, ceil(paint.measureText(text)).toInt() + 1)
        val height = max(1, ceil(2f * half).toInt())
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawText(text, 0f, half - digitCentre, paint)
        return bitmap
    }
}

/** Everything one widget's RemoteViews are built from. Equal models build equal views, so an equal one is not sent. */
data class WidgetRenderModel(
    val geometry: WidgetGeometry,
    val palette: WidgetPalette,
    val valueText: String,
    val valueColor: Int,
    val stale: Boolean,
    val fontKey: String,
    /** NaN when no arrow is shown. */
    val arrowRate: Float,
    val largeArrow: Boolean,
    val chartKey: WidgetChartKey?,
    val description: String,
    val density: Float,
    val rtl: Boolean,
)

/** Turns the loaded data and one widget's settings and size into a [WidgetRenderModel]. */
object WidgetPresenter {
    fun present(
        context: Context,
        kind: WidgetKind,
        options: WidgetOptions,
        size: WidgetSizeDp,
        data: WidgetData,
        fonts: WidgetFonts,
        night: Boolean,
        largeArrow: Boolean,
        timeText: (Long) -> String,
    ): WidgetRenderModel {
        val palette = WidgetPalette.resolve(context, options.background, options.opacityPercent, night)
        val reading = data.reading
        val fresh = data.isFresh
        val valueText = reading?.valueText ?: context.getString(R.string.novalue)
        val time = reading?.let { timeText(it.timeMillis) }
        val meta = when {
            reading == null -> emptyList()
            !fresh -> listOf(MetaItem(listOf(context.getString(R.string.nonewvalue) + time, time!!)))
            else -> listOfNotNull(
                time?.takeIf { options.showTime }?.let { MetaItem(it) },
                data.deltaText?.takeIf { options.showDelta }?.let { MetaItem("Δ $it") },
                data.iobUnits.takeIf { options.showIob && it.isFinite() }?.let {
                    MetaItem("IOB " + context.getString(R.string.unit_insulin_value, formatUnits(it)))
                },
            )
        }
        val chartWindow = options.chartWindowMillis
        val chartPoints = if (kind.offersChart && options.showChart) {
            data.historySince(data.nowMillis - chartWindow)
        } else {
            emptyList()
        }
        val showArrow = options.showArrow && fresh && reading!!.rate.isFinite()
        var geometry = WidgetGeometryCalculator.compute(
            size = size,
            chartWanted = chartPoints.size >= 2,
            hasBackground = palette.backgroundArgb != null,
            textScale = options.textScale,
            valueText = valueText,
            valueMeasure = fonts.valueMeasure,
            showArrow = showArrow,
            meta = meta,
            metaMeasure = fonts.metaMeasure,
        )
        if (reading == null) {
            // "No value" is a notice, not a number to read across the room.
            geometry = geometry.copy(valueSizeDp = minOf(geometry.valueSizeDp, 18f * options.textScale))
        }
        val valueColor = when {
            reading == null || !fresh -> palette.metaArgb
            options.rangeColors -> GlucoseRangeColors.trafficColorForValue(
                reading.value, data.targetLow, data.targetHigh, data.veryLow, data.veryHigh,
                palette.dark, data.isMmol, palette.textArgb,
            )
            else -> palette.textArgb
        }
        val density = context.resources.displayMetrics.density
        val chartKey = if (geometry.layout == WidgetLayout.TALL) {
            WidgetChartKey(
                readingMillis = reading?.timeMillis ?: 0L,
                historyFingerprint = WidgetChartKey.fingerprint(chartPoints),
                widthPx = (geometry.chartWidthDp * density).roundToInt().coerceAtLeast(1),
                heightPx = (geometry.chartHeightDp * density).roundToInt().coerceAtLeast(1),
                windowMillis = chartWindow,
                dark = palette.dark,
                isMmol = data.isMmol,
                viewMode = data.viewMode,
                hasCalibration = data.hasCalibration,
                sensorSerial = data.sensorSerial,
                style = data.chartStyle,
            )
        } else {
            null
        }
        val unit = context.getString(if (data.isMmol) R.string.mmolL else R.string.mgdL)
        val description = when {
            reading == null -> valueText
            else -> (listOf("$valueText $unit") + geometry.metaLines.ifEmpty { listOfNotNull(time) })
                .joinToString(", ")
        }
        return WidgetRenderModel(
            geometry = geometry,
            palette = palette,
            valueText = valueText,
            valueColor = valueColor,
            stale = reading != null && !fresh,
            fontKey = fonts.key,
            arrowRate = if (showArrow) reading!!.rate else Float.NaN,
            largeArrow = largeArrow,
            chartKey = chartKey,
            description = description,
            density = density,
            rtl = context.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL,
        )
    }

    /** As the notification's IOB line: whole units without a decimal. */
    private fun formatUnits(units: Float): String =
        if (units % 1f < 0.05f) units.roundToInt().toString() else String.format(java.util.Locale.getDefault(), "%.1f", units)

    fun isNight(context: Context): Boolean =
        context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
}

/** Builds the RemoteViews for a [WidgetRenderModel]. */
class WidgetRenderer(private val context: Context, private val fonts: WidgetFonts) {
    private val openApp: PendingIntent by lazy {
        PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun build(model: WidgetRenderModel, chart: Bitmap?): RemoteViews {
        val geometry = model.geometry
        val views = RemoteViews(context.packageName, layoutFor(geometry.layout))
        val background = model.palette.backgroundArgb
        if (background == null) {
            views.setViewVisibility(R.id.widget_background, View.GONE)
        } else {
            views.setViewVisibility(R.id.widget_background, View.VISIBLE)
            views.setInt(R.id.widget_background, "setColorFilter", background)
            views.setInt(R.id.widget_background, "setImageAlpha", model.palette.backgroundAlpha)
        }
        val padding = px(model, geometry.paddingDp)
        views.setViewPadding(R.id.widget_content, padding, padding, padding, padding)
        bindValue(views, model)
        bindArrow(views, model)
        bindMeta(views, model)
        if (geometry.layout == WidgetLayout.TALL) {
            views.setInt(R.id.widget_header_strut, "setHeight", px(model, geometry.headerHeightDp))
            if (chart != null) {
                views.setImageViewBitmap(R.id.widget_chart, chart)
                views.setViewVisibility(R.id.widget_chart, View.VISIBLE)
            } else {
                views.setViewVisibility(R.id.widget_chart, View.GONE)
            }
        }
        views.setOnClickPendingIntent(R.id.widget_root, openApp)
        views.setContentDescription(R.id.widget_root, model.description)
        return views
    }

    private fun layoutFor(layout: WidgetLayout): Int = when (layout) {
        WidgetLayout.SMALL -> R.layout.widget_glucose_small
        WidgetLayout.WIDE -> R.layout.widget_glucose_wide
        WidgetLayout.TALL -> R.layout.widget_glucose_tall
    }

    private fun px(model: WidgetRenderModel, dp: Float): Int = (dp * model.density).roundToInt()

    private fun bindValue(views: RemoteViews, model: WidgetRenderModel) {
        val sizePx = model.geometry.valueSizeDp * model.density
        if (fonts.nativeValue) {
            views.setViewVisibility(R.id.widget_value, View.VISIBLE)
            views.setViewVisibility(R.id.widget_value_image, View.GONE)
            val text = SpannableString(model.valueText)
            fonts.systemFamily?.let {
                text.setSpan(TypefaceSpan(it), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            if (model.stale) text.setSpan(StrikethroughSpan(), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            views.setTextViewText(R.id.widget_value, text)
            views.setTextViewTextSize(R.id.widget_value, TypedValue.COMPLEX_UNIT_PX, sizePx)
            views.setTextColor(R.id.widget_value, model.valueColor)
        } else {
            views.setViewVisibility(R.id.widget_value, View.GONE)
            views.setViewVisibility(R.id.widget_value_image, View.VISIBLE)
            views.setImageViewBitmap(R.id.widget_value_image, fonts.drawValue(model.valueText, sizePx, model.stale))
            views.setInt(R.id.widget_value_image, "setColorFilter", model.valueColor)
            views.setContentDescription(R.id.widget_value_image, model.valueText)
        }
    }

    /** The notification's vector arrow (CustomGlucoseNotification.bindArrow): no bitmap. */
    private fun bindArrow(views: RemoteViews, model: WidgetRenderModel) {
        val rate = model.arrowRate
        val visible = model.geometry.arrowSizeDp > 0f && rate.isFinite()
        views.setViewVisibility(R.id.widget_arrow, if (visible) View.VISIBLE else View.GONE)
        if (!visible) return
        val doubled = abs(rate) > 2f
        val drawable = when {
            model.largeArrow && doubled -> R.drawable.notification_trend_long_double
            model.largeArrow -> R.drawable.notification_trend_long_single
            doubled -> R.drawable.notification_trend_double
            else -> R.drawable.notification_trend_single
        }
        views.setImageViewResource(R.id.widget_arrow, drawable)
        val angle = TrendArrowAngle.rotationDegrees(rate)
        views.setInt(R.id.widget_arrow, "setImageLevel", ((angle + 90f) * 10000f / 180f).roundToInt())
        views.setInt(R.id.widget_arrow, "setColorFilter", model.valueColor)
        val size = max(1, px(model, model.geometry.arrowSizeDp))
        val gap = max(1, px(model, model.geometry.arrowGapDp))
        views.setViewPadding(R.id.widget_arrow, if (model.rtl) 0 else gap, 0, if (model.rtl) gap else 0, 0)
        views.setInt(R.id.widget_arrow, "setMaxWidth", size + gap)
        views.setInt(R.id.widget_arrow, "setMaxHeight", size)
        val description = when {
            angle == 0f -> R.string.notification_trend_steady
            rate > 0f -> if (doubled) R.string.notification_trend_rising_fast else R.string.notification_trend_rising
            else -> if (doubled) R.string.notification_trend_falling_fast else R.string.notification_trend_falling
        }
        views.setContentDescription(R.id.widget_arrow, context.getString(description))
    }

    private fun bindMeta(views: RemoteViews, model: WidgetRenderModel) {
        val lines = model.geometry.metaLines
        val ids = if (model.geometry.layout == WidgetLayout.SMALL) SMALL_META else COLUMN_META
        val sizePx = model.geometry.metaSizeDp * model.density
        ids.forEachIndexed { index, id ->
            val line = lines.getOrNull(index)
            if (line == null) {
                views.setViewVisibility(id, View.GONE)
            } else {
                views.setViewVisibility(id, View.VISIBLE)
                views.setTextViewText(id, line)
                views.setTextViewTextSize(id, TypedValue.COMPLEX_UNIT_PX, sizePx)
                views.setTextColor(id, model.palette.metaArgb)
            }
        }
    }

    private companion object {
        val SMALL_META = intArrayOf(R.id.widget_meta)
        val COLUMN_META = intArrayOf(R.id.widget_meta_1, R.id.widget_meta_2, R.id.widget_meta_3)
    }
}
