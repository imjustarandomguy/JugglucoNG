package tk.glucodata

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The watch's own "Return sensor to phone" / "Stop reading on the watch" tells
 * the phone with one byte after the claim state, so the phone's "Direct sensor
 * on watch" switch goes off too. Every other claim status stays the one byte
 * older phones read, and a phone reads only the first byte, so an older phone
 * reads the longer payload as the plain state.
 */
class WearSensorClaimWireTests {
    @Test fun plainStatusIsTheOneByteOlderBuildsSend() {
        WearSensorClaimState.entries.forEach { state ->
            val payload = WearSensorClaimWire.encode(state, stoppedOnWatch = false)
            assertArrayEquals(byteArrayOf(state.wireValue.toByte()), payload)
            assertFalse(WearSensorClaimWire.stoppedOnWatch(payload))
        }
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
