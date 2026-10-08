package tk.glucodata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The watch's charger state, which rides on its sensor-ownership reports
 * (`/sync2/own`) so the phone can tell whether "Watch when connected" lets it
 * stay silent. One optional trailing byte; the version byte stays 1.
 */
class WatchChargingReportTests {
    private val serial = "SIBI:0123456789ABCDEF"

    @Test
    fun aReportWithTheChargerStateStillReadsTheSameForEveryDecoder() {
        // decode is the reader every build has: it reads its own fields and
        // ignores what follows, so an older phone gets the report unchanged.
        for (charging in listOf(true, false, null)) {
            assertEquals(
                "charging=$charging",
                Triple(serial, true, 1234L),
                SensorOwnershipRuntime.decode(SensorOwnershipRuntime.encode(serial, true, 1234L, charging)),
            )
        }
    }

    @Test
    fun theChargerStateRoundTrips() {
        assertEquals(true, SensorOwnershipRuntime.decodeCharging(SensorOwnershipRuntime.encode(serial, false, 0L, true)))
        assertEquals(false, SensorOwnershipRuntime.decodeCharging(SensorOwnershipRuntime.encode(serial, true, 99L, false)))
    }

    @Test
    fun aReportFromAnOlderWatchSaysNothingAboutCharging() {
        // No trailing byte: unknown, so the phone rings.
        assertNull(SensorOwnershipRuntime.decodeCharging(SensorOwnershipRuntime.encode(serial, true, 1234L)))
        assertEquals(
            SensorOwnershipRuntime.encode(serial, true, 1234L).size + 1,
            SensorOwnershipRuntime.encode(serial, true, 1234L, false).size,
        )
    }

    @Test
    fun anUnreadableReportSaysNothingAboutCharging() {
        val report = SensorOwnershipRuntime.encode(serial, true, 1234L, true)
        assertNull(SensorOwnershipRuntime.decodeCharging(null))
        assertNull(SensorOwnershipRuntime.decodeCharging(report.copyOfRange(0, 6)))
        assertNull(SensorOwnershipRuntime.decodeCharging(report.copyOf().also { it[0] = 2 }))
    }
}
