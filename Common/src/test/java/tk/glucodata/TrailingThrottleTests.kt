package tk.glucodata

import org.junit.Assert.assertEquals
import org.junit.Test

class TrailingThrottleTests {
    @Test fun firstRequestRunsAtOnce() {
        assertEquals(0L, TrailingThrottle(20_000L).request(5_000L))
    }

    @Test fun requestInsideIntervalIsPostponedToItsEnd() {
        // The companion watch case: the calibration payload's refresh runs, and
        // the reading stored 300 ms later must still reach the complications.
        val throttle = TrailingThrottle(20_000L)
        assertEquals(0L, throttle.request(100_000L))
        assertEquals(19_700L, throttle.request(100_300L))
    }

    @Test fun requestsWhileOneIsPendingCoalesce() {
        val throttle = TrailingThrottle(20_000L)
        throttle.request(0L)
        assertEquals(15_000L, throttle.request(5_000L))
        assertEquals(TrailingThrottle.COVERED, throttle.request(6_000L))
        assertEquals("an overdue run still covers requests until it starts",
            TrailingThrottle.COVERED, throttle.request(25_000L))
    }

    @Test fun deferredRunStartsTheNextInterval() {
        val throttle = TrailingThrottle(20_000L)
        throttle.request(0L)
        throttle.request(1_000L)
        throttle.deferredRunStarting(20_000L)
        assertEquals("a request after the run has started needs its own",
            10_000L, throttle.request(30_000L))
        throttle.deferredRunStarting(40_000L)
        assertEquals(0L, throttle.request(60_000L))
    }

    @Test fun requestAfterIntervalRunsAtOnce() {
        val throttle = TrailingThrottle(20_000L)
        throttle.request(0L)
        assertEquals(0L, throttle.request(20_000L))
        assertEquals(0L, throttle.request(300_000L))
    }
}
