package tk.glucodata.glucosecomplication

import org.junit.Assert.assertEquals
import org.junit.Test

class TrendArrowTextTests {

    @Test fun flatInsideTheSharedArrowsFlatBand() {
        assertEquals("→", trendArrowText(0f))
        assertEquals("→", trendArrowText(0.5f))
        assertEquals("→", trendArrowText(-0.7f))
    }

    @Test fun followsTheDrawnArrowsAngle() {
        // 22.5 and 67.5 degrees: halfway between the drawn arrow's flat, 45 and vertical.
        assertEquals("↗", trendArrowText(0.75f))
        assertEquals("↗", trendArrowText(1.2f))
        assertEquals("↑", trendArrowText(1.5f))
        assertEquals("↑", trendArrowText(2f))
        assertEquals("↘", trendArrowText(-1f))
        assertEquals("↓", trendArrowText(-1.6f))
    }

    @Test fun doubledWhereTheDrawnArrowGrowsItsSecondHead() {
        assertEquals("↑↑", trendArrowText(2.5f))
        assertEquals("↓↓", trendArrowText(-2.1f))
    }

    @Test fun noRateNoArrow() {
        assertEquals("", trendArrowText(null))
        assertEquals("", trendArrowText(Float.NaN))
    }
}
