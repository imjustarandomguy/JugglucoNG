package tk.glucodata

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.HealthActivityImportPolicy.ImportedRow
import tk.glucodata.HealthActivityImportPolicy.Interval

class HealthConnectPolicyTests {
    private fun packed(time: Long, mgdL: Int, next: Int): Long =
        time or (mgdL.toLong() shl 32) or (next.toLong() shl 48)

    @Test
    fun streamValueUnpacksAsNativePacksIt() {
        val value = packed(1_700_000_000L, 123, 4_567)
        assertEquals(1_700_000_000L, HealthConnectExportPolicy.time(value))
        assertEquals(123, HealthConnectExportPolicy.mgdL(value))
        assertEquals(4_567, HealthConnectExportPolicy.nextPos(value))
        // Positions above 32767 set the sign bit.
        assertEquals(40_000, HealthConnectExportPolicy.nextPos(packed(1_700_000_000L, 90, 40_000)))
    }

    @Test
    fun nativeFoundNothingEndsTheBatchInsteadOfSending1970() {
        // streamfromSensorptr's "nothing valid from pos on": time 0, position = pollcount.
        val nothing = packed(0L, 0, 800)
        assertTrue(HealthConnectExportPolicy.searchEnded(nothing, 700))
        assertFalse(HealthConnectExportPolicy.isExportable(0L, 0))
        // pos at or past pollcount: no progress either.
        assertTrue(HealthConnectExportPolicy.searchEnded(packed(0L, 0, 800), 800))
        assertFalse(HealthConnectExportPolicy.searchEnded(packed(1_700_000_000L, 100, 701), 700))
    }

    @Test
    fun onlyRealReadingsHealthConnectAcceptsAreExported() {
        assertTrue(HealthConnectExportPolicy.isExportable(1_700_000_000L, 40))
        assertTrue(HealthConnectExportPolicy.isExportable(1_700_000_000L, 900))
        assertFalse("0 mg/dL is an empty slot", HealthConnectExportPolicy.isExportable(1_700_000_000L, 0))
        assertFalse("1970", HealthConnectExportPolicy.isExportable(0L, 120))
        assertFalse("above 50 mmol/L fails the whole batch", HealthConnectExportPolicy.isExportable(1_700_000_000L, 901))
    }

    @Test
    fun clientRecordIdNamesTheSensorAndTheReadingTime() {
        assertEquals("ng:3MH00ABCDEF:1700000000", HealthConnectExportPolicy.clientRecordId("3MH00ABCDEF", 1_700_000_000L))
        // The same reading sent again carries the same id, so Health Connect replaces it.
        assertEquals(
            HealthConnectExportPolicy.clientRecordId("X", 1_700_000_300L),
            HealthConnectExportPolicy.clientRecordId("X", 1_700_000_300L),
        )
        assertFalse(
            HealthConnectExportPolicy.clientRecordId("X", 1_700_000_300L) ==
                HealthConnectExportPolicy.clientRecordId("Y", 1_700_000_300L),
        )
    }

    @Test
    fun permissionDialogComesUpOnlyForASwitchedOnFeatureMissingIt() {
        val ask = HealthConnectPermissionPolicy::shouldRequest
        // A switch turned on asks whatever happened before.
        assertTrue(ask(true, false, true, true))
        // Otherwise once per process.
        assertTrue(ask(true, false, false, false))
        assertFalse(ask(true, false, false, true))
        // Never when granted or when the switch is off.
        assertFalse(ask(true, true, true, false))
        assertFalse(ask(false, false, false, false))
        assertFalse(ask(false, false, true, false))
    }

    @Test
    fun foregroundImportRunsAtMostEveryFifteenMinutes() {
        val now = 1_700_000_000_000L
        val due = HealthActivityImportPolicy::foregroundImportDue
        assertTrue("never run", due(now, 0L))
        assertFalse(due(now, now - 14 * 60_000L))
        assertTrue(due(now, now - 15 * 60_000L))
        assertTrue("clock moved back", due(now, now + 60_000L))
    }

    @Test
    fun overlapIsHalfOpen() {
        val session = Interval(1_000L, 2_000L)
        assertTrue(HealthActivityImportPolicy.overlaps(Interval(1_500L, 2_500L), session))
        assertTrue(HealthActivityImportPolicy.overlaps(Interval(0L, 3_000L), session))
        assertTrue(HealthActivityImportPolicy.overlaps(Interval(1_200L, 1_300L), session))
        assertFalse("ends as the session starts", HealthActivityImportPolicy.overlaps(Interval(0L, 1_000L), session))
        assertFalse("starts as the session ends", HealthActivityImportPolicy.overlaps(Interval(2_000L, 2_500L), session))
    }

    @Test
    fun stepsWithinASessionAreNotImported() {
        val sessions = listOf(Interval(10 * 60_000L, 40 * 60_000L))
        assertFalse(HealthActivityImportPolicy.importsSteps(3_000L, Interval(20 * 60_000L, 30 * 60_000L), sessions))
        assertFalse(HealthActivityImportPolicy.importsSteps(3_000L, Interval(35 * 60_000L, 50 * 60_000L), sessions))
        assertTrue(HealthActivityImportPolicy.importsSteps(3_000L, Interval(40 * 60_000L, 50 * 60_000L), sessions))
        assertFalse("too few", HealthActivityImportPolicy.importsSteps(249L, Interval(60 * 60_000L, 70 * 60_000L), sessions))
        assertTrue(HealthActivityImportPolicy.importsSteps(250L, Interval(60 * 60_000L, 70 * 60_000L), emptyList()))
    }

    @Test
    fun cleanupRemovesOnlyImportedStepRowsInsideASession() {
        val minute = 60_000L
        val sessions = listOf(Interval(10 * minute, 40 * minute))
        val rows = listOf(
            ImportedRow(1, "health_connect:steps:a", 20 * minute, 10),
            ImportedRow(2, "health_connect:steps:b", 50 * minute, 10),
            ImportedRow(3, "health_connect:exercise:s", 10 * minute, 30),
            ImportedRow(4, null, 20 * minute, 10),
            ImportedRow(5, "meter:1:1200000", 20 * minute, 10),
            ImportedRow(6, "health_connect:steps:c", 5 * minute, 5),
            ImportedRow(7, "health_connect:steps:d", 5 * minute, 6),
        )
        // c ends at 10 min by what the row kept; d's record, still in Health Connect, ends at 12.
        val known = mapOf("health_connect:steps:d" to Interval(5 * minute, 12 * minute))
        assertEquals(listOf(1L, 7L), HealthActivityImportPolicy.stepRowsToRemove(rows, sessions, known))
        assertEquals(emptyList<Long>(), HealthActivityImportPolicy.stepRowsToRemove(rows, emptyList(), known))
    }

    @Test
    fun everyPageIsRead() = runBlocking {
        val pages = mapOf<String?, Pair<List<Int>, String?>>(
            null to (listOf(1, 2) to "p2"),
            "p2" to (listOf(3) to "p3"),
            "p3" to (listOf(4, 5) to null),
        )
        val tokens = mutableListOf<String?>()
        val all = HealthActivityImportPolicy.readAllPages { token ->
            tokens += token
            pages.getValue(token)
        }
        assertEquals(listOf(1, 2, 3, 4, 5), all)
        assertEquals(listOf(null, "p2", "p3"), tokens)
        assertEquals(listOf(7), HealthActivityImportPolicy.readAllPages { listOf(7) to "" })
    }

    @Test
    fun aPageTokenThatNeverEndsStops() = runBlocking {
        var calls = 0
        val repeated = HealthActivityImportPolicy.readAllPages { calls++; listOf(calls) to "same" }
        assertEquals(listOf(1, 2), repeated)
        calls = 0
        HealthActivityImportPolicy.readAllPages { calls++; emptyList<Int>() to "t$calls" }
        assertEquals(HealthActivityImportPolicy.MAX_PAGES, calls)
    }
}
