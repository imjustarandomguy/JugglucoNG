package tk.glucodata.widget

data class WidgetSizeDp(val width: Int, val height: Int)

/**
 * SMALL: value and arrow centred, one meta line under them (one-row and square
 * sizes, and tall sizes without a chart, which just centre a bigger value).
 * WIDE: a short, wide widget; value on the left, meta lines on the right.
 * TALL: the value row with its meta on top, the chart filling the rest.
 */
enum class WidgetLayout { SMALL, WIDE, TALL }

object WidgetSizing {
    /**
     * The size the host draws the widget at now. AppWidgetManager reports a range:
     * a portrait home screen uses MIN_WIDTH × MAX_HEIGHT, a landscape one
     * MAX_WIDTH × MIN_HEIGHT. A host that reports nothing (some lock-screen hosts)
     * gets the provider's own size.
     */
    fun current(
        minWidth: Int,
        minHeight: Int,
        maxWidth: Int,
        maxHeight: Int,
        portrait: Boolean,
        fallback: WidgetSizeDp,
    ): WidgetSizeDp {
        val width = pick(if (portrait) minWidth else maxWidth, if (portrait) maxWidth else minWidth, fallback.width)
        val height = pick(if (portrait) maxHeight else minHeight, if (portrait) minHeight else maxHeight, fallback.height)
        return WidgetSizeDp(width, height)
    }

    private fun pick(preferred: Int, other: Int, fallback: Int): Int = when {
        preferred > 0 -> preferred
        other > 0 -> other
        else -> fallback
    }
}

/** A text's width in units of its text size. */
fun interface TextMeasure {
    fun em(text: String): Float
}

/** One meta item (reading time, change, IOB, stale notice): its text, then shorter forms to try when space runs out. */
data class MetaItem(val forms: List<String>) {
    constructor(text: String) : this(listOf(text))
}

/** Sizes in dp for one render; see [WidgetGeometryCalculator]. */
data class WidgetGeometry(
    val layout: WidgetLayout,
    val paddingDp: Float,
    val valueSizeDp: Float,
    /** 0 when no arrow is shown. */
    val arrowSizeDp: Float,
    val arrowGapDp: Float,
    val metaSizeDp: Float,
    val metaLines: List<String>,
    /** TALL: the height of the value row above the chart. */
    val headerHeightDp: Float,
    /** 0 when no chart is shown. */
    val chartWidthDp: Float,
    val chartHeightDp: Float,
)

/**
 * Picks the layout for a size and fits the text into it. Pure, so the
 * size buckets can be tested without a launcher.
 *
 * The value row is laid out so the digits themselves fill the height: a text
 * view of size s needs about 1.2 to 1.3 s of height, but a digit is only about
 * 0.7 s tall. The layouts let the value's line box overflow its row (the empty
 * ascent and descent are clipped), so the row height is sized for the digits.
 */
object WidgetGeometryCalculator {
    /** Digit (cap) height in units of the text size; about the same for IBM Plex, Roboto and Google Sans. */
    const val DIGIT_HEIGHT_EM = 0.71f

    /** Line height of a small text view without font padding. */
    const val META_LINE_EM = 1.25f

    const val META_SEPARATOR = " · "

    private const val DIGIT_FILL = 0.8f
    private const val ARROW_EM = 0.72f
    private const val ARROW_GAP_EM = 0.08f
    private const val WIDTH_FILL = 0.96f

    /** At text size 1.0 the value takes this share of the room it could take; up to 1.3 makes it larger. */
    private const val SOFT_FIT = 0.85f
    private const val MIN_META_DP = 8.5f
    private const val MAX_ARROW_DP = 64f

    const val TALL_MIN_HEIGHT_DP = 100
    const val WIDE_MIN_WIDTH_DP = 170
    const val WIDE_MIN_ASPECT = 2.4f
    const val MIN_CHART_HEIGHT_DP = 40f
    private const val CHART_GAP_DP = 4f

    fun layoutFor(size: WidgetSizeDp, chartWanted: Boolean): WidgetLayout = when {
        size.height >= TALL_MIN_HEIGHT_DP ->
            if (chartWanted && tallChartHeight(size, hasBackground = true) >= MIN_CHART_HEIGHT_DP) {
                WidgetLayout.TALL
            } else {
                WidgetLayout.SMALL
            }
        size.width >= WIDE_MIN_WIDTH_DP && size.width >= size.height * WIDE_MIN_ASPECT -> WidgetLayout.WIDE
        else -> WidgetLayout.SMALL
    }

    fun compute(
        size: WidgetSizeDp,
        chartWanted: Boolean,
        hasBackground: Boolean,
        textScale: Float,
        valueText: String,
        valueMeasure: TextMeasure,
        showArrow: Boolean,
        meta: List<MetaItem>,
        metaMeasure: TextMeasure,
    ): WidgetGeometry {
        val scale = if (textScale.isFinite()) {
            textScale.coerceIn(WidgetOptions.MIN_TEXT_SCALE, WidgetOptions.MAX_TEXT_SCALE)
        } else {
            1f
        }
        val padding = padding(size, hasBackground)
        val boxW = (size.width - 2f * padding).coerceAtLeast(1f)
        val boxH = (size.height - 2f * padding).coerceAtLeast(1f)
        val valueEm = valueMeasure.em(valueText).coerceAtLeast(0.5f)
        val input = Input(padding, boxW, boxH, scale, valueEm, showArrow, meta, metaMeasure)
        return when (layoutFor(size, chartWanted)) {
            WidgetLayout.TALL -> tall(input) ?: small(input)
            WidgetLayout.WIDE -> wide(input)
            WidgetLayout.SMALL -> small(input)
        }
    }

    private class Input(
        val padding: Float,
        val boxW: Float,
        val boxH: Float,
        val scale: Float,
        val valueEm: Float,
        val showArrow: Boolean,
        val meta: List<MetaItem>,
        val metaMeasure: TextMeasure,
    )

    private fun padding(size: WidgetSizeDp, hasBackground: Boolean): Float {
        val base = (minOf(size.width, size.height) * 0.1f).coerceIn(3f, 12f)
        // On the bare wallpaper there is no edge to keep clear of.
        return if (hasBackground) base else (base * 0.5f).coerceAtLeast(2f)
    }

    private fun tallChartHeight(size: WidgetSizeDp, hasBackground: Boolean): Float {
        val boxH = size.height - 2f * padding(size, hasBackground)
        return boxH - headerHeight(boxH) - CHART_GAP_DP
    }

    private fun headerHeight(boxH: Float): Float = (boxH * 0.32f).coerceIn(36f, 88f)

    private fun small(input: Input): WidgetGeometry {
        val metaSize = (input.boxH * 0.2f).coerceIn(9f, 14f) * input.scale
        var line = fitLine(input.meta, metaSize, input.boxW, input.metaMeasure)
        var valueBoxH = input.boxH
        if (line != null) {
            valueBoxH = input.boxH - metaSize * META_LINE_EM
            // Not at the cost of a value smaller than twice the meta line.
            if (valueBoxH < metaSize * META_LINE_EM * 2f) {
                line = null
                valueBoxH = input.boxH
            }
        }
        val valueSize = valueSize(input.boxW, valueBoxH, input)
        return WidgetGeometry(
            layout = WidgetLayout.SMALL,
            paddingDp = input.padding,
            valueSizeDp = valueSize,
            arrowSizeDp = arrowSize(valueSize, input.showArrow),
            arrowGapDp = valueSize * ARROW_GAP_EM,
            metaSizeDp = if (line != null) metaSize else 0f,
            metaLines = listOfNotNull(line),
            headerHeightDp = 0f,
            chartWidthDp = 0f,
            chartHeightDp = 0f,
        )
    }

    private fun wide(input: Input): WidgetGeometry {
        val row = valueRow(input, input.boxW, input.boxH)
        return WidgetGeometry(
            layout = WidgetLayout.WIDE,
            paddingDp = input.padding,
            valueSizeDp = row.valueSize,
            arrowSizeDp = arrowSize(row.valueSize, input.showArrow),
            arrowGapDp = row.valueSize * ARROW_GAP_EM,
            metaSizeDp = row.metaSize,
            metaLines = row.lines,
            headerHeightDp = 0f,
            chartWidthDp = 0f,
            chartHeightDp = 0f,
        )
    }

    private fun tall(input: Input): WidgetGeometry? {
        val header = headerHeight(input.boxH)
        val chartHeight = input.boxH - header - CHART_GAP_DP
        if (chartHeight < MIN_CHART_HEIGHT_DP) return null
        val row = valueRow(input, input.boxW, header)
        return WidgetGeometry(
            layout = WidgetLayout.TALL,
            paddingDp = input.padding,
            valueSizeDp = row.valueSize,
            arrowSizeDp = arrowSize(row.valueSize, input.showArrow),
            arrowGapDp = row.valueSize * ARROW_GAP_EM,
            metaSizeDp = row.metaSize,
            metaLines = row.lines,
            headerHeightDp = header,
            chartWidthDp = input.boxW,
            chartHeightDp = chartHeight,
        )
    }

    private class Row(val valueSize: Float, val metaSize: Float, val lines: List<String>)

    /** Value on the left, meta lines stacked on the right, in a [width] × [height] box. */
    private fun valueRow(input: Input, width: Float, height: Float): Row {
        var column = metaColumn(input.meta, height, input.scale, input.metaMeasure)
        var metaWidth = column.lines.maxOfOrNull { input.metaMeasure.em(it) * column.size } ?: 0f
        // The value comes first: a meta column that would squeeze it below 45 % of the
        // width keeps only its first item, and goes when even that does not fit.
        if (column.lines.isNotEmpty() && width - metaWidth < width * 0.45f) {
            column = metaColumn(input.meta.take(1), height, input.scale, input.metaMeasure)
            metaWidth = column.lines.maxOfOrNull { input.metaMeasure.em(it) * column.size } ?: 0f
            if (width - metaWidth < width * 0.45f) {
                column = MetaColumn(0f, emptyList())
                metaWidth = 0f
            }
        }
        val gap = if (column.lines.isEmpty()) 0f else maxOf(6f, width * 0.04f)
        val valueSize = valueSize(width - metaWidth - gap, height, input)
        return Row(valueSize, column.size, column.lines)
    }

    private class MetaColumn(val size: Float, val lines: List<String>)

    /** One item per line when they fit at a readable size; otherwise the last lines are joined. */
    private fun metaColumn(items: List<MetaItem>, height: Float, scale: Float, measure: TextMeasure): MetaColumn {
        if (items.isEmpty()) return MetaColumn(0f, emptyList())
        val preferred = (height * 0.26f).coerceIn(9f, 15f) * scale
        for (lineCount in items.size downTo 1) {
            val size = minOf(preferred, height / (lineCount * META_LINE_EM))
            if (size < MIN_META_DP) continue
            val lines = items.take(lineCount - 1).map { it.forms.first() } +
                items.drop(lineCount - 1).joinToString(META_SEPARATOR) { it.forms.first() }
            return MetaColumn(size, lines)
        }
        return MetaColumn(0f, emptyList())
    }

    /** As many items as fit on one line, in order; a lone item may fall back to a shorter form. */
    private fun fitLine(items: List<MetaItem>, size: Float, width: Float, measure: TextMeasure): String? {
        if (items.isEmpty()) return null
        for (count in items.size downTo 1) {
            val line = items.take(count).joinToString(META_SEPARATOR) { it.forms.first() }
            if (measure.em(line) * size <= width) return line
        }
        return items.first().forms.drop(1).firstOrNull { measure.em(it) * size <= width }
    }

    private fun valueSize(width: Float, height: Float, input: Input): Float {
        val arrowEm = if (input.showArrow) ARROW_EM + ARROW_GAP_EM else 0f
        val byHeight = height * DIGIT_FILL / DIGIT_HEIGHT_EM
        val byWidth = width.coerceAtLeast(1f) * WIDTH_FILL / (input.valueEm + arrowEm)
        val fit = minOf(byHeight, byWidth)
        return minOf(fit, fit * SOFT_FIT * input.scale).coerceAtLeast(1f)
    }

    private fun arrowSize(valueSize: Float, showArrow: Boolean): Float =
        if (showArrow) minOf(valueSize * ARROW_EM, MAX_ARROW_DP) else 0f
}
