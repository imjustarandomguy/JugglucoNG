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

    /**
     * Feeds readings through the policy, threading its state like the runtime
     * does. With [stored], an episode's start comes from those readings before
     * the current one, then the current one, as [EpisodeHistory.load] hands them over,
     * with [veryLow] as the runtime passes VERY_LOW's config to the walk.
     */
    private class Run(
        val config: AlertConfig,
        val isMmol: Boolean = true,
        val stored: List<StoredReading>? = null,
        val veryLow: AlertConfig? = null
    ) {
        var state = PersistentLowState()
        var historyReads = 0
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
                snoozed = snoozed,
                historyStartMs = {
                    historyReads++
                    stored?.let { readings ->
                        val upToNow = readings.filter { it.timeMs < atMs } + StoredReading(atMs, value!!)
                        PersistentLowPolicy.startFromHistory(upToNow, config, isMmol, veryLow)?.startedAtMs
                    } ?: 0L
                }
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
        assertTrue(run.state.heldByVeryLow)
        assertEquals(t0, run.state.startedAtMs)
    }

    @Test
    fun aDismissedVeryLowThenALowBetweenTheLinesRingsAfterTheDuration() {
        val run = Run(mmolConfig(durationMinutes = 15))
        run.reading(t0, 3.4f)
        // VERY_LOW fires and is dismissed: its episode runs until its condition clears.
        assertEquals(PersistentLowAction.HOLD, run.reading(t0 + 5 * minute, 2.9f, veryLowActive = true))
        assertEquals(PersistentLowAction.HOLD, run.reading(t0 + 10 * minute, 3.1f, veryLowActive = true))
        // Its episode is over, the value stays between the two lines: the count starts here.
        assertEquals(PersistentLowAction.WAIT, run.reading(t0 + 15 * minute, 3.6f))
        assertFalse(run.state.heldByVeryLow)
        assertEquals(t0 + 15 * minute, run.state.startedAtMs)
        assertEquals(PersistentLowAction.WAIT, run.reading(t0 + 20 * minute, 3.5f))
        assertEquals(PersistentLowAction.WAIT, run.reading(t0 + 25 * minute, 3.6f))
        assertEquals(PersistentLowAction.FIRE, run.reading(t0 + 30 * minute, 3.5f))
    }

    @Test
    fun aVeryLowEndingInTheBandRestartsAtTheNextLowReading() {
        val run = Run(mmolConfig(durationMinutes = 10))
        assertEquals(PersistentLowAction.HOLD, run.reading(t0, 2.9f, veryLowActive = true))
        assertEquals(PersistentLowAction.WAIT, run.reading(t0 + 5 * minute, 4.0f))
        assertEquals(PersistentLowState(), run.state)
        run.reading(t0 + 10 * minute, 3.7f)
        assertEquals(t0 + 10 * minute, run.state.startedAtMs)
        assertEquals(PersistentLowAction.WAIT, run.reading(t0 + 15 * minute, 3.7f))
        assertEquals(PersistentLowAction.FIRE, run.reading(t0 + 20 * minute, 3.7f))
    }

    @Test
    fun aRecoveryEndsAHeldEpisode() {
        val run = Run(mmolConfig(durationMinutes = 15))
        run.reading(t0, 2.9f, veryLowActive = true)
        assertEquals(PersistentLowAction.RESET, run.reading(t0 + 5 * minute, 4.4f))
        assertEquals(PersistentLowState(), run.state)
        // The next low episode without a very low rings as usual.
        (2..4).forEach { run.reading(t0 + it * 5 * minute, 3.7f) }
        assertEquals(PersistentLowAction.FIRE, run.reading(t0 + 25 * minute, 3.7f))
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

    /**
     * The evening behind the stored start (3.9 with a 0.6 margin, 15 min): one G7
     * reading every 5 minutes from 19:22:41 ([t0]) to 19:57:41. The alarm was
     * edited at 19:45:53. The phone then counted from its last reading, 19:42:41,
     * and rang at 19:57:41; the watch got the setting while asleep, counted from
     * 19:47:41 and was due at 20:02:41, when the value was back at the line.
     */
    private val tonight = listOf(3.67f, 3.61f, 3.5f, 3.5f, 3.44f, 3.56f, 3.67f, 3.78f)
        .mapIndexed { i, value -> StoredReading(t0 + i * 5 * minute, value) }

    @Test
    fun phoneAndWatchCountFromTheSameStoredStart() {
        val config = mmolConfig(rearmMargin = 0.6f)
        assertEquals(EpisodeStart(t0, 5), PersistentLowPolicy.startFromHistory(tonight.take(5), config, isMmol = true))
        // Phone: the 15 s check after the edit, on the 19:42:41 reading.
        val phone = Run(config, stored = tonight)
        assertEquals(PersistentLowAction.FIRE, phone.reading(tonight[4].timeMs, tonight[4].value))
        // Watch: its first look, on the 19:47:41 reading.
        val watch = Run(config, stored = tonight)
        assertEquals(PersistentLowAction.FIRE, watch.reading(tonight[5].timeMs, tonight[5].value))
        // Both count from 19:22:41, low for longer than 15 minutes already, so
        // both ring at that check instead of a fresh 15 minutes later.
        assertEquals(t0, phone.state.startedAtMs)
        assertEquals(t0, watch.state.startedAtMs)
        // A restart in the middle of the low finds the same start.
        val restarted = Run(config, stored = tonight)
        restarted.reading(tonight[7].timeMs, tonight[7].value)
        assertEquals(t0, restarted.state.startedAtMs)
    }

    @Test
    fun aStoredRecoveryEndsTheWalk() {
        val config = mmolConfig(rearmMargin = 0.6f)
        // 4.5 = 3.9 + 0.6 at 19:37:41: the low counts from 19:42:41.
        val recovered = tonight.mapIndexed { i, reading -> if (i == 3) reading.copy(value = 4.5f) else reading }
        val run = Run(config, stored = recovered)
        assertEquals(PersistentLowAction.WAIT, run.reading(recovered[5].timeMs, recovered[5].value))
        assertEquals(recovered[4].timeMs, run.state.startedAtMs)
        // 4.4 is in the band, not a recovery: the low goes on back to 19:22:41.
        val inBand = tonight.mapIndexed { i, reading -> if (i == 3) reading.copy(value = 4.4f) else reading }
        assertEquals(EpisodeStart(t0, 6), PersistentLowPolicy.startFromHistory(inBand.take(6), config, isMmol = true))
    }

    @Test
    fun storedReadingsInTheBandDoNotStartTheEpisode() {
        val config = mmolConfig(rearmMargin = 0.6f)
        // Recovered, hovering in the band, then the first reading below.
        val stored = listOf(4.6f, 4.0f, 4.2f, 3.8f).mapIndexed { i, value -> StoredReading(t0 + i * 5 * minute, value) }
        assertEquals(EpisodeStart(t0 + 15 * minute, 3), PersistentLowPolicy.startFromHistory(stored, config, isMmol = true))
        assertNull(PersistentLowPolicy.startFromHistory(stored.take(3), config, isMmol = true))
    }

    @Test
    fun storedStartInMgdl() {
        val config = AlertConfig(
            type = AlertType.PERSISTENT_LOW, enabled = true, threshold = 70f,
            durationMinutes = 15, rearmMargin = 3f
        )
        // 73 = 70 + 3 has recovered, 71 and 72 are the band, 68 starts the low.
        val stored = listOf(80f, 73f, 72f, 68f, 71f, 66f, 64f)
            .mapIndexed { i, value -> StoredReading(t0 + i * 5 * minute, value) }
        assertEquals(EpisodeStart(t0 + 15 * minute, 5), PersistentLowPolicy.startFromHistory(stored, config, isMmol = false))
        // 64 is 15 minutes after 68: due on its first look.
        val run = Run(config, isMmol = false, stored = stored)
        assertEquals(PersistentLowAction.FIRE, run.reading(t0 + 30 * minute, 64f))
        assertEquals(t0 + 15 * minute, run.state.startedAtMs)
    }

    @Test
    fun theHistoryIsReadOnlyWhenAnEpisodeStarts() {
        // Off, outside its hours, or in the band without an episode: nothing starts.
        val off = Run(mmolConfig(enabled = false), stored = tonight)
        off.reading(t0, 3.5f)
        off.reading(t0 + 5 * minute, 3.5f, activeNow = false)
        assertEquals(0, off.historyReads)

        val run = Run(mmolConfig(durationMinutes = 15), stored = emptyList())
        run.reading(t0, 4.0f)
        assertEquals(0, run.historyReads)
        run.reading(t0 + 5 * minute, 3.7f)
        assertEquals(1, run.historyReads)
        // Neither the 15 s checks nor the episode's later readings read it again.
        repeat(20) { run.reading(t0 + 5 * minute, 3.7f) }
        run.reading(t0 + 10 * minute, 4.0f)
        run.reading(t0 + 15 * minute, 3.6f)
        assertEquals(1, run.historyReads)
        // A recovery ends the episode; the next low reads it once more.
        run.reading(t0 + 20 * minute, 4.5f)
        run.reading(t0 + 25 * minute, 3.6f)
        assertEquals(2, run.historyReads)
    }

    @Test
    fun aStoredStartThatIsNotEarlierIsIgnored() {
        listOf(0L, t0, t0 + minute).forEach { storedStartMs ->
            val decision = PersistentLowPolicy.decide(
                state = PersistentLowState(),
                config = mmolConfig(),
                isMmol = true,
                activeNow = true,
                value = 3.7f,
                readingTimeMs = t0,
                rate = 0f,
                veryLowActive = false,
                snoozed = false,
                historyStartMs = { storedStartMs }
            )
            assertEquals(t0, decision.state.startedAtMs)
        }
    }

    @Test
    fun aLiveVeryLowHoldsWithoutReadingTheHistory() {
        val run = Run(mmolConfig(rearmMargin = 0.6f), stored = tonight)
        assertEquals(PersistentLowAction.HOLD, run.reading(tonight[4].timeMs, tonight[4].value, veryLowActive = true))
        assertEquals(0, run.historyReads)
    }

    /** VERY_LOW at 3.0 with a 0.6 margin: entered below 3.0, over at 3.6 and above. */
    private val veryLow = AlertConfig(type = AlertType.VERY_LOW, enabled = true, threshold = 3.0f, rearmMargin = 0.6f)

    /** 3.4 starts the low, 2.9 enters VERY_LOW, 3.3 keeps it, 3.7 ends it. */
    private val afterVeryLow = listOf(4.5f, 3.4f, 2.9f, 3.3f, 3.7f, 3.8f, 3.7f, 3.5f)
        .mapIndexed { i, value -> StoredReading(t0 + i * 5 * minute, value) }

    @Test
    fun theStoredStartRestartsWhereAVeryLowEnded() {
        val config = mmolConfig(durationMinutes = 15)
        assertEquals(
            EpisodeStart(t0 + 20 * minute, 7),
            PersistentLowPolicy.startFromHistory(afterVeryLow, config, isMmol = true, veryLow = veryLow)
        )
        // VERY_LOW off or outside its hours: the low counts from its first reading.
        assertEquals(t0 + 5 * minute, PersistentLowPolicy.startFromHistory(afterVeryLow, config, isMmol = true)?.startedAtMs)
        // Its episode still runs at the current reading (3.3 is under its exit line): no start.
        assertNull(PersistentLowPolicy.startFromHistory(afterVeryLow.take(4), config, isMmol = true, veryLow = veryLow))
        // Ended in the band: the next reading below starts the count.
        val bandEnd = afterVeryLow.mapIndexed { i, reading -> if (i == 4) reading.copy(value = 4.0f) else reading }
        assertEquals(
            t0 + 25 * minute,
            PersistentLowPolicy.startFromHistory(bandEnd, config, isMmol = true, veryLow = veryLow)?.startedAtMs
        )
    }

    @Test
    fun liveAndRestartedRunsAgreeAfterAVeryLow() {
        val config = mmolConfig(durationMinutes = 15)
        val veryLowAt = setOf(t0 + 10 * minute, t0 + 15 * minute)
        val live = Run(config, stored = afterVeryLow, veryLow = veryLow)
        val actions = afterVeryLow.map { live.reading(it.timeMs, it.value, veryLowActive = it.timeMs in veryLowAt) }
        assertEquals(PersistentLowAction.FIRE, actions.last())
        assertTrue(actions.dropLast(1).none { it == PersistentLowAction.FIRE })
        assertEquals(t0 + 20 * minute, live.state.startedAtMs)
        // A restart at any reading after VERY_LOW ended finds the same start.
        (4 until afterVeryLow.size).forEach { i ->
            val restarted = Run(config, stored = afterVeryLow, veryLow = veryLow)
            val action = restarted.reading(afterVeryLow[i].timeMs, afterVeryLow[i].value)
            assertEquals(t0 + 20 * minute, restarted.state.startedAtMs)
            assertEquals(if (i == afterVeryLow.lastIndex) PersistentLowAction.FIRE else PersistentLowAction.WAIT, action)
        }
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
