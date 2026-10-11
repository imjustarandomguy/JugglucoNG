package tk.glucodata.alerts

import java.math.BigDecimal
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.ui.util.GlucoseFormatter

class ShownGlucoseTests {

    private fun mmol(mgdl: Int): Float = GlucoseFormatter.displayFromMgDl(mgdl.toFloat(), true)

    /** The digits the display prints for [value], as a count of its last digit. */
    private fun printedSteps(value: Float, isMmol: Boolean): Long =
        BigDecimal(String.format(Locale.US, if (isMmol) "%.1f" else "%.0f", value))
            .unscaledValue()
            .toLong()

    @Test
    fun roundsEveryReadingLikeTheDisplay() {
        for (mgdl in 20..600) {
            val shown = mmol(mgdl)
            assertEquals("$mgdl mg/dL in mmol/L", printedSteps(shown, true), ShownGlucose.steps(shown, true))
            assertEquals("$mgdl mg/dL", mgdl.toLong(), ShownGlucose.steps(mgdl.toFloat(), false))
        }
        // Calibrated and smoothed values are not whole mg/dL.
        for (tenths in 200..6000) {
            val value = tenths / 10f
            assertEquals("$value mg/dL", printedSteps(value, false), ShownGlucose.steps(value, false))
            val inMmol = GlucoseFormatter.mgToMmol(value)
            assertEquals("$inMmol mmol/L", printedSteps(inMmol, true), ShownGlucose.steps(inMmol, true))
        }
    }

    @Test
    fun anExactHalfRoundsUpLikeTheDisplay() {
        for (value in listOf(4.25f, 4.75f, 10.25f)) {
            assertEquals("$value", printedSteps(value, true), ShownGlucose.steps(value, true))
        }
        assertEquals(43L, ShownGlucose.steps(4.25f, true))
        for (value in listOf(70.5f, 71.5f, 180.5f)) {
            assertEquals("$value", printedSteps(value, false), ShownGlucose.steps(value, false))
        }
        assertEquals(71L, ShownGlucose.steps(70.5f, false))
    }

    @Test
    fun aReadingShownAtTheThresholdIsNotBelowIt() {
        // 72 mg/dL is 3.996 mmol/L, shown 4.0.
        assertFalse(ShownGlucose.isBelow(mmol(72), 4.0f, true))
        // 71 mg/dL is 3.94, shown 3.9.
        assertTrue(ShownGlucose.isBelow(mmol(71), 4.0f, true))
        assertFalse(ShownGlucose.isBelow(70f, 70f, false))
        assertTrue(ShownGlucose.isBelow(69f, 70f, false))
        // 69.6 is shown 70.
        assertFalse(ShownGlucose.isBelow(69.6f, 70f, false))
    }

    @Test
    fun aReadingShownAtTheThresholdIsNotAboveIt() {
        // 181 mg/dL is 10.05 mmol/L, shown 10.0; 182 is 10.10, shown 10.1.
        assertFalse(ShownGlucose.isAbove(mmol(181), 10.0f, true))
        assertTrue(ShownGlucose.isAbove(mmol(182), 10.0f, true))
        assertFalse(ShownGlucose.isAbove(180f, 180f, false))
        assertTrue(ShownGlucose.isAbove(181f, 180f, false))
        // 180.4 is shown 180.
        assertFalse(ShownGlucose.isAbove(180.4f, 180f, false))
    }

    @Test
    fun theLineIsRoundedLikeTheReading() {
        // 3.9 + 0.5 is a float a hair above 4.4: still the line shown as 4.4.
        assertFalse(ShownGlucose.isBelow(4.4f, 3.9f + 0.5f, true))
        assertTrue(ShownGlucose.isBelow(4.3f, 3.9f + 0.5f, true))
        // A threshold kept in mg/dL after a unit change is compared as shown too.
        assertFalse(ShownGlucose.isBelow(70f, 3.9f * GlucoseFormatter.MGDL_PER_MMOL, false))
    }

    @Test
    fun aValueThatCannotBeShownComparesAsItIs() {
        assertFalse(ShownGlucose.isBelow(Float.NaN, 4.0f, true))
        assertFalse(ShownGlucose.isAbove(Float.NaN, 4.0f, true))
        assertFalse(ShownGlucose.isBelow(4.0f, Float.NaN, true))
        assertTrue(ShownGlucose.isAbove(Float.POSITIVE_INFINITY, 10.0f, true))
    }
}
