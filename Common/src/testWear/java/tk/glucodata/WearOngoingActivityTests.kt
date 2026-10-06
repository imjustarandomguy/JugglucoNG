package tk.glucodata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The value the watch's ongoing activity shows, and when it stops showing it. */
class WearOngoingActivityTests {
    private val timeout = 330_000L
    private val reading = 1_000_000L

    @Test
    fun aCurrentReadingIsShown() {
        assertEquals("7.6", WearOngoingActivity.freshValueText("7.6", reading, reading + 60_000L, timeout))
        assertEquals("7.6", WearOngoingActivity.freshValueText("7.6", reading, reading + timeout, timeout))
    }

    @Test
    fun aReadingOlderThanTheTimeoutIsNotShown() {
        assertNull(WearOngoingActivity.freshValueText("7.6", reading, reading + timeout + 1L, timeout))
        // The resolver's history fallback reaches well past the timeout.
        assertNull(WearOngoingActivity.freshValueText("7.6", reading, reading + 20L * 60_000L, timeout))
    }

    @Test
    fun noValueOrNoTimestampIsNotShown() {
        assertNull(WearOngoingActivity.freshValueText(null, reading, reading, timeout))
        assertNull(WearOngoingActivity.freshValueText(" ", reading, reading, timeout))
        assertNull(WearOngoingActivity.freshValueText("7.6", 0L, reading, timeout))
    }
}
