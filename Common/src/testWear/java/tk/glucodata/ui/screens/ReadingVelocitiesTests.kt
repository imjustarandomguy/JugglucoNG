package tk.glucodata.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import tk.glucodata.GlucosePoint
import tk.glucodata.ui.WearGlucoseStore

/**
 * The newest reading's arrow on the watch's main screen and readings list is the
 * store's trendRate, the one the complications and the phone draw; older rows keep
 * their own windowed sweep.
 */
class ReadingVelocitiesTests {
    private val minute = 60_000L

    /** Oldest first, one reading every 5 minutes, rising 1 mg/dL each. */
    private val points = (0 until 8).map { GlucosePoint(1_700_000_000_000L + it * 5 * minute, 100f + it, 0f) }

    private fun store(trendRate: Float) =
        WearGlucoseStore.Snapshot(points = points, loadedAtMs = 1L, trendRate = trendRate)

    @Test fun theNewestRowTakesTheStoresTrendRate() {
        val rows = points.takeLast(3).reversed()
        val swept = rowVelocities(points, rows, useRaw = false, isMmol = false)

        val velocities = readingVelocities(store(0.6f), rows, isMmol = false)

        assertEquals(0.6f, velocities.getValue(points.last().timestamp), 0f)
        rows.drop(1).forEach { row ->
            assertEquals(swept.getValue(row.timestamp), velocities.getValue(row.timestamp), 0f)
        }
    }

    @Test fun withoutATrendRateEveryRowKeepsItsSweep() {
        val rows = points.takeLast(3).reversed()

        assertEquals(
            rowVelocities(points, rows, useRaw = false, isMmol = false),
            readingVelocities(store(Float.NaN), rows, isMmol = false),
        )
    }

    @Test fun rowsWithoutTheNewestReadingGetNoEntryForIt() {
        val rows = points.dropLast(1).takeLast(3).reversed()

        val velocities = readingVelocities(store(0.6f), rows, isMmol = false)

        assertFalse(points.last().timestamp in velocities)
        assertEquals(rowVelocities(points, rows, useRaw = false, isMmol = false), velocities)
    }
}
