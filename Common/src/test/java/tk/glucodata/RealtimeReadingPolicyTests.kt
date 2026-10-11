package tk.glucodata

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimeReadingPolicyTests {

    @Test
    fun staleHistoryCallbackCannotRegressRealtimeSurfaces() {
        assertFalse(
            RealtimeReadingPolicy.shouldDispatch(
                1_782_006_372_000L,
                1_782_133_332_000L,
                1_782_133_332_000L
            )
        )
    }

    @Test
    fun equalAndNewerReadingsRemainDispatchable() {
        assertTrue(RealtimeReadingPolicy.shouldDispatch(2_000L, 2_000L, 2_000L))
        assertTrue(RealtimeReadingPolicy.shouldDispatch(2_001L, 2_000L, 2_000L))
    }

    @Test
    fun nativeHighWaterAllowsSubSecondLiveTimestampSkew() {
        assertTrue(
            RealtimeReadingPolicy.shouldDispatch(
                1_783_587_719_998L,
                1_783_587_660_050L,
                1_783_587_720_000L
            )
        )
    }

    @Test
    fun inMemoryHighWaterStillBlocksOlderCallbacks() {
        assertFalse(
            RealtimeReadingPolicy.shouldDispatch(
                1_783_587_719_998L,
                1_783_587_720_000L,
                1_783_587_720_000L
            )
        )
    }

    @Test
    fun nativeHighWaterProtectsAfterProcessStateReset() {
        assertFalse(RealtimeReadingPolicy.shouldDispatch(1_000L, 0L, 2_000L))
    }

    @Test
    fun nativeKeepsALiveReadingUnderItsOwnSecond() {
        assertTrue(RealtimeReadingPolicy.isNativeCopy(1_783_587_719_998L, 1_783_587_720_000L))
        assertTrue(RealtimeReadingPolicy.isNativeCopy(1_783_587_720_400L, 1_783_587_720_000L))
        // The reading before, a minute back, is not this one.
        assertFalse(RealtimeReadingPolicy.isNativeCopy(1_783_587_720_000L, 1_783_587_660_000L))
        assertFalse(RealtimeReadingPolicy.isNativeCopy(1_783_587_720_000L, 0L))
    }

    @Test
    fun aReadingTheOtherDeviceHandedOverIsNotHandledAgainWhenThisOneReadsIt() {
        val pushed = 1_783_587_720_000L
        // The watch's own session reads the G7 reading the phone's push brought a few seconds earlier.
        assertTrue(RealtimeReadingPolicy.isOtherDevicesCopy(pushed + 400L, false, pushed, true))
        // And the other way round: this device read it, the push comes after.
        assertTrue(RealtimeReadingPolicy.isOtherDevicesCopy(pushed - 600L, true, pushed, false))
        // The next five-minute reading is a reading of its own.
        assertFalse(RealtimeReadingPolicy.isOtherDevicesCopy(pushed + 300_000L, false, pushed, true))
    }

    @Test
    fun twoReadingsOfOneDeviceKeepTheirOldRule() {
        val last = 1_783_587_720_000L
        assertFalse(RealtimeReadingPolicy.isOtherDevicesCopy(last, false, last, false))
        assertFalse(RealtimeReadingPolicy.isOtherDevicesCopy(last + 1_000L, true, last, true))
        assertFalse(RealtimeReadingPolicy.isOtherDevicesCopy(last, true, 0L, false))
    }
}
