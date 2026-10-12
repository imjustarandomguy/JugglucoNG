package tk.glucodata.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import tk.glucodata.DisplayedReading
import tk.glucodata.GlucosePoint
import tk.glucodata.ui.WearGlucoseStore

/**
 * The current reading's arrow on the watch's main screen and readings list is the hub's
 * rate, the one the complications, the face and the phone draw; older rows keep their own
 * windowed sweep.
 */
class ReadingVelocitiesTests {
    private val minute = 60_000L

    /** Oldest first, one reading every 5 minutes, rising 1 mg/dL each. */
    private val points = (0 until 8).map { GlucosePoint(1_700_000_000_000L + it * 5 * minute, 100f + it, 0f) }

    private val store = WearGlucoseStore.Snapshot(points = points, loadedAtMs = 1L)

    /** The hub's reading, taken [offsetMs] after the newest stored row (live time vs stored time). */
    private fun current(rate: Float, offsetMs: Long = 0L) = DisplayedReading.NONE.copy(
        timeMillis = points.last().timestamp + offsetMs,
        rate = rate,
        freshness = DisplayedReading.Freshness.FRESH,
    )

    @Test fun theCurrentReadingsRowTakesTheHubsRate() {
        val rows = points.takeLast(3).reversed()
        val swept = rowVelocities(points, rows, useRaw = false, isMmol = false)

        // A live reading keeps its milliseconds, its stored row whole seconds.
        val velocities = readingVelocities(store, rows, isMmol = false, current(0.6f, offsetMs = 640L))

        assertEquals(0.6f, velocities.getValue(points.last().timestamp), 0f)
        rows.drop(1).forEach { row ->
            assertEquals(swept.getValue(row.timestamp), velocities.getValue(row.timestamp), 0f)
        }
    }

    @Test fun withoutARateEveryRowKeepsItsSweep() {
        val rows = points.takeLast(3).reversed()

        assertEquals(
            rowVelocities(points, rows, useRaw = false, isMmol = false),
            readingVelocities(store, rows, isMmol = false, current(Float.NaN)),
        )
    }

    @Test fun rowsWithoutTheCurrentReadingGetNoEntryForIt() {
        val rows = points.dropLast(1).takeLast(3).reversed()

        val velocities = readingVelocities(store, rows, isMmol = false, current(0.6f))

        assertFalse(points.last().timestamp in velocities)
        assertEquals(rowVelocities(points, rows, useRaw = false, isMmol = false), velocities)
    }
}
