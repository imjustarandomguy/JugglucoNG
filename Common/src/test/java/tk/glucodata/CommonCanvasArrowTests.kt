package tk.glucodata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The floating glucose and the round complication draw their arrows here; they must
 * point where every other arrow points for the same rate.
 */
class CommonCanvasArrowTests {

    @Test
    fun legacyArrowTurnsLikeTheSharedArrow() {
        var rate = -4f
        while (rate <= 4f) {
            assertEquals("rate $rate", TrendArrowAngle.rotationDegrees(rate), CommonCanvas.arrowRotationDegrees(rate), 0f)
            rate += 0.05f
        }
        // A slow drift is flat: 0.3 mg/dL/min is under the 0.5 line, in either unit.
        assertEquals(0f, CommonCanvas.arrowRotationDegrees(0.3f), 0f)
        assertEquals(-45f, CommonCanvas.arrowRotationDegrees(1f), 0.001f)
        assertEquals(90f, CommonCanvas.arrowRotationDegrees(-2.5f), 0.001f)
    }

    @Test
    fun doubleHeadOnlyPastTwoMgdlPerMinute() {
        assertFalse(CommonCanvas.showsDoubleArrow(0.3f))
        assertFalse(CommonCanvas.showsDoubleArrow(2f))
        assertFalse(CommonCanvas.showsDoubleArrow(-2f))
        assertTrue(CommonCanvas.showsDoubleArrow(2.1f))
        assertTrue(CommonCanvas.showsDoubleArrow(-2.1f))
    }

    @Test
    fun circleArrowSpansTheCanvasAtTheSharedAngle() {
        // Flat: across the full width, through the centre.
        assertEnds(doubleArrayOf(0.0, 50.0, 100.0, 50.0), CommonCanvas.circleArrowEnds(100.0, 100.0, 0.3f))
        // Vertical from 2 mg/dL/min, tip up when rising and down when falling.
        assertEnds(doubleArrayOf(50.0, 100.0, 50.0, 0.0), CommonCanvas.circleArrowEnds(100.0, 100.0, 3f))
        assertEnds(doubleArrayOf(50.0, 0.0, 50.0, 100.0), CommonCanvas.circleArrowEnds(100.0, 100.0, -2f))
        // 45 degrees at 1 mg/dL/min: tip up and to the right, on the inscribed circle.
        val ends = CommonCanvas.circleArrowEnds(100.0, 100.0, 1f)
        val reach = 50.0 / Math.sqrt(2.0)
        assertEnds(doubleArrayOf(50.0 - reach, 50.0 + reach, 50.0 + reach, 50.0 - reach), ends)
    }

    private fun assertEnds(expected: DoubleArray, actual: DoubleArray) {
        assertEquals(expected.size, actual.size)
        expected.indices.forEach { assertEquals("coordinate $it", expected[it], actual[it], 1e-6) }
    }
}
