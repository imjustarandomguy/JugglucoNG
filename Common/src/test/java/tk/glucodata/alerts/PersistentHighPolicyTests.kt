package tk.glucodata.alerts

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * PERSISTENT_HIGH measures its duration between reading times, so phone and watch,
 * reading the same G7, ring on the same reading. Field case behind it: with "Where
 * alarms ring" on Both, the watch rang about 5 s before the phone, because each device
 * fired on its own 15 s tick as soon as the wall clock passed the duration, before the
 * reading that completes it had even arrived.
 */
class PersistentHighPolicyTests {

    private val minute = 60_000L
    private val t0 = 1_700_000_000_000L

    private fun config(
        durationMinutes: Int? = 60,
        threshold: Float? = 180f,
        fallRateSuppress: Float? = 1.0f,
        enabled: Boolean = true
    ) = AlertConfig(
        type = AlertType.PERSISTENT_HIGH,
        enabled = enabled,
        threshold = threshold,
        durationMinutes = durationMinutes,
        fallRateSuppress = fallRateSuppress
    )

    /** Feeds readings through the policy, threading its timer like the runtime does. */
    private class Run(val config: AlertConfig) {
        var startedAtMs = 0L
        lateinit var last: PersistentHighDecision

        fun reading(
            atMs: Long,
            value: Float?,
            rate: Float = 0f,
            activeNow: Boolean = true,
            snoozed: Boolean = false
        ): PersistentHighAction {
            last = PersistentHighPolicy.decide(
                startedAtMs = startedAtMs,
                config = config,
                activeNow = activeNow,
                value = value,
                readingTimeMs = atMs,
                rate = rate,
                snoozed = snoozed
            )
            startedAtMs = last.startedAtMs
            return last.action
        }

        /** The 15 s tick: the same reading again, whatever the wall clock says. */
        fun tick(lastReadingAtMs: Long, value: Float?, rate: Float = 0f, snoozed: Boolean = false) =
            reading(lastReadingAtMs, value, rate, snoozed = snoozed)
    }

    @Test
    fun timerStartsAtTheFirstHighReadingAndLaterReadingsDoNotMoveIt() {
        val run = Run(config())
        assertEquals(PersistentHighAction.RESET, run.reading(t0 - 5 * minute, 170f))
        assertEquals(0L, run.startedAtMs)
        assertEquals(PersistentHighAction.WAIT, run.reading(t0, 190f))
        assertEquals(t0, run.startedAtMs)
        assertEquals(PersistentHighAction.WAIT, run.reading(t0 + 5 * minute, 200f))
        assertEquals(t0, run.startedAtMs)
    }

    @Test
    fun firesOnTheReadingThatCompletesTheDurationAtTheG7Cadence() {
        val run = Run(config(durationMinutes = 60))
        // Twelve 5-minute intervals: the 13th high reading in a row completes the hour.
        for (i in 0 until 12) {
            assertEquals("reading $i", PersistentHighAction.WAIT, run.reading(t0 + i * 5 * minute, 200f))
        }
        assertEquals(PersistentHighAction.FIRE, run.reading(t0 + 60 * minute, 200f))
        assertEquals("persistent-high-due", run.last.reason)
    }

    @Test
    fun theTickNeverFiresBeforeTheReadingThatCompletesTheDuration() {
        val run = Run(config(durationMinutes = 60))
        for (i in 0..11) run.reading(t0 + i * 5 * minute, 200f)
        // The wall clock passes t0 + 60 min some seconds before the 13th reading
        // arrives; every tick in between still sees the reading of t0 + 55 min.
        repeat(25) {
            assertEquals(PersistentHighAction.WAIT, run.tick(t0 + 55 * minute, 200f))
        }
        assertEquals(PersistentHighAction.FIRE, run.reading(t0 + 60 * minute, 200f))
    }

    @Test
    fun phoneAndWatchFireOnTheSameReadingWhateverTheirTickPhase() {
        // Same G7 readings, a second apart in their timestamps; the devices tick at
        // unrelated times, which the policy never sees.
        val phone = Run(config(durationMinutes = 30))
        val watch = Run(config(durationMinutes = 30))
        var phoneFiredAt = -1
        var watchFiredAt = -1
        for (i in 0..8) {
            val at = t0 + i * 5 * minute
            if (phone.reading(at, 210f) == PersistentHighAction.FIRE && phoneFiredAt < 0) phoneFiredAt = i
            // The watch's ticks before this reading arrives see its previous one.
            if (i > 0) repeat(3) { watch.tick(at - 5 * minute + 1_000L, 210f) }
            if (watch.reading(at + 1_000L, 210f) == PersistentHighAction.FIRE && watchFiredAt < 0) watchFiredAt = i
        }
        assertEquals(6, phoneFiredAt)
        assertEquals(phoneFiredAt, watchFiredAt)
    }

    @Test
    fun readingJitterDoesNotCostAWholeReading() {
        val run = Run(config(durationMinutes = 60))
        // Readings 4:59 apart: twelve intervals are 59:48, inside the slack.
        val interval = 5 * minute - 1_000L
        for (i in 0 until 12) {
            assertEquals(PersistentHighAction.WAIT, run.reading(t0 + i * interval, 200f))
        }
        assertEquals(PersistentHighAction.FIRE, run.reading(t0 + 12 * interval, 200f))
    }

    @Test
    fun oneMinuteSensorsStillNeedTheFullCount() {
        val run = Run(config(durationMinutes = 10))
        for (i in 0..9) {
            assertEquals("minute $i", PersistentHighAction.WAIT, run.reading(t0 + i * minute, 200f))
        }
        assertEquals(PersistentHighAction.FIRE, run.reading(t0 + 10 * minute, 200f))
    }

    @Test
    fun aDurationBetweenReadingsFiresOnTheFirstReadingPastIt() {
        val run = Run(config(durationMinutes = 7))
        assertEquals(PersistentHighAction.WAIT, run.reading(t0, 200f))
        assertEquals(PersistentHighAction.WAIT, run.reading(t0 + 5 * minute, 200f))
        // The tick at t0 + 7 min sees the reading of t0 + 5 min: no fire on a guess.
        assertEquals(PersistentHighAction.WAIT, run.tick(t0 + 5 * minute, 200f))
        assertEquals(PersistentHighAction.FIRE, run.reading(t0 + 10 * minute, 200f))
    }

    @Test
    fun aReadingAtOrBelowTheThresholdResetsTheTimer() {
        val run = Run(config(durationMinutes = 15))
        run.reading(t0, 200f)
        run.reading(t0 + 5 * minute, 200f)
        assertEquals(PersistentHighAction.RESET, run.reading(t0 + 10 * minute, 180f))
        assertEquals(0L, run.startedAtMs)
        assertEquals("persistent-high-cleared", run.last.reason)
        // A fresh episode starts from the next high reading.
        assertEquals(PersistentHighAction.WAIT, run.reading(t0 + 15 * minute, 190f))
        assertEquals(t0 + 15 * minute, run.startedAtMs)
        assertEquals(PersistentHighAction.WAIT, run.reading(t0 + 25 * minute, 190f))
        assertEquals(PersistentHighAction.FIRE, run.reading(t0 + 30 * minute, 190f))
    }

    @Test
    fun aFallHoldsTheAlarmButKeepsTheTimer() {
        val run = Run(config(durationMinutes = 15, fallRateSuppress = 1.0f))
        run.reading(t0, 250f)
        run.reading(t0 + 5 * minute, 245f)
        // Falling 2 mg/dl/min when the duration completes: held, timer untouched.
        assertEquals(PersistentHighAction.HOLD, run.reading(t0 + 15 * minute, 230f, rate = -2f))
        assertEquals(t0, run.startedAtMs)
        assertEquals("persistent-high-falling", run.last.reason)
        // The fall stalls above the threshold: it speaks at once, no fresh duration.
        assertEquals(PersistentHighAction.FIRE, run.reading(t0 + 20 * minute, 228f, rate = -0.2f))
    }

    @Test
    fun aFallBeforeTheDurationDoesNotRestartTheCount() {
        val run = Run(config(durationMinutes = 15, fallRateSuppress = 1.0f))
        run.reading(t0, 250f)
        assertEquals(PersistentHighAction.HOLD, run.reading(t0 + 5 * minute, 235f, rate = -3f))
        assertEquals(t0, run.startedAtMs)
        assertEquals(PersistentHighAction.WAIT, run.reading(t0 + 10 * minute, 233f, rate = 0f))
        assertEquals(PersistentHighAction.FIRE, run.reading(t0 + 15 * minute, 233f, rate = 0f))
    }

    @Test
    fun unsetFallRateUsesTheDefaultAndZeroTurnsTheHoldOff() {
        val byDefault = Run(config(durationMinutes = 5, fallRateSuppress = null))
        byDefault.reading(t0, 250f)
        assertEquals(PersistentHighAction.HOLD, byDefault.reading(t0 + 5 * minute, 240f, rate = -1.5f))

        val off = Run(config(durationMinutes = 5, fallRateSuppress = 0f))
        off.reading(t0, 250f)
        assertEquals(PersistentHighAction.FIRE, off.reading(t0 + 5 * minute, 240f, rate = -5f))
    }

    @Test
    fun snoozedKeepsCountingAndFiresOnceTheSnoozeIsOver() {
        val run = Run(config(durationMinutes = 10))
        run.reading(t0, 200f)
        assertEquals(PersistentHighAction.WAIT, run.reading(t0 + 10 * minute, 200f, snoozed = true))
        assertEquals("persistent-high-snoozed", run.last.reason)
        assertEquals(t0, run.startedAtMs)
        assertEquals(PersistentHighAction.FIRE, run.tick(t0 + 10 * minute, 200f, snoozed = false))
    }

    @Test
    fun aGapInReadingsFiresOnTheFirstReadingAfterIt() {
        val run = Run(config(durationMinutes = 30))
        run.reading(t0, 200f)
        run.reading(t0 + 5 * minute, 200f)
        // Readings stop for 40 minutes; the ticks keep seeing t0 + 5 min.
        assertEquals(PersistentHighAction.WAIT, run.tick(t0 + 5 * minute, 200f))
        assertEquals(PersistentHighAction.FIRE, run.reading(t0 + 45 * minute, 200f))
    }

    @Test
    fun anythingThatCannotRunResets() {
        val disabled = Run(config(enabled = false))
        assertEquals(PersistentHighAction.RESET, disabled.reading(t0, 300f))
        assertEquals("persistent-high-disabled", disabled.last.reason)

        assertEquals(PersistentHighAction.RESET, Run(config(threshold = null)).reading(t0, 300f))
        assertEquals(PersistentHighAction.RESET, Run(config(durationMinutes = null)).reading(t0, 300f))
        assertEquals(PersistentHighAction.RESET, Run(config(durationMinutes = 0)).reading(t0, 300f))

        val running = Run(config(durationMinutes = 30))
        running.reading(t0, 300f)
        assertEquals(PersistentHighAction.RESET, running.reading(t0 + 5 * minute, null))
        assertEquals(0L, running.startedAtMs)
        running.reading(t0 + 10 * minute, 300f)
        assertEquals(PersistentHighAction.RESET, running.reading(t0 + 15 * minute, Float.NaN))
    }

    @Test
    fun outsideTheTimeWindowResets() {
        val run = Run(config(durationMinutes = 10))
        run.reading(t0, 200f)
        assertEquals(PersistentHighAction.RESET, run.reading(t0 + 5 * minute, 200f, activeNow = false))
        assertEquals("persistent-high-time-inactive", run.last.reason)
        assertEquals(0L, run.startedAtMs)
    }

    @Test
    fun anOlderReadingThanTheStartNeverFires() {
        val run = Run(config(durationMinutes = 10))
        run.reading(t0, 200f)
        assertEquals(PersistentHighAction.WAIT, run.reading(t0 - 20 * minute, 200f))
        assertEquals(t0, run.startedAtMs)
    }
}
