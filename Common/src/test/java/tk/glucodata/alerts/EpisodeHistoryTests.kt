package tk.glucodata.alerts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The walk back over the stored readings that gives the persistent alarms their
 * start, the same on phone and watch whenever each began to look.
 */
class EpisodeHistoryTests {

    private val minute = 60_000L
    private val t0 = 1_700_000_000_000L

    /** A low-side rule: below 3.9 runs and starts, up to 4.1 runs, 4.1 and up ends. */
    private fun kind(value: Float) = when {
        value >= 4.1f -> EpisodeHistory.Kind.END
        value < 3.9f -> EpisodeHistory.Kind.START
        else -> EpisodeHistory.Kind.BAND
    }

    /** One reading every [stepMinutes] minutes, the last one (the current one) at [t0]. */
    private fun readings(vararg values: Float, stepMinutes: Int = 5): List<StoredReading> =
        values.mapIndexed { i, value ->
            StoredReading(t0 - (values.size - 1 - i) * stepMinutes * minute, value)
        }

    private fun startOf(readings: List<StoredReading>) = EpisodeHistory.startOf(readings, ::kind)

    @Test
    fun theStartIsTheOldestStartReadingAfterTheLastEnd() {
        val start = startOf(readings(3.5f, 4.4f, 3.8f, 3.7f, 3.6f))
        assertEquals(EpisodeStart(t0 - 10 * minute, 3), start)
    }

    @Test
    fun bandReadingsNeitherStartNorEndTheEpisode() {
        // The band reading after the end is walked but does not start; the one
        // inside the stretch does not end it.
        val start = startOf(readings(4.4f, 4.0f, 3.8f, 4.0f, 3.7f))
        assertEquals(EpisodeStart(t0 - 10 * minute, 4), start)
    }

    @Test
    fun noStartReadingMeansNoEpisode() {
        assertNull(startOf(emptyList()))
        assertNull(startOf(readings(3.8f, 4.0f, 4.0f).drop(1)))
        assertNull(startOf(readings(3.7f, 4.2f)))
    }

    @Test
    fun aHoleLongerThanTheGapLimitEndsTheStretch() {
        val current = StoredReading(t0, 3.6f)
        val beforeHole = StoredReading(t0 - EpisodeHistory.MAX_GAP_MS - minute, 3.5f)
        assertEquals(EpisodeStart(t0, 1), startOf(listOf(beforeHole, current)))
        // Three missed G7 readings are still one stretch.
        val acrossHole = StoredReading(t0 - EpisodeHistory.MAX_GAP_MS, 3.5f)
        assertEquals(EpisodeStart(acrossHole.timeMs, 2), startOf(listOf(acrossHole, current)))
    }

    @Test
    fun theWalkGoesNoFurtherThanTheLookback() {
        val allLow = readings(*FloatArray(60) { 3.5f })
        val start = requireNotNull(startOf(allLow))
        assertEquals(t0 - EpisodeHistory.MAX_LOOKBACK_MS, start.startedAtMs)
        assertEquals(37, start.readingsWalked)
    }

    @Test
    fun aValueThatIsNotFiniteIsAMissingReadingNotARecovery() {
        val start = startOf(readings(3.7f, Float.NaN, 3.6f))
        assertEquals(EpisodeStart(t0 - 10 * minute, 2), start)
    }
}
