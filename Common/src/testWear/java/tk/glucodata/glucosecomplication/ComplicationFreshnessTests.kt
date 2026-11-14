package tk.glucodata.glucosecomplication

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ComplicationFreshnessTests {
    private val timeout = 330_000L

    @Test fun aShownReadingArmsForTheMomentItGoesStale() {
        assertEquals(1_330_000L, FreshnessDeadline().rearmAt(1_000_000L, timeout))
    }

    @Test fun redrawingTheSameReadingDoesNotTouchTheAlarm() {
        val deadline = FreshnessDeadline()
        deadline.rearmAt(1_000_000L, timeout)
        assertNull(deadline.rearmAt(1_000_000L, timeout))
    }

    @Test fun theNextReadingMovesTheAlarmBeforeItFires() {
        val deadline = FreshnessDeadline()
        deadline.rearmAt(1_000_000L, timeout)
        assertEquals(1_630_000L, deadline.rearmAt(1_300_000L, timeout))
    }

    @Test fun afterTheAlarmFiredTheSameReadingArmsAgain() {
        // An alarm that came early finds the reading still fresh; it must not
        // be left without one.
        val deadline = FreshnessDeadline()
        deadline.rearmAt(1_000_000L, timeout)
        deadline.clear()
        assertEquals(1_330_000L, deadline.rearmAt(1_000_000L, timeout))
    }

    @Test fun aReadingWithoutATimeArmsNothing() {
        assertNull(FreshnessDeadline().rearmAt(0L, timeout))
    }
}
