package tk.glucodata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.DisplayedReading.Freshness
import tk.glucodata.DisplayedReading.Origin
import tk.glucodata.DisplayedReading.Reason

/**
 * The one current reading of a device: when it publishes, what it publishes, and when the
 * shown reading goes stale by itself. Driven by a fake clock and a fake scheduler, so every
 * deadline is reached without waiting and without any event but the clock's.
 */
class CurrentReadingHubTests {
    private val minute = 60_000L
    private val t0 = 1_700_000_000_000L
    private val sensor = "G7-SENSOR"

    private class FakeScheduler : ReadingScheduler {
        var at = 0L
        var wake: Runnable? = null
        var schedules = 0

        override fun schedule(atMillis: Long, wake: Runnable) {
            at = atMillis
            this.wake = wake
            schedules++
        }

        override fun cancel() {
            at = 0L
            wake = null
        }
    }

    private inner class Rig(
        var inputs: ReadingHub.Inputs,
    ) {
        var now = t0
        var reads = 0
        val scheduler = FakeScheduler()
        val published = ArrayList<DisplayedReading>()
        val hub = ReadingHub(
            source = { reads++; inputs },
            worker = { it.run() },
            clock = ReadingClock(scheduler),
            now = { now },
        ).also { hub -> hub.addListener { published += it } }

        /** Advances the fake clock to [atMillis] and fires the wake if it is due. */
        fun advanceTo(atMillis: Long) {
            now = atMillis
            val wake = scheduler.wake
            if (wake != null && scheduler.at <= atMillis) {
                scheduler.wake = null
                wake.run()
            }
        }
    }

    private fun reading(
        timeMillis: Long,
        text: String = "120",
        rate: Float = 1.2f,
        isMmol: Boolean = false,
    ) = ReadingHub.Reading(
        sensorId = sensor,
        timeMillis = timeMillis,
        value = text.toFloat(),
        valueText = text,
        secondaryText = null,
        tertiaryText = null,
        isMmol = isMmol,
        viewMode = 0,
        rate = rate,
        index = 0,
    )

    private fun inputs(
        reading: ReadingHub.Reading?,
        declaredIntervalMs: Long = 5 * minute,
        recentTimes: List<Long> = emptyList(),
        sensorId: String? = sensor,
        latestTimeMillis: Long = 0L,
        newestReadingMillis: Long = 0L,
    ) = ReadingHub.Inputs(sensorId, reading, recentTimes, declaredIntervalMs, latestTimeMillis, newestReadingMillis)

    // --- The stale rule: the next reading was due and did not come. ---

    @Test
    fun aFiveMinuteSensorGoesStaleFiveMinutesTwentyAfterItsReading() {
        val composed = ReadingHub.compose(inputs(reading(t0)), t0)
        assertEquals(5 * minute, composed.intervalMillis)
        assertEquals(t0 + 5 * minute, composed.nextExpectedAtMillis)
        assertEquals(t0 + 5 * minute + 20_000L, composed.staleAtMillis)
        assertEquals(t0 + 30 * minute, composed.noValueAtMillis)
        assertEquals(Freshness.FRESH, ReadingHub.compose(inputs(reading(t0)), t0 + 5 * minute + 19_999L).freshness)
        assertEquals(Freshness.STALE, ReadingHub.compose(inputs(reading(t0)), t0 + 5 * minute + 20_000L).freshness)
    }

    @Test
    fun aOneMinuteSensorGoesStaleOneMinuteTwentyAfterItsReading() {
        val composed = ReadingHub.compose(inputs(reading(t0), declaredIntervalMs = minute), t0)
        assertEquals(t0 + minute + 20_000L, composed.staleAtMillis)
        assertEquals(
            Freshness.STALE,
            ReadingHub.compose(inputs(reading(t0), declaredIntervalMs = minute), t0 + 80_000L).freshness,
        )
    }

    @Test
    fun anUndeclaredIntervalIsTheMedianOfTheRecentGaps() {
        // Three-minute readings with one disconnect gap: the median ignores the gap.
        val times = listOf(t0 - 30 * minute, t0 - 9 * minute, t0 - 6 * minute, t0 - 3 * minute)
        val composed = ReadingHub.compose(inputs(reading(t0), declaredIntervalMs = 0L, recentTimes = times), t0)
        assertEquals(3 * minute, composed.intervalMillis)
        assertEquals(t0 + 3 * minute + 20_000L, composed.staleAtMillis)
    }

    @Test
    fun aReadingStoredTwiceIsNotAGap() {
        // The live reading and its stored row, a second apart, then one-minute readings.
        val times = listOf(t0 - 2 * minute, t0 - minute, t0 - 1_000L)
        assertEquals(minute, ReadingCadence.intervalMillis(0L, times + t0))
    }

    @Test
    fun aMeasuredIntervalIsBoundedAndDefaultsToFiveMinutes() {
        assertEquals(ReadingCadence.DEFAULT_INTERVAL_MS, ReadingCadence.intervalMillis(0L, listOf(t0)))
        assertEquals(ReadingCadence.DEFAULT_INTERVAL_MS, ReadingCadence.intervalMillis(0L, emptyList()))
        assertEquals(
            ReadingCadence.MAX_INTERVAL_MS,
            ReadingCadence.intervalMillis(0L, listOf(t0 - 80 * minute, t0 - 40 * minute, t0)),
        )
        assertEquals(
            ReadingCadence.MIN_INTERVAL_MS,
            ReadingCadence.intervalMillis(0L, listOf(t0 - 80_000L, t0 - 40_000L, t0)),
        )
    }

    @Test
    fun theDeclaredIntervalWinsOverTheMeasuredOne() {
        val oneMinuteRows = (1..10).map { t0 - it * minute }
        assertEquals(5 * minute, ReadingCadence.intervalMillis(5 * minute, oneMinuteRows))
    }

    @Test
    fun thirtyMinutesAfterItsReadingTheValueIsTakenDown() {
        val composed = ReadingHub.compose(inputs(reading(t0)), t0 + 30 * minute)
        assertEquals(Freshness.NO_VALUE, composed.freshness)
        assertFalse(composed.showsValue)
        assertEquals(DisplayedReading.NO_VALUE_TEXT, composed.shownText)
        assertTrue(composed.value.isNaN())
        assertTrue(composed.shownRate.isNaN())
        // Taken down the same way whether or not the resolver still found the reading.
        assertTrue(composed.sameDisplayAs(ReadingHub.compose(inputs(null, latestTimeMillis = t0), t0 + 30 * minute)))
    }

    @Test
    fun aStaleReadingKeepsItsValueAndDropsItsArrow() {
        val stale = ReadingHub.compose(inputs(reading(t0)), t0 + 6 * minute)
        assertTrue(stale.isDimmed)
        assertEquals("120", stale.shownText)
        assertFalse(stale.showsArrow)
        assertTrue(stale.shownRate.isNaN())
        val fresh = ReadingHub.compose(inputs(reading(t0)), t0 + minute)
        assertEquals(1.2f, fresh.shownRate, 0f)
        assertEquals(TrendArrowAngle.rotationDegrees(1.2f), fresh.arrowDegrees, 0f)
    }

    @Test
    fun noSensorAndAwaitingFollowDisplayDataState() {
        assertEquals(Freshness.NO_SENSOR, ReadingHub.compose(inputs(null, sensorId = null), t0).freshness)
        assertEquals(Freshness.AWAITING, ReadingHub.compose(inputs(null), t0).freshness)
        assertEquals(Freshness.NO_VALUE, ReadingHub.compose(inputs(null, latestTimeMillis = t0 - 40 * minute), t0).freshness)
    }

    @Test
    fun aReadingShownAtItsChunksEndCountsItsDeadlinesFromTheNewestReading() {
        // A one-minute sensor with smoothing collapsed into five-minute chunks: the point
        // shown is the end of the last full chunk, four minutes behind the newest reading.
        val chunked = inputs(reading(t0 - 4 * minute), declaredIntervalMs = minute, newestReadingMillis = t0)
        val composed = ReadingHub.compose(chunked, t0 + 30_000L)
        assertEquals(Freshness.FRESH, composed.freshness)
        assertEquals(t0 - 4 * minute, composed.timeMillis)
        assertEquals(t0, composed.lastReadingMillis)
        assertEquals(t0 + minute, composed.nextExpectedAtMillis)
        assertEquals(t0 + minute + 20_000L, composed.staleAtMillis)
        assertEquals(t0 + 30 * minute, composed.noValueAtMillis)
        assertEquals(1.2f, composed.shownRate, 0f)
        assertEquals(Freshness.STALE, ReadingHub.compose(chunked, t0 + minute + 20_000L).freshness)
    }

    @Test
    fun theClockWakesAtTheNewestReadingsDeadlineNotTheChunks() {
        val rig = Rig(inputs(reading(t0 - 4 * minute), declaredIntervalMs = minute, newestReadingMillis = t0))
        assertEquals(t0 + minute + 20_000L, rig.scheduler.at)
        assertEquals(Freshness.FRESH, rig.hub.current().freshness)
        // The next one-minute reading lands in the same chunk: the shown point does not move.
        rig.inputs = inputs(reading(t0 - 4 * minute), declaredIntervalMs = minute, newestReadingMillis = t0 + minute)
        rig.now = t0 + minute + 2_000L
        rig.hub.onReadingAccepted(sensor, t0 + minute, Origin.LOCAL)
        assertEquals(t0 + 2 * minute + 20_000L, rig.scheduler.at)
        assertEquals(Freshness.FRESH, rig.hub.current().freshness)
        assertEquals(Origin.LOCAL, rig.hub.current().origin)
        rig.advanceTo(t0 + 2 * minute + 20_000L)
        assertEquals(Freshness.STALE, rig.hub.current().freshness)
    }

    @Test
    fun aReadingStampedAheadOfTheClockDoesNotKeepTheReadingFresh() {
        val rows = listOf(GlucosePoint(t0 - 2 * minute, 110f), GlucosePoint(t0 + 2 * 60 * minute, 120f))
        assertEquals(listOf(t0 - 2 * minute), StoredReadingSource.notAhead(rows, t0).map { it.timestamp })
        assertEquals(t0 - 2 * minute, StoredReadingSource.newestReadingMillis(rows, 0L, t0))
        // The live reading is newer than every row; a few seconds ahead of this clock still counts.
        assertEquals(t0 - 20_000L, StoredReadingSource.newestReadingMillis(rows, t0 - 20_000L, t0))
        assertEquals(t0 + 5_000L, StoredReadingSource.newestReadingMillis(rows, t0 + 5_000L, t0))
        assertEquals(t0 - 2 * minute, StoredReadingSource.newestReadingMillis(rows, t0 + 10 * minute, t0))
        assertEquals(0L, StoredReadingSource.newestReadingMillis(emptyList(), 0L, t0))
        val past = rows.subList(0, 1)
        assertTrue("nothing ahead, nothing copied", past === StoredReadingSource.notAhead(past, t0))
    }

    @Test
    fun mgdlFollowsTheDisplayUnit() {
        val mmol = ReadingHub.compose(inputs(reading(t0, text = "6.7", isMmol = true)), t0)
        assertEquals(6.7f * 18.0182f, mmol.mgdl, 0.001f)
        assertEquals(120f, ReadingHub.compose(inputs(reading(t0)), t0).mgdl, 0f)
    }

    // --- Publishing. ---

    @Test
    fun nothingIsReadUntilTheHubIsUsed() {
        var reads = 0
        val hub = ReadingHub({ reads++; inputs(reading(t0)) }, { it.run() }, ReadingClock(FakeScheduler()), { t0 })
        hub.onReadingAccepted(sensor, t0, Origin.LOCAL)
        hub.onDisplayInputsChanged(Reason.BACKFILL)
        assertEquals(0, reads)
        // The first reader starts it; the requests made while idle fold into that one read.
        val first = hub.current()
        assertEquals(1, reads)
        assertEquals(Reason.STARTUP, first.reason)
        assertEquals("120", first.shownText)
    }

    @Test
    fun aSurfaceDrawnAtStartWaitsForTheFirstPublish() {
        val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val hub = ReadingHub({ Thread.sleep(50L); inputs(reading(t0)) }, worker, ReadingClock(FakeScheduler()), { t0 })
            val shown = hub.awaitCurrent(5_000L)
            assertEquals(1L, shown.revision)
            assertEquals("120", shown.shownText)
            // One flow for every collector, not a new one per read.
            assertTrue(hub.readings === hub.readings)
        } finally {
            worker.shutdownNow()
        }
    }

    @Test
    fun theFirstPublishIsStartupAndTheSameReadingIsNotPublishedAgain() {
        val rig = Rig(inputs(reading(t0)))
        assertEquals(1, rig.published.size)
        assertEquals(Reason.STARTUP, rig.published[0].reason)
        assertEquals(1L, rig.published[0].revision)
        rig.hub.onDisplayInputsChanged(Reason.BACKFILL)
        rig.hub.onReadingAccepted(sensor, t0, Origin.LOCAL)
        assertEquals(3, rig.reads)
        assertEquals(1, rig.published.size)
        assertEquals(1L, rig.hub.current().revision)
    }

    @Test
    fun aNewerReadingIsPublishedAsNewWhateverAskedForIt() {
        val rig = Rig(inputs(reading(t0)))
        rig.inputs = inputs(reading(t0 + 5 * minute, text = "125"))
        rig.advanceTo(t0 + 5 * minute + 3_000L)
        rig.hub.onDisplayInputsChanged(Reason.BACKFILL)
        assertEquals(Reason.NEW_READING, rig.published.last().reason)
        assertEquals("125", rig.published.last().shownText)
    }

    @Test
    fun aChangeBehindTheSameReadingIsABackfill() {
        val rig = Rig(inputs(reading(t0)))
        rig.inputs = inputs(reading(t0, rate = -0.3f))
        rig.hub.onReadingAccepted(sensor, t0 - 5 * minute, Origin.BACKFILL)
        assertEquals(2, rig.published.size)
        assertEquals(Reason.BACKFILL, rig.published.last().reason)
    }

    @Test
    fun anotherUnitOrSensorIsASettingWhateverAskedForIt() {
        val rig = Rig(inputs(reading(t0)))
        rig.inputs = inputs(reading(t0, text = "6.7", isMmol = true))
        rig.hub.onDisplayInputsChanged(Reason.BACKFILL)
        assertEquals(Reason.SETTINGS, rig.published.last().reason)
        rig.inputs = inputs(reading(t0, text = "6.7", isMmol = true).copy(sensorId = "OTHER"))
        rig.hub.onDisplayInputsChanged(Reason.BACKFILL)
        assertEquals(Reason.SETTINGS, rig.published.last().reason)
        assertEquals(3, rig.published.size)
    }

    @Test
    fun theAcceptingPathNamesTheOrigin() {
        val rig = Rig(inputs(reading(t0)))
        assertEquals(Origin.STORED, rig.published.last().origin)
        rig.inputs = inputs(reading(t0 + 5 * minute))
        rig.now = t0 + 5 * minute
        rig.hub.onReadingAccepted(sensor, t0 + 5 * minute, Origin.LOCAL)
        assertEquals(Origin.LOCAL, rig.published.last().origin)
    }

    @Test
    fun theClockTakesTheReadingStaleThenDownWithNoOtherEvent() {
        val rig = Rig(inputs(reading(t0)))
        assertEquals(t0 + 5 * minute + 20_000L, rig.scheduler.at)

        rig.advanceTo(t0 + 5 * minute + 20_000L)
        assertEquals(2, rig.published.size)
        val stale = rig.published.last()
        assertEquals(Reason.STALE_TICK, stale.reason)
        assertEquals(Freshness.STALE, stale.freshness)
        assertEquals(t0 + 30 * minute, rig.scheduler.at)

        val schedules = rig.scheduler.schedules
        rig.advanceTo(t0 + 30 * minute)
        assertEquals(3, rig.published.size)
        assertEquals(Freshness.NO_VALUE, rig.published.last().freshness)
        assertEquals(Reason.STALE_TICK, rig.published.last().reason)
        // Nothing left to change by itself: no wake.
        assertEquals(schedules, rig.scheduler.schedules)
        assertEquals(null, rig.scheduler.wake)
        assertEquals(listOf(1L, 2L, 3L), rig.published.map { it.revision })
    }

    @Test
    fun aOneMinuteSensorIsStaleAfterOneMinuteTwenty() {
        val rig = Rig(inputs(reading(t0), declaredIntervalMs = minute))
        assertEquals(t0 + 80_000L, rig.scheduler.at)
        rig.advanceTo(t0 + 80_000L)
        assertEquals(Freshness.STALE, rig.hub.current().freshness)
    }

    @Test
    fun aNewReadingMovesTheWakeBeforeItFires() {
        val rig = Rig(inputs(reading(t0)))
        rig.inputs = inputs(reading(t0 + 5 * minute))
        rig.advanceTo(t0 + 5 * minute + 2_000L)
        rig.hub.onReadingAccepted(sensor, t0 + 5 * minute, Origin.LOCAL)
        assertEquals(t0 + 10 * minute + 20_000L, rig.scheduler.at)
        assertEquals(Freshness.FRESH, rig.hub.current().freshness)
    }

    @Test
    fun anEarlyWakeArmsTheSameDeadlineAgain() {
        val rig = Rig(inputs(reading(t0)))
        val schedules = rig.scheduler.schedules
        rig.now = t0 + 5 * minute
        rig.hub.onDeadline()
        assertEquals(schedules + 1, rig.scheduler.schedules)
        assertEquals(t0 + 5 * minute + 20_000L, rig.scheduler.at)
        assertEquals(1, rig.published.size)
    }

    @Test
    fun aClockChangeReconcilesFreshness() {
        val rig = Rig(inputs(reading(t0)))
        // The device slept through the deadline and its wake was lost.
        rig.scheduler.wake = null
        rig.now = t0 + 7 * minute
        rig.hub.onClockChanged()
        assertEquals(Freshness.STALE, rig.hub.current().freshness)
        assertEquals(Reason.STALE_TICK, rig.hub.current().reason)
    }

    @Test
    fun sameDisplayIgnoresRevisionReasonAndOrigin() {
        val a = ReadingHub.compose(inputs(reading(t0)), t0)
        val b = a.copy(revision = 9L, reason = Reason.NEW_READING, origin = Origin.PEER)
        assertTrue(a.sameDisplayAs(b))
        assertFalse(a.sameDisplayAs(a.copy(rate = 0.4f)))
        assertFalse(a.sameDisplayAs(a.copy(freshness = Freshness.STALE)))
    }

    @Test
    fun theClockArmsOncePerDeadline() {
        val scheduler = FakeScheduler()
        val clock = ReadingClock(scheduler)
        val shown = ReadingHub.compose(inputs(reading(t0)), t0)
        clock.arm(shown, Runnable { })
        clock.arm(shown, Runnable { })
        assertEquals(1, scheduler.schedules)
        clock.arm(ReadingHub.compose(inputs(null, sensorId = null), t0), Runnable { })
        assertEquals(0L, scheduler.at)
    }
}
