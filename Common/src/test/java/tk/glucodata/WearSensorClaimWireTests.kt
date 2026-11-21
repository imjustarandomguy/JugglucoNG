package tk.glucodata

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The watch's own "Read on the watch" switch tells the phone with one byte after
 * the claim state, so the phone's "Direct sensor on watch" switch goes off or on
 * too. Every other claim status stays the one byte older phones read, and a
 * phone reads only the first byte, so an older phone reads the longer payload as
 * the plain state.
 */
class WearSensorClaimWireTests {
    @Test fun plainStatusIsTheOneByteOlderBuildsSend() {
        WearSensorClaimState.entries.forEach { state ->
            val payload = WearSensorClaimWire.encode(state, stoppedOnWatch = false)
            assertArrayEquals(byteArrayOf(state.wireValue.toByte()), payload)
            assertFalse(WearSensorClaimWire.stoppedOnWatch(payload))
            assertFalse(WearSensorClaimWire.startedOnWatch(payload))
        }
    }

    @Test fun startOnWatchStillReadsAsRequestingToAnOlderPhone() {
        val payload = WearSensorClaimWire.encode(
            WearSensorClaimState.REQUESTING, stoppedOnWatch = false, startedOnWatch = true,
        )
        assertTrue(WearSensorClaimWire.startedOnWatch(payload))
        assertFalse(WearSensorClaimWire.stoppedOnWatch(payload))
        assertEquals(WearSensorClaimState.REQUESTING, WearSensorClaimState.fromWireValue(payload[0].toInt()))
    }

    @Test fun onlyAStartCountsAsStartedOnWatch() {
        assertFalse(WearSensorClaimWire.startedOnWatch(null))
        assertFalse(WearSensorClaimWire.startedOnWatch(byteArrayOf(1)))
        // A stop is never a start, nor a "started" byte on a watch that says it reads nothing.
        assertFalse(
            WearSensorClaimWire.startedOnWatch(
                WearSensorClaimWire.encode(WearSensorClaimState.PHONE_OWNS, stoppedOnWatch = true),
            ),
        )
        assertFalse(
            WearSensorClaimWire.startedOnWatch(
                byteArrayOf(WearSensorClaimState.PHONE_OWNS.wireValue.toByte(), WearSensorClaimWire.STARTED_ON_WATCH),
            ),
        )
        assertTrue(
            WearSensorClaimWire.startedOnWatch(
                byteArrayOf(WearSensorClaimState.CONNECTED.wireValue.toByte(), WearSensorClaimWire.STARTED_ON_WATCH),
            ),
        )
    }

    @Test fun stopOnWatchStillReadsAsPhoneOwnsToAnOlderPhone() {
        val payload = WearSensorClaimWire.encode(WearSensorClaimState.PHONE_OWNS, stoppedOnWatch = true)
        assertTrue(WearSensorClaimWire.stoppedOnWatch(payload))
        assertEquals(WearSensorClaimState.PHONE_OWNS, WearSensorClaimState.fromWireValue(payload[0].toInt()))
    }

    @Test fun onlyAStopCountsAsStoppedOnWatch() {
        assertFalse(WearSensorClaimWire.stoppedOnWatch(null))
        assertFalse(WearSensorClaimWire.stoppedOnWatch(byteArrayOf()))
        assertFalse(WearSensorClaimWire.stoppedOnWatch(byteArrayOf(0)))
        assertFalse(WearSensorClaimWire.stoppedOnWatch(byteArrayOf(0, 0)))
        assertFalse(
            WearSensorClaimWire.stoppedOnWatch(
                byteArrayOf(WearSensorClaimState.REQUESTING.wireValue.toByte(), WearSensorClaimWire.STOPPED_ON_WATCH),
            ),
        )
        assertFalse(
            WearSensorClaimWire.stoppedOnWatch(
                byteArrayOf(WearSensorClaimState.CONNECTED.wireValue.toByte(), WearSensorClaimWire.STOPPED_ON_WATCH),
            ),
        )
    }
}
