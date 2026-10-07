package tk.glucodata.alerts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PERSISTENT_LOW: a low that lasts the duration rings, a compression dip that
 * recovers does not. Field case behind it: lying on the sensor at night drops
 * the reading for 10-20 minutes and wakes the owner with a plain LOW.
 */
class PersistentLowPolicyTests {

    private val minute = 60_000L
    private val t0 = 1_700_000_000_000L

    private fun mmolConfig(
        durationMinutes: Int = 15,
        rearmMargin: Float? = 0.2f,
        riseRateSuppress: Float? = null,
        enabled: Boolean = true,
        threshold: Float? = 3.9f
    ) = AlertConfig(
        type = AlertType.PERSISTENT_LOW,
        enabled = enabled,
        threshold = threshold,
        durationMinutes = durationMinutes,
        rearmMargin = rearmMargin,
        riseRateSuppress = riseRateSuppress
    )

    /** Feeds readings through the policy, threading its state like the runtime does. */
    private class Run(val config: AlertConfig, val isMmol: Boolean = true) {
        var state = PersistentLowState()
        lateinit var last: PersistentLowDecision

        fun reading(
            atMs: Long,
            value: Float?,
            rate: Float = 0f,
            activeNow: Boolean = true,
            veryLowActive: Boolean = false,
            snoozed: Boolean = false
        ): PersistentLowAction {
            last = PersistentLowPolicy.decide(
                state = state,
                config = config,
                isMmol = isMmol,
                activeNow = activeNow,
                value = value,
                readingTimeMs = atMs,
                rate = rate,
                veryLowActive = veryLowActive,
                snoozed = snoozed
            )
            state = last.state
            return last.action
        }
    }

    @Test
    fun timerStartsAtTheFirstLowReading() {
        val run = Run(mmolConfig())
        assertEquals(PersistentLowAction.RESET, run.reading(t0 - 5 * minute, 4.5f))
        assertEquals(0L, run.state.startedAtMs)
        assertEquals(PersistentLowAction.WAIT, run.reading(t0, 3.8f))
        assertEquals(t0, run.state.startedAtMs)
        // Later lows do not move the start.
        assertEquals(PersistentLowAction.WAIT, run.reading(t0 + 5 * minute, 3.6f))
        assertEquals(t0, run.state.startedAtMs)
    }

    @Test
    fun aReadingInTheBandDoesNotStartTheTimer() {
        val run = Run(mmolConfig())
        // At the threshold is not below it.
        assertEquals(PersistentLowAction.WAIT, run.reading(t0, 3.9f))
        assertEquals(0L, run.state.startedAtMs)
        assertEquals(PersistentLowAction.WAIT, run.reading(t0 + 5 * minute, 4.0f))
        assertEquals(0L, run.state.startedAtMs)
    }

    @Test
    fun timerResetsOnlyAtThresholdPlusMargin() {
        val run = Run(mmolConfig())
        run.reading(t0, 3.8f)
        assertEquals(PersistentLowAction.WAIT, run.reading(t0 + 5 * minute, 4.0f))
        assertEquals(t0, run.state.startedAtMs)
        // Exactly threshold + margin counts as recovered, float rounding or not.
        assertEquals(PersistentLowAction.RESET, run.reading(t0 + 10 * minute, 4.1f))
        assertEquals(PersistentLowState(), run.state)
    }

    @Test
    fun mgdlMarginWorksTheSameWay() {
        val run = Run(
            AlertConfig(
                type = AlertType.PERSISTENT_LOW, enabled = true, threshold = 70f,
                durationMinutes = 15, rearmMargin = 3f
            ),
            isMmol = false
        )
        run.reading(t0, 68f)
        assertEquals(PersistentLowAction.WAIT, run.reading(t0 + 5 * minute, 72f))
        assertEquals(t0, run.state.startedAtMs)
        assertEquals(PersistentLowAction.RESET, run.reading(t0 + 10 * minute, 73f))
    }

    @Test
    fun zeroMarginResetsAtTheThresholdItself() {
        val run = Run(mmolConfig(rearmMargin = 0f))
        run.reading(t0, 3.8f)
        assertEquals(PersistentLowAction.RESET, run.reading(t0 + 5 * minute, 3.9f))
    }

    @Test
    fun unsetMarginFallsBackToTheTypeDefault() {
        val run = Run(mmolConfig(rearmMargin = null))
        run.reading(t0, 3.8f)
        assertEquals(PersistentLowAction.WAIT, run.reading(t0 + 5 * minute, 4.0f))
        assertEquals(PersistentLowAction.RESET, run.reading(t0 + 10 * minute, 4.1f))
    }

    @Test
    fun aLowHoveringAtTheLineKeepsItsCount() {
        // 3.8 / 4.0 / 3.8 / 4.0: the in-band readings neither reset nor fire.
        val run = Run(mmolConfig(durationMinutes = 15))
        assertEquals(PersistentLowAction.WAIT, run.reading(t0, 3.8f))
        assertEquals(PersistentLowAction.WAIT, run.reading(t0 + 5 * minute, 4.0f))
        assertEquals(PersistentLowAction.WAIT, run.reading(t0 + 10 * minute, 3.8f))
        // Duration reached, but this reading is in the band: no fire yet.
        assertEquals(PersistentLowAction.WAIT, run.reading(t0 + 15 * minute, 4.0f))
        assertEquals("persistent-low-in-band", run.last.reason)
        assertEquals(t0, run.state.startedAtMs)
        // Back below: due at once, no fresh duration.
        assertEquals(PersistentLowAction.FIRE, run.reading(t0 + 20 * minute, 3.8f))
    }

    @Test
    fun firesWhenTheDurationIsReachedAndStillLow() {
        val run = Run(mmolConfig(durationMinutes = 15))
        assertEquals(PersistentLowAction.WAIT, run.reading(t0, 3.7f))
        assertEquals(PersistentLowAction.WAIT, run.reading(t0 + 5 * minute, 3.7f))
        assertEquals(PersistentLowAction.WAIT, run.reading(t0 + 10 * minute, 3.6f))
        assertEquals(PersistentLowAction.FIRE, run.reading(t0 + 15 * minute, 3.6f))
        // Stays due while low; the alert state decides about re-delivery.
        assertEquals(PersistentLowAction.FIRE, run.reading(t0 + 20 * minute, 3.6f))
    }

    @Test
    fun threeG7ReadingsMakeTenMinutesAndFourMakeFifteen() {
        val ten = Run(mmolConfig(durationMinutes = 10))
        assertEquals(PersistentLowAction.WAIT, ten.reading(t0, 3.7f))
        assertEquals(PersistentLowAction.WAIT, ten.reading(t0 + 5 * minute, 3.7f))
        assertEquals(PersistentLowAction.FIRE, ten.reading(t0 + 10 * minute, 3.7f))

        val fifteen = Run(mmolConfig(durationMinutes = 15))
        val actions = (0..3).map { fifteen.reading(t0 + it * 5 * minute, 3.7f) }
        assertEquals(
            listOf(PersistentLowAction.WAIT, PersistentLowAction.WAIT, PersistentLowAction.WAIT, PersistentLowAction.FIRE),
            actions
        )
    }

    @Test
    fun readingJitterDoesNotCostAWholeReading() {
        // 4:59 apart: the third reading is still the tenth minute.
        val run = Run(mmolConfig(durationMinutes = 10))
        val step = 5 * minute - 1_000L
        run.reading(t0, 3.7f)
        run.reading(t0 + step, 3.7f)
        assertEquals(PersistentLowAction.FIRE, run.reading(t0 + 2 * step, 3.7f))
    }

    @Test
    fun twoReadingsNeverFireTenMinutesNoMatterHowOftenTheyAreChecked() {
        // The 15 s monitor re-evaluates the same reading; time comes from readings only.
        val run = Run(mmolConfig(durationMinutes = 10))
        run.reading(t0, 3.7f)
        repeat(40) {
            assertEquals(PersistentLowAction.WAIT, run.reading(t0 + 5 * minute, 3.7f))
        }
    }

    @Test
    fun oneMinuteSensorsStillNeedTheFullDuration() {
        val run = Run(mmolConfig(durationMinutes = 10))
        (0..9).forEach { assertEquals(PersistentLowAction.WAIT, run.reading(t0 + it * minute, 3.7f)) }
        assertEquals(PersistentLowAction.FIRE, run.reading(t0 + 10 * minute, 3.7f))
    }

    @Test
    fun compressionDipThatRecoversNeverRings() {
        val run = Run(mmolConfig(durationMinutes = 15))
        listOf(3.5f, 3.3f, 3.6f).forEachIndexed { i, v ->
            assertEquals(PersistentLowAction.WAIT, run.reading(t0 + i * 5 * minute, v))
        }
        assertEquals(PersistentLowAction.RESET, run.reading(t0 + 15 * minute, 5.2f))
    }

    @Test
    fun risingHoldBlocksWithoutResettingThenFiresOnceTheRiseStops() {
        val run = Run(mmolConfig(durationMinutes = 15, riseRateSuppress = 1.0f))
        run.reading(t0, 3.4f)
        run.reading(t0 + 5 * minute, 3.3f)
        run.reading(t0 + 10 * minute, 3.4f, rate = 0.5f)
        // Due, but climbing at 2 mg/dl/min: held, timer kept.
        assertEquals(PersistentLowAction.HOLD, run.reading(t0 + 15 * minute, 3.6f, rate = 2f))
        assertEquals("persistent-low-rising", run.last.reason)
        assertEquals(t0, run.state.startedAtMs)
        // The rise stalls below the line: rings at once, no fresh 15 minutes.
        assertEquals(PersistentLowAction.FIRE, run.reading(t0 + 20 * minute, 3.7f, rate = 0.3f))
    }

    @Test
    fun risingHoldIsOffByDefault() {
        assertNull(AlertDefaults.defaultConfig(AlertType.PERSISTENT_LOW, true).riseRateSuppress)
        assertFalse(PersistentLowPolicy.risingHolds(5f, null))
        assertFalse(PersistentLowPolicy.risingHolds(5f, 0f))
        val run = Run(mmolConfig(durationMinutes = 10, riseRateSuppress = null))
        run.reading(t0, 3.4f)
        run.reading(t0 + 5 * minute, 3.5f)
        assertEquals(PersistentLowAction.FIRE, run.reading(t0 + 10 * minute, 3.7f, rate = 3f))
    }

    @Test
    fun risingHoldUsesTheConfiguredMagnitudeAndIgnoresFalls() {
        assertTrue(PersistentLowPolicy.risingHolds(1.0f, 1.0f))
        assertFalse(PersistentLowPolicy.risingHolds(0.9f, 1.0f))
        assertFalse(PersistentLowPolicy.risingHolds(-3f, 1.0f))
        assertFalse(PersistentLowPolicy.risingHolds(Float.NaN, 1.0f))
    }

    @Test
    fun inactiveTimeWindowResets() {
        val run = Run(mmolConfig(durationMinutes = 15))
        run.reading(t0, 3.7f)
        run.reading(t0 + 5 * minute, 3.7f)
        assertEquals(PersistentLowAction.RESET, run.reading(t0 + 10 * minute, 3.7f, activeNow = false))
        assertEquals("persistent-low-time-inactive", run.last.reason)
        assertEquals(PersistentLowState(), run.state)
        // Window opens again: the count starts over at the next low reading.
        assertEquals(PersistentLowAction.WAIT, run.reading(t0 + 15 * minute, 3.7f))
        assertEquals(t0 + 15 * minute, run.state.startedAtMs)
        assertEquals(PersistentLowAction.WAIT, run.reading(t0 + 25 * minute, 3.7f))
        assertEquals(PersistentLowAction.FIRE, run.reading(t0 + 30 * minute, 3.7f))
    }

    @Test
    fun disabledOrIncompleteConfigResets() {
        listOf(
            mmolConfig(enabled = false),
            mmolConfig(threshold = null),
            mmolConfig(durationMinutes = 0)
        ).forEach { config ->
            val run = Run(config)
            run.state = PersistentLowState(startedAtMs = t0)
            assertEquals(PersistentLowAction.RESET, run.reading(t0 + 20 * minute, 3.0f))
            assertEquals("persistent-low-disabled", run.last.reason)
            assertEquals(PersistentLowState(), run.state)
        }
    }

    @Test
    fun aMissingValueIsNotARecovery() {
        val run = Run(mmolConfig(durationMinutes = 15))
        run.reading(t0, 3.7f)
        assertEquals(PersistentLowAction.WAIT, run.reading(t0 + 5 * minute, null))
        assertEquals(t0, run.state.startedAtMs)
        run.reading(t0 + 10 * minute, 3.7f)
        assertEquals(PersistentLowAction.FIRE, run.reading(t0 + 15 * minute, 3.7f))
    }

    @Test
    fun snoozeKeepsCountingAndDeliversNothing() {
        val run = Run(mmolConfig(durationMinutes = 10))
        run.reading(t0, 3.7f, snoozed = true)
        run.reading(t0 + 5 * minute, 3.7f, snoozed = true)
        assertEquals(PersistentLowAction.WAIT, run.reading(t0 + 10 * minute, 3.7f, snoozed = true))
        assertEquals("persistent-low-snoozed", run.last.reason)
        assertEquals(PersistentLowAction.FIRE, run.reading(t0 + 15 * minute, 3.7f))
    }

    @Test
    fun veryLowActiveHoldsThePersistentLow() {
        val run = Run(mmolConfig(durationMinutes = 15))
        run.reading(t0, 3.4f)
        run.reading(t0 + 5 * minute, 2.9f, veryLowActive = true)
        run.reading(t0 + 10 * minute, 2.8f, veryLowActive = true)
        assertEquals(PersistentLowAction.HOLD, run.reading(t0 + 15 * minute, 2.8f, veryLowActive = true))
        assertEquals("persistent-low-very-low", run.last.reason)
        assertEquals(t0, run.state.startedAtMs)
    }

    @Test
    fun aVeryLowInTheEpisodeKeepsItQuietUntilTheLowIsOver() {
        val run = Run(mmolConfig(durationMinutes = 15))
        run.reading(t0, 3.4f)
        run.reading(t0 + 5 * minute, 2.9f, veryLowActive = true)
        // VERY_LOW resolved (treated, dismissed, snoozed), still below 3.9.
        assertEquals(PersistentLowAction.HOLD, run.reading(t0 + 10 * minute, 3.5f))
        assertEquals(PersistentLowAction.HOLD, run.reading(t0 + 20 * minute, 3.6f))
        assertTrue(run.state.veryLowSeen)
        // A real recovery ends the episode and clears the latch.
        assertEquals(PersistentLowAction.RESET, run.reading(t0 + 25 * minute, 4.4f))
        assertFalse(run.state.veryLowSeen)
        // The next low episode without a very low rings as usual.
        (6..8).forEach { run.reading(t0 + it * 5 * minute, 3.7f) }
        assertEquals(PersistentLowAction.FIRE, run.reading(t0 + 45 * minute, 3.7f))
    }

    @Test
    fun plainLowPlaysNoPart() {
        // Mirrors PERSISTENT_HIGH next to HIGH: LOW having fired is not an input,
        // so the persistent low still rings as the "still low" reminder.
        val run = Run(mmolConfig(durationMinutes = 10))
        run.reading(t0, 3.5f)
        run.reading(t0 + 5 * minute, 3.5f)
        assertEquals(PersistentLowAction.FIRE, run.reading(t0 + 10 * minute, 3.5f))
    }

    @Test
    fun defaultsMatchThePlan() {
        val mmol = AlertDefaults.defaultConfig(AlertType.PERSISTENT_LOW, true)
        assertFalse(mmol.enabled)
        assertEquals(3.9f, mmol.threshold!!, 0.0001f)
        assertEquals(15, mmol.durationMinutes)
        assertEquals(0.2f, mmol.rearmMargin!!, 0.0001f)
        val mgdl = AlertDefaults.defaultConfig(AlertType.PERSISTENT_LOW, false)
        assertEquals(70f, mgdl.threshold!!, 0.0001f)
        assertEquals(3f, mgdl.rearmMargin!!, 0.0001f)
        assertTrue(AlertType.PERSISTENT_LOW in AlertType.settingsEntries)
        assertEquals(
            AlertType.settingsEntries.indexOf(AlertType.PERSISTENT_HIGH) + 1,
            AlertType.settingsEntries.indexOf(AlertType.PERSISTENT_LOW)
        )
        assertEquals(AlertType.PERSISTENT_LOW, AlertType.fromId(14))
    }
}
