package tk.glucodata.ui

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class LegacySensorStatusPolicyTests {
    private val start = 1_790_352_742_000L

    @Test fun waitingForBluetoothIsNotPaused() {
        assertTrue(isSensorLocallyEnabled(streaming = false, paused = false))
        assertFalse(isSensorLocallyEnabled(streaming = true, paused = true))
        assertFalse(isSensorLocallyEnabled(streaming = false, paused = null))
        assertTrue(isSensorLocallyEnabled(streaming = true, paused = null))
    }

    @Test fun libre3NfcStartShowsWarmupBeforeBluetoothStatusArrives() {
        assertEquals(60, libre3WarmupMinutes(3, start, start, 60, ""))
        assertEquals(51, libre3WarmupMinutes(3, start, start + 9 * 60_000L, 60, ""))
        assertEquals(1, libre3WarmupMinutes(3, start, start + 3_599_999, 60, "Warming Up"))
        assertNull(libre3WarmupMinutes(3, start, start + 3_600_000, 60, ""))
    }

    @Test fun respectsNegotiatedDurationAndDoesNotHideErrors() {
        assertEquals(21, libre3WarmupMinutes(3, start, start + 9 * 60_000L, 30, ""))
        for (status in listOf("Sensor Error", "Sensor Ended", "Receiving old values: 12")) {
            assertNull(libre3WarmupMinutes(3, start, start + 60_000, 60, status))
        }
        assertNull(libre3WarmupMinutes(3, start, start - 1, 60, ""))
        assertNull(libre3WarmupMinutes(3, 0, start, 60, ""))
        assertNull(libre3WarmupMinutes(3, start, start, 0, ""))
        assertNull(libre3WarmupMinutes(2, start, start, 60, ""))
        assertNull(libre3WarmupMinutes(0x40, start, start, 60, ""))
    }

    private val minute = 60_000L

    @Test fun dexcomWaitsForConnectionOnlyUntilTheSensorIsReached() {
        assertEquals(DexcomWarmupStatus.WaitingForConnection, dexcomWarmupStatus(start, start, false, 0L))
        assertEquals(DexcomWarmupStatus.WaitingForConnection, dexcomWarmupStatus(start, start + 20 * minute, false, 0L))
        assertEquals(DexcomWarmupStatus.ReadyIn(28), dexcomWarmupStatus(start, start + 2 * minute, true, 0L))
    }

    @Test fun dexcomCountdownOutlastsTheWarmupFrameStatus() {
        // The warmup frame arrived 2 minutes in; its native status was gone at 17.
        assertEquals(DexcomWarmupStatus.ReadyIn(13), dexcomWarmupStatus(start, start + 17 * minute, true, 0L))
        assertEquals(DexcomWarmupStatus.ReadyIn(1), dexcomWarmupStatus(start, start + 30 * minute - 1, true, 0L))
    }

    @Test fun dexcomWaitsForTheFirstReadingInsteadOfReportingALoss() {
        assertEquals(
            DexcomWarmupStatus.WaitingForFirstReading,
            dexcomWarmupStatus(start, start + 30 * minute, true, 0L),
        )
        assertEquals(
            DexcomWarmupStatus.WaitingForFirstReading,
            dexcomWarmupStatus(start, start + 45 * minute - 1, true, 0L),
        )
        // Past the grace period the silence is a real loss again.
        assertNull(dexcomWarmupStatus(start, start + 45 * minute, true, 0L))
        // A sensor never reached is left to the Bluetooth status after its warmup.
        assertNull(dexcomWarmupStatus(start, start + 31 * minute, false, 0L))
    }

    @Test fun dexcomReadingEndsTheWarmupStatus() {
        val reading = start + 28 * minute
        assertNull(dexcomWarmupStatus(start, start + 28 * minute + 1, true, reading))
        assertNull(dexcomWarmupStatus(start, start + 31 * minute, true, reading))
        // A start time not yet corrected by the sensor cannot hide its readings.
        assertNull(dexcomWarmupStatus(start, start + 5 * minute, false, start + 4 * minute))
    }

    @Test fun dexcomNeedsAKnownStart() {
        assertNull(dexcomWarmupStatus(0L, start, true, 0L))
        assertNull(dexcomWarmupStatus(start, start - 1, true, 0L))
    }

    @Test fun everyLocaleWithTheWarmupStatusHasTheFirstReadingStatus() {
        val res = listOf("src/main/res", "Common/src/main/res").map(::File).first { it.isDirectory }
        val locales = res.listFiles { f -> f.isDirectory && f.name.startsWith("values") }!!
            .map { File(it, "strings.xml") }
            .filter { it.isFile && it.readText().contains("name=\"status_ready_in_minutes\"") }
        assertTrue("expected the default locale and the translations", locales.size >= 15)
        for (file in locales) {
            assertTrue(
                "${file.parentFile.name}: status_waiting_for_first_reading is missing",
                file.readText().contains("name=\"status_waiting_for_first_reading\""),
            )
        }
    }
}
