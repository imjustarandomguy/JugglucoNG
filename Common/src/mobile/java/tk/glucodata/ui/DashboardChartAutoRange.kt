package tk.glucodata.ui

internal data class ChartYRange(
    val min: Float,
    val max: Float
)

/**
 * The chart's y range: the configured chart range ([baselineMin]..[baselineMax]),
 * widened just enough that every value in the visible window keeps a little padding
 * from the axis ends, rounded out to 1 mmol/L or 18 mg/dL. A value inside the range
 * but within that padding of an end widens it too, so its line and dot are not cut
 * by the plot's edge. It never shrinks below the configured range, and is that range
 * again once those values leave the window.
 */
internal fun autoExpandedChartYRange(
    baselineMin: Float,
    baselineMax: Float,
    visibleMin: Float?,
    visibleMax: Float?,
    isMmol: Boolean
): ChartYRange {
    val safeMin = baselineMin.takeIf { it.isFinite() && it >= 0f } ?: 0f
    val safeMax = baselineMax.takeIf { it.isFinite() && it > safeMin } ?: if (isMmol) 13f else 234f
    val span = safeMax - safeMin
    val expansionStep = if (isMmol) 1f else 18f
    val edgePadding = maxOf(span * 0.04f, if (isMmol) 0.4f else 7f)

    val low = visibleMin
        ?.takeIf { it.isFinite() && it > 0.1f && it < safeMin + edgePadding }
        ?.let { value ->
            (kotlin.math.floor((value - edgePadding) / expansionStep) * expansionStep)
                .toFloat()
                .coerceAtLeast(0f)
        }
        ?.coerceAtMost(safeMin)
        ?: safeMin
    val high = visibleMax
        ?.takeIf { it.isFinite() && it > safeMax - edgePadding }
        ?.let { value ->
            (kotlin.math.ceil((value + edgePadding) / expansionStep) * expansionStep).toFloat()
        }
        ?.coerceAtLeast(safeMax)
        ?: safeMax

    return ChartYRange(min = low, max = high)
}
