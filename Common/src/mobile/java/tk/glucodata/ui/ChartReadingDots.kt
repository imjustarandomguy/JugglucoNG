package tk.glucodata.ui

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas

/**
 * The positions of the readings one series draws as dots ([tk.glucodata.ChartReadingsStyle]),
 * as x, y pairs. Kept and cleared across frames like the chart's paths, so a pan
 * allocates nothing once the array has grown to the window's size.
 */
internal class ChartDotBuffer {
    var coordinates = FloatArray(256)
        private set

    /** Floats in use: two per dot. */
    var size = 0
        private set

    fun clear() {
        size = 0
    }

    fun add(x: Float, y: Float) {
        if (size + 2 > coordinates.size) coordinates = coordinates.copyOf(coordinates.size * 2)
        coordinates[size++] = x
        coordinates[size++] = y
    }
}

/**
 * Every dot in [dots] as a round point of [radius], painted with [brush]. Given the
 * line's own vertical range gradient, each dot comes out in the colour the line has
 * at that reading. One native call for the whole series.
 */
internal fun DrawScope.drawReadingDots(
    dots: ChartDotBuffer,
    brush: Brush,
    radius: Float,
    paint: Paint,
    alpha: Float = 1f
) {
    if (dots.size == 0 || !(radius > 0f)) return
    brush.applyTo(size, paint, alpha)
    paint.style = PaintingStyle.Stroke
    paint.strokeWidth = radius * 2f
    paint.strokeCap = StrokeCap.Round
    drawContext.canvas.nativeCanvas.drawPoints(dots.coordinates, 0, dots.size, paint.asFrameworkPaint())
}
