package tk.glucodata.widget

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.Parcel
import android.view.View
import android.widget.ImageView
import android.widget.RemoteViews
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import tk.glucodata.R

/**
 * The widgets' RemoteViews as a launcher sees them: parcelled, then applied in a
 * restricted context. Every reflective call (colour filter, alpha, strut height,
 * arrow level) must be allowed on RemoteViews, or the host shows an error instead.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 34])
@ConscryptMode(ConscryptMode.Mode.OFF)
class WidgetRendererTests {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val restricted = object : ContextWrapper(app) {
        override fun isRestricted() = true
    }
    private val now = 1_800_000_000_000L
    private val fresh = WidgetSamples.data(now, isMmol = false)
    private val stale = WidgetData(
        reading = fresh.reading!!.copy(timeMillis = now - 20 * 60_000L),
        history = fresh.history, isMmol = false, sensorSerial = null, viewMode = 0, hasCalibration = false,
        deltaText = null, iobUnits = Float.NaN, nowMillis = now, freshnessMillis = 330_000L,
    )
    private val empty = WidgetData(
        reading = null, history = emptyList(), isMmol = false, sensorSerial = null, viewMode = 0,
        hasCalibration = false, deltaText = null, iobUnits = Float.NaN, nowMillis = now, freshnessMillis = 330_000L,
    )

    @Before
    fun appFont() {
        fontFamily(0)
    }

    private fun fontFamily(family: Int) {
        app.getSharedPreferences(WidgetDataLoader.PREFS, Context.MODE_PRIVATE).edit()
            .putInt("notification_font_family", family).commit()
    }

    private fun render(
        kind: WidgetKind,
        options: WidgetOptions,
        size: WidgetSizeDp,
        data: WidgetData = fresh,
    ): Pair<WidgetRenderModel, View> {
        val fonts = WidgetFonts.resolve(app)
        val model = WidgetPresenter.present(app, kind, options, size, data, fonts, night = false, largeArrow = false) { "12:34" }
        val chart = model.chartKey?.let { Bitmap.createBitmap(it.widthPx, it.heightPx, Bitmap.Config.ARGB_8888) }
        val views = WidgetRenderer(app, fonts).build(model, chart)
        val parcel = Parcel.obtain()
        try {
            views.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            val root = RemoteViews.CREATOR.createFromParcel(parcel).apply(restricted, null)
            val density = app.resources.displayMetrics.density
            root.measure(
                View.MeasureSpec.makeMeasureSpec((size.width * density).toInt(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec((size.height * density).toInt(), View.MeasureSpec.EXACTLY),
            )
            root.layout(0, 0, root.measuredWidth, root.measuredHeight)
            return model to root
        } finally {
            parcel.recycle()
        }
    }

    @Test
    fun oneRowValueWidgetOnTheWallpaper() {
        val (model, root) = render(WidgetKind.VALUE, WidgetOptions.VALUE_DEFAULTS, WidgetSizeDp(110, 56))
        assertEquals(WidgetLayout.SMALL, model.geometry.layout)
        assertEquals(View.GONE, root.findViewById<View>(R.id.widget_background).visibility)
        // The bundled font cannot cross to the launcher: the value is an image.
        assertEquals(View.GONE, root.findViewById<View>(R.id.widget_value).visibility)
        assertEquals(View.VISIBLE, root.findViewById<View>(R.id.widget_value_image).visibility)
        assertEquals(View.VISIBLE, root.findViewById<ImageView>(R.id.widget_arrow).visibility)
        assertEquals("12:34", root.findViewById<TextView>(R.id.widget_meta).text.toString())
        assertNull(model.chartKey)
        assertTrue(root.contentDescription.toString().startsWith("${fresh.reading!!.valueText} mg/dL"))
    }

    @Test
    fun aSystemFontValueIsNativeText() {
        fontFamily(1)
        val (_, root) = render(WidgetKind.VALUE, WidgetOptions.VALUE_DEFAULTS, WidgetSizeDp(250, 56))
        val value = root.findViewById<TextView>(R.id.widget_value)
        assertEquals(View.VISIBLE, value.visibility)
        assertEquals(fresh.reading!!.valueText, value.text.toString())
        assertEquals(View.GONE, root.findViewById<View>(R.id.widget_value_image).visibility)
    }

    @Test
    fun wideChartWidgetOnACardShowsItsMetaColumn() {
        val options = WidgetOptions.CHART_DEFAULTS.copy(background = WidgetBackground.DARK, opacityPercent = 50, showIob = true)
        val (model, root) = render(WidgetKind.CHART, options, WidgetSizeDp(300, 64))
        assertEquals(WidgetLayout.WIDE, model.geometry.layout)
        assertEquals(View.VISIBLE, root.findViewById<View>(R.id.widget_background).visibility)
        assertEquals(128, model.palette.backgroundAlpha)
        val lines = listOf(R.id.widget_meta_1, R.id.widget_meta_2, R.id.widget_meta_3)
            .map { root.findViewById<TextView>(it) }
            .filter { it.visibility == View.VISIBLE }
            .joinToString(" · ") { it.text.toString() }
        assertTrue(lines, lines.startsWith("12:34"))
        assertTrue(lines, lines.contains("Δ +4"))
        assertTrue(lines, lines.contains("IOB 1.2 U"))
    }

    @Test
    fun tallChartWidgetDrawsTheChartUnderTheValueRow() {
        val (model, root) = render(WidgetKind.CHART, WidgetOptions.CHART_DEFAULTS, WidgetSizeDp(300, 150))
        assertEquals(WidgetLayout.TALL, model.geometry.layout)
        assertNotNull(model.chartKey)
        val chart = root.findViewById<ImageView>(R.id.widget_chart)
        assertEquals(View.VISIBLE, chart.visibility)
        assertTrue(chart.height > 0)
        assertTrue(chart.top >= root.findViewById<View>(R.id.widget_header_strut).bottom)
    }

    @Test
    fun theValueWidgetNeverDrawsAChart() {
        val options = WidgetOptions.VALUE_DEFAULTS.copy(showChart = true)
        val (model, _) = render(WidgetKind.VALUE, options, WidgetSizeDp(300, 150))
        assertEquals(WidgetLayout.SMALL, model.geometry.layout)
        assertNull(model.chartKey)
    }

    @Test
    fun aStaleReadingIsStruckThroughWithoutArrowAndSaysSince() {
        fontFamily(1)
        val (model, root) = render(WidgetKind.VALUE, WidgetOptions.VALUE_DEFAULTS, WidgetSizeDp(260, 56), stale)
        assertTrue(model.stale)
        assertEquals(View.GONE, root.findViewById<View>(R.id.widget_arrow).visibility)
        val value = root.findViewById<TextView>(R.id.widget_value)
        val spanned = value.text as android.text.Spanned
        assertEquals(1, spanned.getSpans(0, spanned.length, android.text.style.StrikethroughSpan::class.java).size)
        assertEquals(model.palette.metaArgb, value.currentTextColor)
        val meta = root.findViewById<TextView>(R.id.widget_meta_1).text.toString()
        assertTrue(meta, meta.endsWith("12:34"))
    }

    @Test
    fun noReadingSaysSo() {
        val (model, root) = render(WidgetKind.CHART, WidgetOptions.CHART_DEFAULTS, WidgetSizeDp(300, 150), empty)
        assertEquals(app.getString(R.string.novalue), model.valueText)
        assertNull(model.chartKey)
        assertEquals(View.GONE, root.findViewById<View>(R.id.widget_arrow).visibility)
    }

    @Test
    fun rangeColoursFollowTheOption() {
        val coloured = WidgetPresenter.present(
            app, WidgetKind.VALUE, WidgetOptions.VALUE_DEFAULTS, WidgetSizeDp(110, 56), fresh,
            WidgetFonts.resolve(app), night = false, largeArrow = false,
        ) { "12:34" }
        val plain = WidgetPresenter.present(
            app, WidgetKind.VALUE, WidgetOptions.VALUE_DEFAULTS.copy(rangeColors = false), WidgetSizeDp(110, 56), fresh,
            WidgetFonts.resolve(app), night = false, largeArrow = false,
        ) { "12:34" }
        assertEquals(android.graphics.Color.WHITE, plain.valueColor)
        assertTrue(coloured.valueColor != plain.valueColor)
    }
}
