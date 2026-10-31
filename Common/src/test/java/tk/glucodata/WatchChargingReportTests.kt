package tk.glucodata

import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The watch's charger state, which rides on its sensor-ownership reports
 * (`/sync2/own`) so the phone can tell whether "Watch when connected" lets it
 * stay silent. One optional status byte after lastReadingMs, shared with the
 * other fields builds append there; the version byte stays 1.
 */
class WatchChargingReportTests {
    private val serial = "SIBI:0123456789ABCDEF"

    /** Where the status byte is: after the header, the (ASCII) serial and lastReadingMs. */
    private val statusAt = 3 + serial.length + 8

    /** A report as a build that also sends directAgeMs puts it on the wire: the status byte, then the age. */
    private fun withAge(status: Int, directAgeMs: Long = 95_000L): ByteArray =
        SensorOwnershipRuntime.encode(serial, true, 1234L) + byteArrayOf(status.toByte()) +
            ByteBuffer.allocate(8).putLong(directAgeMs).array()

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
    fun theChargerStateIsBitZeroOfTheStatusByte() {
        assertEquals(0x01, SensorOwnershipRuntime.encode(serial, true, 1234L, true)[statusAt].toInt())
        assertEquals(0x00, SensorOwnershipRuntime.encode(serial, true, 1234L, false)[statusAt].toInt())
        assertEquals(statusAt + 1, SensorOwnershipRuntime.encode(serial, true, 1234L, false).size)
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
    fun aStatusByteThatSaysTheChargerStateIsUnknownSaysNothingAboutCharging() {
        val plain = SensorOwnershipRuntime.encode(serial, true, 1234L)
        for (status in listOf(0x04, 0x05, 0x06)) {
            assertNull("status=$status", SensorOwnershipRuntime.decodeCharging(plain + byteArrayOf(status.toByte())))
        }
    }

    @Test
    fun aReportWithTheDirectReadingAgeAfterTheStatusByteReadsTheSame() {
        // A build that also reports each device's newest direct reading puts
        // the status byte before it, with bit 2 when it has no charger state,
        // and bit 1 when it wants an answer.
        assertEquals(true, SensorOwnershipRuntime.decodeCharging(withAge(0x01)))
        assertEquals(false, SensorOwnershipRuntime.decodeCharging(withAge(0x00)))
        assertEquals(true, SensorOwnershipRuntime.decodeCharging(withAge(0x01 or 0x02)))
        assertNull(SensorOwnershipRuntime.decodeCharging(withAge(0x04)))
        assertNull(SensorOwnershipRuntime.decodeCharging(withAge(0x04 or 0x02, directAgeMs = -1L)))
        assertEquals(Triple(serial, true, 1234L), SensorOwnershipRuntime.decode(withAge(0x04 or 0x02)))
    }

    @Test
    fun anUnreadableReportSaysNothingAboutCharging() {
        val report = SensorOwnershipRuntime.encode(serial, true, 1234L, true)
        assertNull(SensorOwnershipRuntime.decodeCharging(null))
        assertNull(SensorOwnershipRuntime.decodeCharging(report.copyOfRange(0, 6)))
        assertNull(SensorOwnershipRuntime.decodeCharging(report.copyOf().also { it[0] = 2 }))
    }
}
