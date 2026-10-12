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

    @Test fun newerReadingRunsAtOnceInsideTheInterval() {
        val throttle = TrailingThrottle(20_000L)
        assertEquals(0L, throttle.request(100_000L, key = 1_000L))
        assertEquals(0L, throttle.request(100_300L, key = 61_000L))
    }

    @Test fun newerReadingRunsAtOnceWhileARepeatIsPending() {
        val throttle = TrailingThrottle(20_000L)
        throttle.request(0L, key = 1_000L)
        assertEquals(15_000L, throttle.request(5_000L))
        assertEquals(0L, throttle.request(6_000L, key = 2_000L))
        assertEquals("the newer reading's run replaced the pending one",
            19_000L, throttle.request(7_000L, key = 2_000L))
    }

    @Test fun repeatsOfTheSameReadingCoalesce() {
        val throttle = TrailingThrottle(20_000L)
        assertEquals(0L, throttle.request(0L, key = 1_000L))
        assertEquals(15_000L, throttle.request(5_000L, key = 1_000L))
        assertEquals(TrailingThrottle.COVERED, throttle.request(6_000L, key = 1_000L))
        assertEquals("an older reading is a repeat too",
            TrailingThrottle.COVERED, throttle.request(7_000L, key = 500L))
    }

    @Test fun complicationKeyIsTheReadingTimeOnlyWhileItCanBeShown() {
        val timeout = 10L * 60_000L
        val now = 1_700_000_000_000L
        assertEquals(now - 30_000L, UiRefreshBus.complicationKey(now - 30_000L, now, timeout))
        assertEquals(TrailingThrottle.NO_KEY, UiRefreshBus.complicationKey(now - timeout, now, timeout))
        assertEquals(TrailingThrottle.NO_KEY, UiRefreshBus.complicationKey(0L, now, timeout))
    }
}
