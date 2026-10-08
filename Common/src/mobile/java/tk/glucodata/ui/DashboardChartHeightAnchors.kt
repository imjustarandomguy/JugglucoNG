package tk.glucodata.ui

/**
 * The portrait dashboard chart's three heights, as boosts above the collapsed
 * height: collapsed (0), middle and full. Shared by the pull-down on the list
 * and the handle under the chart, and stored by index rather than in pixels so
 * the chosen height survives a change of screen size.
 */
internal object DashboardChartHeightAnchors {
    const val COLLAPSED = 0
    const val MIDDLE = 1
    const val FULL = 2

    /** In tk.glucodata_preferences, beside the chart's time range. */
    const val PREFERENCE_KEY = "dashboard_chart_height_anchor"

    private const val FAST_FLING = 3000f
    private const val FLING = 400f
    private const val ANCHOR_TOLERANCE_PX = 1f

    fun boostPx(anchor: Int, middlePx: Float, maxPx: Float): Float {
        if (maxPx <= 0f) return 0f
        return when (anchor) {
            MIDDLE -> middlePx.coerceIn(0f, maxPx)
            FULL -> maxPx
            else -> 0f
        }
    }

    /** The anchor [boostPx] sits on, or null between anchors. */
    fun anchorAt(boostPx: Float, middlePx: Float, maxPx: Float): Int? {
        if (maxPx <= 0f) return null
        return when {
            kotlin.math.abs(boostPx) <= ANCHOR_TOLERANCE_PX -> COLLAPSED
            kotlin.math.abs(boostPx - maxPx) <= ANCHOR_TOLERANCE_PX -> FULL
            kotlin.math.abs(boostPx - middlePx.coerceIn(0f, maxPx)) <= ANCHOR_TOLERANCE_PX -> MIDDLE
            else -> null
        }
    }

    /**
     * Where a released drag settles. [velocityY] is px/s, positive downwards, which
     * grows the chart: a strong fling jumps to the end, a moderate one steps one
     * anchor, and a slow release settles on the nearest zone.
     */
    fun snapTargetPx(currentPx: Float, middlePx: Float, maxPx: Float, velocityY: Float): Float {
        if (maxPx <= 0f) return 0f
        val middle = middlePx.coerceIn(0f, maxPx)
        return when {
            velocityY < -FAST_FLING -> 0f
            velocityY < -FLING -> if (currentPx > middle + 10f) middle else 0f
            velocityY > FAST_FLING -> maxPx
            velocityY > FLING -> if (currentPx < middle - 10f) middle else maxPx
            else -> when {
                currentPx <= middle * 0.5f -> 0f
                currentPx < (middle + maxPx) * 0.5f -> middle
                else -> maxPx
            }
        }
    }

    /** Stored anchors from an older or newer build fall back to collapsed. */
    fun fromPreference(value: Int): Int = if (value in COLLAPSED..FULL) value else COLLAPSED
}

/**
 * Which resting heights of the chart are remembered: one the user chose, with the
 * handle or by pulling the list at its top, and not the collapse that scrolling the
 * list brings, which lasts only until the list is back at the top.
 */
internal class DashboardChartHeightChoice {
    private var chosen = false

    /** The user resized the chart: the handle, or the pull on the list at its top. */
    fun onUserResize() {
        chosen = true
    }

    /**
     * The chart is at rest, on [anchor] (null between anchors). Returns the anchor to
     * remember, or null. A rest away from the top of the list ends the choice: the
     * gesture that resized the chart went on to scroll the list.
     */
    fun anchorToRemember(anchor: Int?, listAtTop: Boolean): Int? {
        if (!listAtTop) {
            chosen = false
            return null
        }
        if (!chosen || anchor == null) return null
        chosen = false
        return anchor
    }
}
