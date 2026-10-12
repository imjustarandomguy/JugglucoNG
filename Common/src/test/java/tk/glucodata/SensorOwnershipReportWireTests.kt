package tk.glucodata

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The ownership report gained fields appended to it, with the version left
 * alone: a status byte, then each device's newest direct reading. Phone and
 * watch are updated separately, so both directions have to keep working with a
 * build that does not know a field: an older receiver ignores it, an older
 * sender reads as "unknown". A version bump would instead have made each side
 * drop the other's reports, and the arbitration with them (see
 * WearPayloadVersionPolicyTests).
 *
 * The status byte is the one builds that send the watch's charger state put
 * right after lastReadingMs, alone; this build has no charger state to give,
 * and says so in it.
 */
class SensorOwnershipReportWireTests {
    private val serial = "12345678901"

    /** Where the status byte is: after the header, the (ASCII) serial and lastReadingMs. */
    private val statusAt = 3 + serial.length + 8

    /** What builds before the fields put on the wire. */
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

    /** What a build that sends only the charger state puts on the wire: the status byte, nothing after. */
    private fun statusOnlyPayload(owns: Boolean, lastReadingMs: Long, status: Int): ByteArray =
        legacyPayload(owns, lastReadingMs) + byteArrayOf(status.toByte())

    /** The decoder builds before the fields run, verbatim but for the serial check. */
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

    /** How a build that knows only the status byte reads the charger state off it. */
    private fun statusOnlyCharging(data: ByteArray): Boolean? {
        if (legacyDecode(data) == null || data.size <= statusAt) return null
        val status = data[statusAt].toInt()
        if (status and 0x04 != 0) return null
        return status and 0x01 != 0
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
    fun theNewReportIsTheOldOneWithTheStatusAndTheAgeAppended() {
        val payload = SensorOwnershipRuntime.encode(serial, owns = false, lastReadingMs = 42L, directAgeMs = 7L)
        val legacy = legacyPayload(owns = false, lastReadingMs = 42L)
        assertArrayEquals(legacy, payload.copyOfRange(0, legacy.size))
        assertEquals(legacy.size + 1 + 8, payload.size)
        // No charger state from here: bit 2, "unknown".
        assertEquals(0x04, payload[statusAt].toInt())
        assertEquals(7L, ByteBuffer.wrap(payload, statusAt + 1, 8).long)
        assertEquals(1, payload[0].toInt())
    }

    @Test
    fun theTripleDecoderIsUnchangedForBothLayouts() {
        assertEquals(Triple(serial, true, 1234L), SensorOwnershipRuntime.decode(legacyPayload(true, 1234L)))
        assertEquals(
            Triple(serial, true, 1234L),
            SensorOwnershipRuntime.decode(SensorOwnershipRuntime.encode(serial, true, 1234L, 1L)),
        )
        assertEquals(Triple(serial, true, 1234L), SensorOwnershipRuntime.decode(statusOnlyPayload(true, 1234L, 0x01)))
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

    // A device that has just started asks the other to answer at once, so it
    // can ask for the readings it missed while it was down.

    @Test
    fun anAnswerRequestIsABitOfTheStatusByte() {
        val payload = SensorOwnershipRuntime.encode(
            serial, owns = false, lastReadingMs = 42L, directAgeMs = 7L, answerRequested = true,
        )
        val plain = SensorOwnershipRuntime.encode(serial, owns = false, lastReadingMs = 42L, directAgeMs = 7L)
        assertEquals(plain.size, payload.size)
        assertEquals(0x02 or 0x04, payload[statusAt].toInt())
        assertArrayEquals(plain.copyOfRange(0, statusAt), payload.copyOfRange(0, statusAt))
        assertArrayEquals(plain.copyOfRange(statusAt + 1, plain.size), payload.copyOfRange(statusAt + 1, payload.size))
        assertEquals(
            SensorOwnershipRuntime.Report(serial, false, 42L, 7L, answerRequested = true),
            SensorOwnershipRuntime.decodeReport(payload),
        )
    }

    @Test
    fun aReportThatAsksNothingIsTheSameAsBefore() {
        val report = SensorOwnershipRuntime.decodeReport(
            SensorOwnershipRuntime.encode(serial, owns = true, lastReadingMs = 1234L, directAgeMs = 95_000L),
        )
        assertEquals(false, report?.answerRequested)
        assertEquals(false, SensorOwnershipRuntime.decodeReport(legacyPayload(true, 1234L))?.answerRequested)
    }

    @Test
    fun anOlderPeerReadsAnAnswerRequestAsAnOrdinaryReport() {
        val payload = SensorOwnershipRuntime.encode(
            serial, owns = true, lastReadingMs = 1234L, directAgeMs = 95_000L, answerRequested = true,
        )
        assertEquals(Triple(serial, true, 1234L), legacyDecode(payload))
        // A build that knows only the status byte finds no charger state in it.
        assertNull(statusOnlyCharging(payload))
        assertNull(statusOnlyCharging(SensorOwnershipRuntime.encode(serial, true, 1234L, 95_000L)))
    }

    // Builds that send the watch's charger state put it in the same status byte.

    @Test
    fun aReportWithTheStatusByteAloneHasNoDirectReadingAge() {
        for (status in listOf(0x00, 0x01)) {
            assertEquals(
                "status=$status",
                SensorOwnershipRuntime.Report(serial, true, 1234L, null),
                SensorOwnershipRuntime.decodeReport(statusOnlyPayload(true, 1234L, status)),
            )
        }
    }

    @Test
    fun theAgeAfterAChargerStateIsRead() {
        val payload = statusOnlyPayload(true, 1234L, 0x01 or 0x02) + ByteBuffer.allocate(8).putLong(95_000L).array()
        assertEquals(
            SensorOwnershipRuntime.Report(serial, true, 1234L, 95_000L, answerRequested = true),
            SensorOwnershipRuntime.decodeReport(payload),
        )
        assertEquals(true, statusOnlyCharging(payload))
    }
}
