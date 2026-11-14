package tk.glucodata.glucosecomplication

import tk.glucodata.TrendArrowAngle

/**
 * The trend arrow written as text, for the surfaces that print it rather than draw it:
 * the drawn arrow's angle ([TrendArrowAngle]) rounded to the nearest of the five
 * directions, doubled past 2 mg/dL/min where the drawn arrow grows its second head.
 * Empty without a rate.
 *
 * The alarm screen used xDrip's direction names instead (flat to 1, double from 3
 * mg/dL/min), so at 1.6 mg/dL/min it wrote ↗ while the drawn arrows point nearly
 * straight up, and at 2.5 a single arrow where they are doubled.
 */
internal fun trendArrowText(rateMgdlPerMin: Float?): String {
    val rate = rateMgdlPerMin?.takeIf { it.isFinite() } ?: return ""
    val rotation = -TrendArrowAngle.rotationDegrees(rate)
    return when {
        rate > 2f -> "↑↑"
        rotation >= 67.5f -> "↑"
        rotation >= 22.5f -> "↗"
        rate < -2f -> "↓↓"
        rotation <= -67.5f -> "↓"
        rotation <= -22.5f -> "↘"
        else -> "→"
    }
}
