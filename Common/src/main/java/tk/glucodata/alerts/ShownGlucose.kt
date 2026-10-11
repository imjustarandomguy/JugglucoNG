package tk.glucodata.alerts

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Threshold comparisons on a glucose value as the display shows it.
 *
 * Every surface shows a reading rounded to one decimal in mmol/L and to a
 * whole number in mg/dL ("%.1f" and "%.0f"). Compared unrounded, a reading
 * can be past a threshold while it shows exactly that threshold: 72 mg/dL is
 * 3.996 mmol/L, shown as 4.0, yet below a Low threshold of 4.0, so the alarm
 * rang over a screen that read 4.0. Here both sides are rounded the way the
 * display rounds them: a reading shown at the line is not past it, and one
 * shown past it is.
 */
internal object ShownGlucose {

    /**
     * [value], in display units, counted in its last shown digit: tenths of
     * mmol/L, or whole mg/dL. It rounds half up, as java.util.Formatter does
     * for "%.1f" and "%.0f". The formatter rounds the shortest decimal form of
     * the value and this rounds its exact binary value; for a Float the two
     * always agree, since no Float lies close enough to a half step for them
     * to differ without lying exactly on it.
     */
    fun steps(value: Float, isMmol: Boolean): Long =
        BigDecimal(value.toDouble())
            .setScale(if (isMmol) 1 else 0, RoundingMode.HALF_UP)
            .unscaledValue()
            .toLong()

    /** [value] is shown below [line]. A value that cannot be shown compares as it is. */
    fun isBelow(value: Float, line: Float, isMmol: Boolean): Boolean =
        if (value.isFinite() && line.isFinite()) {
            steps(value, isMmol) < steps(line, isMmol)
        } else {
            value < line
        }

    /** [value] is shown above [line]. A value that cannot be shown compares as it is. */
    fun isAbove(value: Float, line: Float, isMmol: Boolean): Boolean =
        if (value.isFinite() && line.isFinite()) {
            steps(value, isMmol) > steps(line, isMmol)
        } else {
            value > line
        }
}
