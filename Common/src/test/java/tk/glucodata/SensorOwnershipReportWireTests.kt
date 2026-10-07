package tk.glucodata

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The ownership report gained each device's newest direct reading as a field
 * appended to it, with the version left alone. Phone and watch are updated
 * separately, so both directions have to keep working with a build that does
 * not know the field: an older receiver ignores it, an older sender reads as
 * "unknown". A version bump would instead have made each side drop the other's
 * reports, and the arbitration with them (see WearPayloadVersionPolicyTests).
 */
class SensorOwnershipReportWireTests {
    private val serial = "12147739749"

    /** What builds before the field put on the wire. */
    private fun legacyPayload(owns: Boolean, lastReadingMs: Long, sensor: String = serial): ByteArray {
        val serialBytes = sensor.toByteArray(StandardCharsets.UTF_8)
        return ByteBuffer.allocate(3 + serialBytes.size + 8)
            .put(1)
            .put(if (owns) 1 else 0)
            .put(serialBytes.size.toByte())
            .put(serialBytes)
            .putLong(lastReadingMs)
            .array()
    }

    /** The decoder builds before the field run, verbatim but for the serial check. */
    private fun legacyDecode(data: ByteArray?): Triple<String, Boolean, Long>? {
        val bytes = data ?: return null
        if (bytes.size < 11) return null
        val buffer = ByteBuffer.wrap(bytes)
        if (buffer.get().toInt() != 1) return null
        val owns = buffer.get().toInt() != 0
        val length = buffer.get().toInt() and 0xFF
        if (length == 0 || buffer.remaining() < length + 8) return null
        val serialBytes = ByteArray(length)
        buffer.get(serialBytes)
        val serial = String(serialBytes, StandardCharsets.UTF_8)
        if (serial.isBlank()) return null
        return Triple(serial, owns, buffer.long)
    }

    @Test
    fun theNewReportCarriesTheDirectReadingAge() {
        val report = SensorOwnershipRuntime.decodeReport(
            SensorOwnershipRuntime.encode(serial, owns = true, lastReadingMs = 1234L, directAgeMs = 95_000L),
        )
        assertEquals(SensorOwnershipRuntime.Report(serial, true, 1234L, 95_000L), report)
    }

    @Test
    fun neverReadItselfTravelsAsMinusOne() {
        val report = SensorOwnershipRuntime.decodeReport(
            SensorOwnershipRuntime.encode(serial, owns = false, lastReadingMs = 0L, directAgeMs = -1L),
        )
        assertEquals(-1L, report?.directAgeMs)
    }

    @Test
    fun anOlderPeersReportStillDecodesAndItsDirectReadingIsUnknown() {
        val report = SensorOwnershipRuntime.decodeReport(legacyPayload(owns = true, lastReadingMs = 1234L))
        assertEquals(SensorOwnershipRuntime.Report(serial, true, 1234L, null), report)
        assertEquals(DirectReadingFact.UNKNOWN, DirectReadingStatus.fromWire(report?.directAgeMs, 5_000L))
    }

    @Test
    fun anOlderPeerReadsTheNewReportAsBefore() {
        val payload = SensorOwnershipRuntime.encode(serial, owns = true, lastReadingMs = 1234L, directAgeMs = 95_000L)
        assertEquals(Triple(serial, true, 1234L), legacyDecode(payload))
    }

    @Test
    fun theNewReportIsTheOldOneWithTheFieldAppended() {
        val payload = SensorOwnershipRuntime.encode(serial, owns = false, lastReadingMs = 42L, directAgeMs = 7L)
        val legacy = legacyPayload(owns = false, lastReadingMs = 42L)
        assertArrayEquals(legacy, payload.copyOfRange(0, legacy.size))
        assertEquals(legacy.size + 8, payload.size)
        assertEquals(1, payload[0].toInt())
    }

    @Test
    fun theTripleDecoderIsUnchangedForBothLayouts() {
        assertEquals(Triple(serial, true, 1234L), SensorOwnershipRuntime.decode(legacyPayload(true, 1234L)))
        assertEquals(
            Triple(serial, true, 1234L),
            SensorOwnershipRuntime.decode(SensorOwnershipRuntime.encode(serial, true, 1234L, 1L)),
        )
    }

    @Test
    fun aTruncatedFieldIsIgnoredNotMisread() {
        val payload = SensorOwnershipRuntime.encode(serial, owns = true, lastReadingMs = 1234L, directAgeMs = 95_000L)
        val report = SensorOwnershipRuntime.decodeReport(payload.copyOfRange(0, payload.size - 3))
        assertEquals(SensorOwnershipRuntime.Report(serial, true, 1234L, null), report)
    }

    @Test
    fun aDifferentVersionIsStillDropped() {
        val payload = SensorOwnershipRuntime.encode(serial, owns = true, lastReadingMs = 1234L, directAgeMs = 1L)
        payload[0] = 2
        assertNull(SensorOwnershipRuntime.decodeReport(payload))
    }
}
