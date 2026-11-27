package tk.glucodata.ui.overlay

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class FloatingPillPressIndicationTests {
    @Test
    fun quickTapHoldsForRemainingMinimumDuration() {
        assertEquals(
            FloatingPillPressIndication.MIN_HOLD_MS,
            FloatingPillPressIndication.releaseHoldDelayMs(0L),
        )
        assertEquals(
            FloatingPillPressIndication.MIN_HOLD_MS - 25L,
            FloatingPillPressIndication.releaseHoldDelayMs(25L),
        )
    }

    @Test
    fun longerHoldFadesOutImmediately() {
        assertEquals(
            0L,
            FloatingPillPressIndication.releaseHoldDelayMs(FloatingPillPressIndication.MIN_HOLD_MS),
        )
        assertEquals(
            0L,
            FloatingPillPressIndication.releaseHoldDelayMs(FloatingPillPressIndication.MIN_HOLD_MS + 120L),
        )
    }

    @Test
    fun negativeElapsedClampsToMinimumHold() {
        assertEquals(
            FloatingPillPressIndication.MIN_HOLD_MS,
            FloatingPillPressIndication.releaseHoldDelayMs(-10L),
        )
    }

    @Test
    fun indicationEqualityDependsOnColor() {
        val opaque = FloatingPillPressIndication(Color.White.copy(alpha = 0.16f))
        val opaqueCopy = FloatingPillPressIndication(Color.White.copy(alpha = 0.16f))
        val transparent = FloatingPillPressIndication(Color.White.copy(alpha = 0.24f))

        assertEquals(opaque, opaqueCopy)
        assertEquals(opaque.hashCode(), opaqueCopy.hashCode())
        assertNotEquals(opaque, transparent)
    }
}
