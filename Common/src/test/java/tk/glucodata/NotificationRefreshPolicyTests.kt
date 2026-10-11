package tk.glucodata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationRefreshPolicyTests {
    private val time = 1_700_000_000_000L

    @Test fun theCurrentReadingsFreshnessPicksTheOngoingLook() {
        fun look(freshness: DisplayedReading.Freshness, showsValue: Boolean = true, at: Long = time) =
            NotificationRefreshPolicy.ongoingLook(true, freshness, showsValue, at)
        assertEquals(NotificationRefreshPolicy.OngoingLook.READING, look(DisplayedReading.Freshness.FRESH))
        // The next reading is overdue: the value stays, without its arrow.
        assertEquals(NotificationRefreshPolicy.OngoingLook.STALE_READING, look(DisplayedReading.Freshness.STALE))
        // Taken down: "no new value since" alone.
        assertEquals(NotificationRefreshPolicy.OngoingLook.NO_VALUE, look(DisplayedReading.Freshness.NO_VALUE, false))
        assertEquals(NotificationRefreshPolicy.OngoingLook.STATUS, look(DisplayedReading.Freshness.AWAITING, false, 0L))
        assertEquals(NotificationRefreshPolicy.OngoingLook.NO_SENSOR, look(DisplayedReading.Freshness.NO_SENSOR, false, 0L))
    }

    @Test fun withoutGlucoseInTheNotificationOnlyAnOverdueReadingIsSaid() {
        fun look(freshness: DisplayedReading.Freshness) =
            NotificationRefreshPolicy.ongoingLook(false, freshness, true, time)
        assertEquals(NotificationRefreshPolicy.OngoingLook.STATUS, look(DisplayedReading.Freshness.FRESH))
        assertEquals(NotificationRefreshPolicy.OngoingLook.NO_VALUE, look(DisplayedReading.Freshness.STALE))
        assertEquals(NotificationRefreshPolicy.OngoingLook.NO_VALUE, look(DisplayedReading.Freshness.NO_VALUE))
    }

    @Test fun repeatedDataRequestsCannotPostponePendingRefresh() {
        val first = NotificationRefreshPolicy.boundedDebounceAt(0L, 10_000L, 1_000L)
        assertEquals(11_000L, first)
        assertEquals(first, NotificationRefreshPolicy.boundedDebounceAt(first, 10_900L, 1_000L))
        assertEquals("an overdue callback still owns the slot until it runs",
            first, NotificationRefreshPolicy.boundedDebounceAt(first, 11_001L, 1_000L))
        assertEquals(12_001L, NotificationRefreshPolicy.boundedDebounceAt(0L, 11_001L, 1_000L))
    }

    @Test fun statusRefreshSharesFirstDeadlineWinsCoalescing() {
        // Data and status changes share the same bounded trailing debounce (separate
        // pending slots in Notify): while a refresh is pending, continuous events must
        // not postpone it; once it fires, a new event arms a new deadline.
        val first = NotificationRefreshPolicy.boundedDebounceAt(0L, 20_000L, 1_000L)
        assertEquals(21_000L, first)
        assertEquals("pending status requests coalesce without moving the deadline",
            first, NotificationRefreshPolicy.boundedDebounceAt(first, 20_900L, 1_000L))
        assertEquals(first, NotificationRefreshPolicy.boundedDebounceAt(first, 21_001L, 1_000L))
        assertEquals(22_001L, NotificationRefreshPolicy.boundedDebounceAt(0L, 21_001L, 1_000L))
        assertEquals("non-positive delays fire immediately without going backwards",
            20_000L, NotificationRefreshPolicy.boundedDebounceAt(0L, 20_000L, -5L))
    }

    @Test fun oldServiceCannotCancelReplacementWork() {
        val old = Any()
        val replacement = Any()
        assertFalse(NotificationRefreshPolicy.mayCancelForOwner(old, replacement))
        assertTrue(NotificationRefreshPolicy.mayCancelForOwner(replacement, replacement))
        assertTrue(NotificationRefreshPolicy.mayCancelForOwner(null, replacement))
    }

    @Test fun alertwatchOngoingReadingsStayOnPinnedLifecyclePath() {
        assertTrue(NotificationRefreshPolicy.shouldPinOngoingGlucose(
            false, true, false, true))
        assertTrue(NotificationRefreshPolicy.shouldPinOngoingGlucose(
            false, true, true, false))
        assertFalse(NotificationRefreshPolicy.shouldPinOngoingGlucose(
            true, true, false, true))
        assertFalse(NotificationRefreshPolicy.shouldPinOngoingGlucose(
            false, false, false, true))
    }

    @Test fun failedPublicationGetsOnlyOneRetryPerGeneration() {
        assertTrue(NotificationRefreshPolicy.shouldSchedulePublicationRetry(0L, 7L))
        assertFalse(NotificationRefreshPolicy.shouldSchedulePublicationRetry(7L, 7L))
        assertFalse(NotificationRefreshPolicy.shouldSchedulePublicationRetry(0L, 0L))
        assertTrue(NotificationRefreshPolicy.shouldSchedulePublicationRetry(7L, 8L))
    }
}
