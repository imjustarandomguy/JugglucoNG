package tk.glucodata.ui

internal data class ChartYRange(
    val min: Float,
    val max: Float
)

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
        ?.takeIf { it.isFinite() && it > 0.1f && it < safeMin }
        ?.let { value ->
            (kotlin.math.floor((value - edgePadding) / expansionStep) * expansionStep)
                .toFloat()
                .coerceAtLeast(0f)
        }
        ?.coerceAtMost(safeMin)
        ?: safeMin
    val high = visibleMax
        ?.takeIf { it.isFinite() && it > safeMax }
        ?.let { value ->
            (kotlin.math.ceil((value + edgePadding) / expansionStep) * expansionStep).toFloat()
        }
        ?.coerceAtLeast(safeMax)
        ?: safeMax

    return ChartYRange(min = low, max = high)
}

/**
 * The highest top a manual y-axis drag may reach: 22 mmol/L or 400 mg/dL, about
 * where sensors stop reporting. Auto-expansion may still go past it, but only to
 * show a reading that is itself above it.
 */
internal fun manualChartYMaxCap(isMmol: Boolean): Float = if (isMmol) 22f else 400f

internal fun manuallyAdjustedChartYRange(
    startMin: Float,
    startMax: Float,
    totalDragY: Float,
    chartHeight: Float,
    adjustsMax: Boolean,
    minimumSpan: Float,
    maximumMax: Float = Float.POSITIVE_INFINITY
): ChartYRange {
    if (
        !startMin.isFinite() ||
        !startMax.isFinite() ||
        startMax <= startMin ||
        !totalDragY.isFinite() ||
        !chartHeight.isFinite() ||
        chartHeight <= 0f
    ) {
        return ChartYRange(min = startMin, max = startMax)
    }

    val safeMinimumSpan = minimumSpan.takeIf { it.isFinite() && it > 0f } ?: 0.1f
    val safeMaximumMax = maximumMax.takeIf { !it.isNaN() && it > safeMinimumSpan } ?: Float.POSITIVE_INFINITY
    val scaleDelta = totalDragY * (startMax - startMin) / chartHeight * 2f
    return if (adjustsMax) {
        val max = (startMax + scaleDelta)
            .coerceAtLeast(startMin + safeMinimumSpan)
            .coerceAtMost(safeMaximumMax)
        ChartYRange(
            // Only moves when the cap leaves less than the minimum span above it.
            min = startMin.coerceAtMost((max - safeMinimumSpan).coerceAtLeast(0f)),
            max = max
        )
    } else {
        // The start may be an auto-expanded top; the manual baseline still keeps under the cap.
        val max = startMax.coerceAtMost(safeMaximumMax)
        ChartYRange(
            min = (startMin + scaleDelta)
                .coerceIn(0f, (max - safeMinimumSpan).coerceAtLeast(0f)),
            max = max
        )
    }
}
