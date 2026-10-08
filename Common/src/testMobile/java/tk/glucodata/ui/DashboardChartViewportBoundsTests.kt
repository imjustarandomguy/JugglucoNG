package tk.glucodata.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tk.glucodata.data.journal.JournalChartMarker
import tk.glucodata.data.journal.JournalEntryType

class DashboardChartViewportBoundsTests {

    private val minute = 60_000L
    private val hour = 60L * minute
    private val now = 1_000_000L * hour

    private fun limits(
        latest: Long = now - 2 * minute,
        earliest: Long = now - 30L * 24L * hour,
        predictionHorizon: Long? = null
    ) = ChartViewportLimits(latest, earliest, predictionHorizon)

    private fun rightEdge(center: Long, duration: Long) = center + duration / 2L
    private fun leftEdge(center: Long, duration: Long) = center - duration / 2L

    @Test
    fun rightEdgeStopsTenMinutesPastNowWithoutPrediction() {
        val duration = 3L * hour
        val center = limits().clampCenter(now + 5L * hour, duration, now)

        assertEquals(now + 10L * minute, rightEdge(center, duration))
    }

    @Test
    fun rightEdgeStopsAtThePredictionHorizonWhilePredicting() {
        val duration = 3L * hour
        val center = limits(predictionHorizon = 90L * minute).clampCenter(now + 5L * hour, duration, now)

        assertEquals(now + 90L * minute, rightEdge(center, duration))
    }

    @Test
    fun rightEdgeCountsFromAReadingAheadOfTheClock() {
        val duration = hour
        val center = limits(latest = now + 3L * minute).clampCenter(now + hour, duration, now)

        assertEquals(now + 13L * minute, rightEdge(center, duration))
    }

    @Test
    fun aWindowInsideTheBoundsIsLeftAlone() {
        val duration = 3L * hour
        val center = now - 5L * hour

        assertEquals(center, limits().clampCenter(center, duration, now))
    }

    @Test
    fun leftEdgeStopsJustBeforeTheEarliestReading() {
        val earliest = now - 48L * hour
        val duration = 6L * hour
        val center = limits(earliest = earliest).clampCenter(now - 100L * hour, duration, now)

        assertEquals(earliest - ChartViewportLimits.PAST_MARGIN_MS, leftEdge(center, duration))
    }

    @Test
    fun withoutDataOnlyTheRightBoundApplies() {
        val noData = ChartViewportLimits(latestDataMs = 0L, earliestDataMs = 0L, predictionHorizonMs = null)
        val far = now - 1000L * hour

        assertNull(noData.leftEdgeLimit)
        assertEquals(far, noData.clampCenter(far, hour, now))
    }

    @Test
    fun dataShorterThanTheWindowPinsItToTheRightBound() {
        val duration = 24L * hour
        val shortData = limits(earliest = now - 3L * hour)

        val fromLeft = shortData.clampCenter(now - 30L * hour, duration, now)
        val fromRight = shortData.clampCenter(now + 30L * hour, duration, now)

        assertEquals(now + 10L * minute, rightEdge(fromLeft, duration))
        assertEquals(fromLeft, fromRight)
    }

    @Test
    fun zoomingOutAtTheRightBoundIsClampedAgain() {
        val bounds = limits()
        val narrow = hour
        val atRightBound = bounds.clampCenter(now + 10L * hour, narrow, now)
        assertEquals(now + 10L * minute, rightEdge(atRightBound, narrow))

        // Same center, wider window: its right edge would pass the bound.
        val wide = 12L * hour
        val reclamped = bounds.clampCenter(atRightBound, wide, now)

        assertEquals(now + 10L * minute, rightEdge(reclamped, wide))
    }

    @Test
    fun zoomingOutAtTheLeftBoundIsClampedAgain() {
        val earliest = now - 72L * hour
        val bounds = limits(earliest = earliest)
        val narrow = hour
        val atLeftBound = bounds.clampCenter(earliest - 10L * hour, narrow, now)
        assertEquals(earliest - ChartViewportLimits.PAST_MARGIN_MS, leftEdge(atLeftBound, narrow))

        val wide = 24L * hour
        val reclamped = bounds.clampCenter(atLeftBound, wide, now)

        assertEquals(earliest - ChartViewportLimits.PAST_MARGIN_MS, leftEdge(reclamped, wide))
    }

    @Test
    fun maxCenterPutsTheRightEdgeOnTheBound() {
        val duration = 6L * hour
        val bounds = limits(predictionHorizon = 2L * hour)

        assertEquals(now + 2L * hour, rightEdge(bounds.maxCenter(duration, now), duration))
    }

    // ---- Journal content ahead of now ----

    private fun marker(timestamp: Long, type: JournalEntryType, activeEnd: Long? = null) = JournalChartMarker(
        entryId = timestamp,
        timestamp = timestamp,
        type = type,
        title = "",
        accentColor = 0,
        badgeText = "",
        detailText = "",
        activeEndMillis = activeEnd
    )

    private fun journalLimits(markers: List<JournalChartMarker>, predictionHorizon: Long? = null) =
        ChartViewportLimits(
            latestDataMs = now - 2 * minute,
            earliestDataMs = now - 30L * 24L * hour,
            predictionHorizonMs = predictionHorizon,
            journalContentEndMs = latestJournalContentEndMs(markers)
        )

    @Test
    fun rightEdgeReachesTheEndOfAnInsulinCurve() {
        val dose = marker(now - 30L * minute, JournalEntryType.INSULIN, activeEnd = now + 4L * hour)
        val duration = 3L * hour
        val center = journalLimits(listOf(dose)).clampCenter(now + 10L * hour, duration, now)

        assertEquals(now + 4L * hour + ChartViewportLimits.FUTURE_MARGIN_MS, rightEdge(center, duration))
    }

    @Test
    fun rightEdgeReachesAnEntryDatedAhead() {
        val planned = marker(now + 2L * hour, JournalEntryType.NOTE)
        val duration = hour
        val center = journalLimits(listOf(planned)).clampCenter(now + 10L * hour, duration, now)

        assertEquals(now + 2L * hour + ChartViewportLimits.FUTURE_MARGIN_MS, rightEdge(center, duration))
    }

    @Test
    fun journalContentThatHasEndedKeepsTheDefaultEdge() {
        val markers = listOf(
            marker(now - 6L * hour, JournalEntryType.INSULIN, activeEnd = now - hour),
            marker(now - 3L * minute, JournalEntryType.CARBS)
        )
        val duration = 3L * hour
        val center = journalLimits(markers).clampCenter(now + 10L * hour, duration, now)

        assertEquals(now + 10L * minute, rightEdge(center, duration))
    }

    @Test
    fun theFurtherOfPredictionAndJournalContentWins() {
        val dose = marker(now, JournalEntryType.INSULIN, activeEnd = now + 30L * minute)
        val duration = 3L * hour

        val predicting = journalLimits(listOf(dose), predictionHorizon = 90L * minute)
        assertEquals(now + 90L * minute, rightEdge(predicting.maxCenter(duration, now), duration))

        val longDose = marker(now, JournalEntryType.INSULIN, activeEnd = now + 5L * hour)
        val both = journalLimits(listOf(dose, longDose), predictionHorizon = 90L * minute)
        assertEquals(now + 5L * hour + 10L * minute, rightEdge(both.maxCenter(duration, now), duration))
    }

    @Test
    fun latestContentEndTakesCurvesActivitiesAndEntryTimes() {
        assertNull(latestJournalContentEndMs(emptyList()))
        val markers = listOf(
            marker(now - hour, JournalEntryType.INSULIN, activeEnd = now + 3L * hour),
            marker(now + hour, JournalEntryType.ACTIVITY, activeEnd = now + 2L * hour),
            marker(now + 4L * hour, JournalEntryType.NOTE)
        )
        assertEquals(now + 4L * hour, latestJournalContentEndMs(markers))
        assertEquals(now + 3L * hour, latestJournalContentEndMs(markers.take(2)))
    }
}
