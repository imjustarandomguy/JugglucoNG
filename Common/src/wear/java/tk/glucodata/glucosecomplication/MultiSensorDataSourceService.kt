package tk.glucodata.glucosecomplication

import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.PhotoImageComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.SmallImage
import androidx.wear.watchface.complications.data.SmallImageComplicationData
import androidx.wear.watchface.complications.data.SmallImageType
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import tk.glucodata.Applic
import tk.glucodata.DisplayedReading
import tk.glucodata.R
import tk.glucodata.SensorIdentity
import tk.glucodata.ui.WearSensorSelection

internal data class MultiSensorValue(
    val sensorId: String,
    val reading: GlucoseComplicationData.Reading?,
    val colorArgb: Int,
) {
    val text: String get() = reading?.text ?: DisplayedReading.NO_VALUE_TEXT
}

/**
 * Keep every selected slot, even without a value, so a peer never impersonates the primary.
 * [resolve] applies the current reading's rules (CurrentReadingHub): null once a sensor's
 * value is taken down, its arrow gone once it is stale.
 */
internal fun multiSensorValues(
    selected: List<String>,
    colors: Map<String, Int>,
    resolve: (String) -> GlucoseComplicationData.Reading?,
): List<MultiSensorValue> = selected.map { sensor ->
    val reading = resolve(sensor)?.takeIf {
        it.timeMillis > 0L && it.value.isFinite() && it.value > 0f && it.text.isNotBlank() &&
            SensorIdentity.matches(it.sensorId, sensor)
    }
    MultiSensorValue(sensor, reading, colors[sensor] ?: 0xFFE3E2DE.toInt())
}

internal fun multiSensorText(values: List<MultiSensorValue>, arrows: Boolean): String =
    values.joinToString(" · ") { value ->
        value.text + trendArrowText(if (arrows) value.reading?.rate else null)
    }

abstract class MultiSensorComplicationBase : SuspendingComplicationDataSourceService() {
    protected abstract val showArrows: Boolean

    override fun getPreviewData(type: ComplicationType): ComplicationData? = build(type, preview = true)

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? =
        build(request.complicationType, preview = false)

    // No validity range or update period of its own: it is redrawn with every other
    // complication whenever the current reading changes, its stale moment included.
    private fun build(type: ComplicationType, preview: Boolean): ComplicationData? {
        val now = System.currentTimeMillis()
        val selected = WearSensorSelection.selected()
        val live = multiSensorValues(selected, WearSensorSelection.colors()) {
            GlucoseComplicationData.currentReading(it)
        }
        // A useful two-sensor picker example even before selection/history arrives.
        val values = if (preview && live.size < 2) previewValues(now) else live
        val text = multiSensorText(values, showArrows).ifBlank { Applic.app.getString(R.string.novalue) }
        val description = PlainComplicationText.Builder(
            values.joinToString("; ") { "${it.sensorId}: ${it.text}" }.ifBlank { text },
        ).build()
        val tap = GlucoseComplicationData.tapAction()
        return when (type) {
            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(
                PlainComplicationText.Builder(text).build(), description,
            ).setTapAction(tap).build()
            ComplicationType.SMALL_IMAGE -> SmallImageComplicationData.Builder(
                SmallImage.Builder(Icon.createWithBitmap(multiSensorBitmap(values, showArrows)), SmallImageType.PHOTO).build(),
                description,
            ).setTapAction(tap).build()
            ComplicationType.PHOTO_IMAGE -> PhotoImageComplicationData.Builder(
                Icon.createWithBitmap(multiSensorBitmap(values, showArrows)), description,
            ).setTapAction(tap).build()
            else -> null
        }
    }

    private fun previewValues(now: Long): List<MultiSensorValue> {
        val mmol = ComplicationRenderer.isMmol()
        return listOf(101f to 0.4f, 126f to -1.2f).mapIndexed { index, (mgdl, rate) ->
            val value = if (mmol) mgdl / 18.0182f else mgdl
            MultiSensorValue(
                "${index + 1}",
                GlucoseComplicationData.Reading(value, tk.glucodata.ui.util.GlucoseFormatter.format(value, mmol), mmol, now, rate, 0),
                if (index == 0) 0xFFE3E2DE.toInt() else 0xFF4285F4.toInt(),
            )
        }
    }
}

/** One inline row, with the same light sensor tints as the phone's reading values. */
internal fun multiSensorBitmap(values: List<MultiSensorValue>, arrows: Boolean): Bitmap {
    val size = 320
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif", Typeface.BOLD) }
    val inset = size * 0.16f
    val width = size - 2 * inset
    val rows = values.ifEmpty { listOf(MultiSensorValue("", null, 0xFFE3E2DE.toInt())) }
    val gap = size * 0.025f
    val arrowWidth = if (arrows) size * 0.09f else 0f
    paint.textSize = size * 0.2f
    val textWidth = rows.sumOf { paint.measureText(it.text).toDouble() }.toFloat()
    val available = width - gap * (rows.size - 1) - (arrowWidth + if (arrows) gap else 0f) * rows.size
    if (textWidth > available) paint.textSize *= available.coerceAtLeast(1f) / textWidth
    val totalWidth = rows.sumOf { paint.measureText(it.text).toDouble() }.toFloat() +
        gap * (rows.size - 1) + (arrowWidth + if (arrows) gap else 0f) * rows.size
    var x = (size - totalWidth) / 2f
    val centerY = size / 2f
    val baseline = centerY - (paint.ascent() + paint.descent()) / 2f
    rows.forEachIndexed { index, value ->
        paint.color = tk.glucodata.SensorVisuals.blendArgb(
            0xFFE3E2DE.toInt(), value.colorArgb,
            if (index == 0) tk.glucodata.SensorVisuals.PRIMARY_TEXT_BLEND else tk.glucodata.SensorVisuals.PEER_TEXT_BLEND,
        ).let { if (value.reading?.stale == true) ComplicationRenderer.dimmed(it) else it }
        canvas.drawText(value.text, x, baseline, paint)
        x += paint.measureText(value.text)
        if (arrows) {
            x += gap
            value.reading?.rate?.takeIf { it.isFinite() }?.let { rate ->
                ComplicationRenderer.drawArrow(canvas, arrowWidth, arrowWidth, rate, paint.color,
                    x, centerY - arrowWidth / 2f)
            }
            x += arrowWidth
        }
        x += gap
    }
    return bitmap
}

class MultiSensorDataSourceService : MultiSensorComplicationBase() {
    override val showArrows = false

    companion object {
        @JvmStatic
        fun update() {
            listOf(MultiSensorDataSourceService::class.java, MultiSensorArrowDataSourceService::class.java).forEach {
                ComplicationDataSourceUpdateRequester.create(Applic.app, ComponentName(Applic.app, it)).requestUpdateAll()
            }
        }
    }
}

class MultiSensorArrowDataSourceService : MultiSensorComplicationBase() {
    override val showArrows = true
}
