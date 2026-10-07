package tk.glucodata.ui.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.SensorSourceResolver
import tk.glucodata.ui.GlucosePoint
import tk.glucodata.ui.overlay.FloatingNextReading.FIVE_MINUTES_MS
import tk.glucodata.ui.overlay.FloatingNextReading.LATE_GRACE_MS
import tk.glucodata.ui.overlay.FloatingNextReading.ONE_MINUTE_MS
import tk.glucodata.ui.overlay.FloatingNextReading.STEP_MS

class FloatingNextReadingTests {
    private val second = 1_000L
    private val minute = ONE_MINUTE_MS
    private val t0 = 1_790_000_000_000L

    private val dexcom = SensorSourceResolver.SENSOR_KIND_DEXCOM
    private val libre2 = SensorSourceResolver.SENSOR_KIND_LIBRE2
    private val unknown = SensorSourceResolver.SENSOR_KIND_UNKNOWN

    private fun times(vararg minutes: Double): List<Long> = minutes.map { t0 + (it * minute).toLong() }

    // Interval

    @Test
    fun aDexcomOrAnAccuChekReadsEveryFiveMinutesWhateverTheReadings() {
        assertEquals(FIVE_MINUTES_MS, FloatingNextReading.intervalMillis(dexcom, emptyList()))
        assertEquals(FIVE_MINUTES_MS, FloatingNextReading.intervalMillis(dexcom, times(0.0, 1.0, 2.0)))
        assertEquals(
            FIVE_MINUTES_MS,
            FloatingNextReading.intervalMillis(SensorSourceResolver.SENSOR_KIND_ACCUCHEK, listOf(t0)),
        )
    }

    @Test
    fun anyOtherSensorGoesByTheSpacingOfItsReadings() {
        // A Libre streams every minute.
        assertEquals(minute, FloatingNextReading.intervalMillis(libre2, times(0.0, 1.0, 2.0, 3.0)))
        // A follower or a Kotlin driver's sensor also has a Libre 2 record.
        assertEquals(FIVE_MINUTES_MS, FloatingNextReading.intervalMillis(libre2, times(0.0, 5.0, 10.0)))
        assertEquals(3 * minute, FloatingNextReading.intervalMillis(libre2, times(0.0, 3.0, 6.0)))
        assertEquals(3 * minute, FloatingNextReading.intervalMillis(unknown, times(0.0, 3.0, 6.0)))
    }

    @Test
    fun aMissedReadingDoesNotWidenTheSpacing() {
        assertEquals(FIVE_MINUTES_MS, FloatingNextReading.intervalMillis(unknown, times(0.0, 5.0, 10.0, 20.0, 25.0)))
        assertEquals(minute, FloatingNextReading.intervalMillis(libre2, times(0.0, 1.0, 4.0, 5.0)))
        // Only two readings, a gap between them: as wide as it gets.
        assertEquals(FIVE_MINUTES_MS, FloatingNextReading.intervalMillis(unknown, times(0.0, 15.0)))
    }

    @Test
    fun oneReadingFromTwoSourcesIsNotASpacing() {
        val readings = times(0.0, 5.0, 10.0) + (t0 + 10 * minute + 10 * second)
        assertEquals(FIVE_MINUTES_MS, FloatingNextReading.intervalMillis(unknown, readings))
    }

    @Test
    fun theSpacingIsRoundedToWholeMinutesWithinOneToFive() {
        assertEquals(FIVE_MINUTES_MS, FloatingNextReading.intervalMillis(unknown, times(0.0, 4.7, 9.4)))
        assertEquals(minute, FloatingNextReading.intervalMillis(unknown, times(0.0, 0.7, 1.4)))
        assertEquals(FIVE_MINUTES_MS, FloatingNextReading.intervalMillis(unknown, times(0.0, 15.0, 30.0)))
    }

    @Test
    fun onlyTheNewestReadingsCount() {
        // One-minute readings long ago, then a five-minute sensor.
        val readings = times(0.0, 1.0, 2.0) + times(60.0, 65.0, 70.0, 75.0, 80.0, 85.0)
        assertEquals(FIVE_MINUTES_MS, FloatingNextReading.intervalMillis(unknown, readings))
    }

    @Test
    fun theReadingsMayComeInAnyOrder() {
        assertEquals(FIVE_MINUTES_MS, FloatingNextReading.intervalMillis(unknown, times(10.0, 0.0, 5.0)))
        assertEquals(FIVE_MINUTES_MS, FloatingNextReading.intervalMillis(unknown, listOf(0L) + times(0.0, 5.0)))
    }

    @Test
    fun withoutTwoReadingsAStreamingKindHasItsMinuteAndAnUnknownSensorNone() {
        assertEquals(minute, FloatingNextReading.intervalMillis(libre2, listOf(t0)))
        assertEquals(minute, FloatingNextReading.intervalMillis(SensorSourceResolver.SENSOR_KIND_LIBRE3, emptyList()))
        assertEquals(minute, FloatingNextReading.intervalMillis(SensorSourceResolver.SENSOR_KIND_SIBIONICS, emptyList()))
        assertEquals(0L, FloatingNextReading.intervalMillis(unknown, listOf(t0)))
        assertEquals(0L, FloatingNextReading.intervalMillis(unknown, emptyList()))
    }

    // Progress and lateness

    @Test
    fun noBarWithoutAReadingOrAnInterval() {
        assertNull(FloatingNextReading.state(0L, FIVE_MINUTES_MS, t0, stale = false))
        assertNull(FloatingNextReading.state(t0, 0L, t0, stale = false))
        assertNull(FloatingNextReading.state(0L, FIVE_MINUTES_MS, t0, stale = true))
    }

    @Test
    fun theBarFillsFromTheReadingToTheNextOne() {
        val atReading = FloatingNextReading.state(t0, FIVE_MINUTES_MS, t0, stale = false)!!
        assertEquals(0f, atReading.progress, 0f)
        assertFalse(atReading.late)

        val halfway = FloatingNextReading.state(t0, FIVE_MINUTES_MS, t0 + 150 * second, stale = false)!!
        assertEquals(0.5f, halfway.progress, 0.0001f)
        assertFalse(halfway.late)

        val due = FloatingNextReading.state(t0, FIVE_MINUTES_MS, t0 + FIVE_MINUTES_MS, stale = false)!!
        assertEquals(1f, due.progress, 0f)
        assertFalse(due.late)
    }

    @Test
    fun theNextReadingIsLateOnlyAfterTheGrace() {
        val lateAt = t0 + FIVE_MINUTES_MS + LATE_GRACE_MS
        val justBefore = FloatingNextReading.state(t0, FIVE_MINUTES_MS, lateAt - 1, stale = false)!!
        assertEquals(1f, justBefore.progress, 0f)
        assertFalse(justBefore.late)

        val late = FloatingNextReading.state(t0, FIVE_MINUTES_MS, lateAt, stale = false)!!
        assertEquals(1f, late.progress, 0f)
        assertTrue(late.late)

        val muchLater = FloatingNextReading.state(t0, minute, t0 + 2 * 60 * minute, stale = false)!!
        assertTrue(muchLater.late)
    }

    @Test
    fun aStalePillIsLateWhateverTheTime() {
        val stale = FloatingNextReading.state(t0, FIVE_MINUTES_MS, t0 + 10 * second, stale = true)!!
        assertEquals(1f, stale.progress, 0f)
        assertTrue(stale.late)
    }

    @Test
    fun aReadingStampedAheadOfTheClockStartsEmpty() {
        val ahead = FloatingNextReading.state(t0, FIVE_MINUTES_MS, t0 - 4 * second, stale = false)!!
        assertEquals(0f, ahead.progress, 0f)
        assertFalse(ahead.late)
    }

    // Steps

    @Test
    fun theBarMovesInStepsCountedFromTheReading() {
        assertEquals(STEP_MS, FloatingNextReading.nextStepDelay(t0, FIVE_MINUTES_MS, t0))
        assertEquals(3 * second, FloatingNextReading.nextStepDelay(t0, FIVE_MINUTES_MS, t0 + 2 * second))
        assertEquals(STEP_MS, FloatingNextReading.nextStepDelay(t0, FIVE_MINUTES_MS, t0 + STEP_MS))
        // Stamped ahead of the clock: the first step is the reading's own time.
        assertEquals(3 * second, FloatingNextReading.nextStepDelay(t0, FIVE_MINUTES_MS, t0 - 3 * second))
    }

    @Test
    fun theLastStepIsWhenItTurnsLateAndThenNothingRuns() {
        val lateAt = t0 + minute + LATE_GRACE_MS
        assertEquals(2 * second, FloatingNextReading.nextStepDelay(t0, minute, lateAt - 2 * second))
        assertNull(FloatingNextReading.nextStepDelay(t0, minute, lateAt))
        assertNull(FloatingNextReading.nextStepDelay(t0, minute, lateAt + 60 * minute))
        assertNull(FloatingNextReading.nextStepDelay(0L, minute, t0))
        assertNull(FloatingNextReading.nextStepDelay(t0, 0L, t0))
    }

    @Test
    fun aWholeIntervalTakesAtMostOneRedrawEveryStep() {
        var now = t0
        var redraws = 0
        while (true) {
            val wait = FloatingNextReading.nextStepDelay(t0, FIVE_MINUTES_MS, now) ?: break
            assertTrue(wait in 1..STEP_MS)
            now += wait
            redraws++
        }
        assertEquals((FIVE_MINUTES_MS + LATE_GRACE_MS) / STEP_MS, redraws.toLong())
        assertTrue(FloatingNextReading.state(t0, FIVE_MINUTES_MS, now, stale = false)!!.late)
    }

    // Drawing

    @Test
    fun theBarStartsWhereTheRoundedCornerLetsItShow() {
        assertEquals(0f, FloatingNextReading.visibleInset(radius = 0f, thickness = 2.5f), 0f)
        assertEquals(0f, FloatingNextReading.visibleInset(radius = 2f, thickness = 2.5f), 0f)
        // A 32 px tall pill with round ends and a 2.5 px bar: about 7.4 px in.
        assertEquals(7.41f, FloatingNextReading.visibleInset(radius = 16f, thickness = 2.5f), 0.01f)
    }

    // The pill's reading

    @Test
    fun thePillsReadingTimeIsItsStoredReadingsWithoutALiveValue() {
        val point = GlucosePoint(value = 6.1f, time = "", timestamp = t0)
        assertEquals(t0, FloatingPillReading(point = point, snapshot = null, sensorId = null).readingTime)
        assertEquals(0L, FloatingPillReading.NONE.readingTime)
        assertEquals(0L, FloatingPillReading.NONE.intervalMillis)
    }
}
