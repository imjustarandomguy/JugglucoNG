package tk.glucodata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Who reads a sensor itself, as the screens say it. On 2026-10-06 the phone said
 * "Watch connected to sensor" through three hours of the watch not reading a G7
 * at all; these pin what replaced that.
 */
class DirectReadingStatusTests {
    private val now = 1_800_000_000_000L
    private val minute = 60_000L

    private fun ago(minutes: Long) = DirectReadingFact.at(now - minutes * minute)
    private val never = DirectReadingFact.at(0L)
    private val unknown = DirectReadingFact.UNKNOWN

    private fun view(
        phone: DirectReadingFact = ago(1),
        watch: DirectReadingFact = ago(2),
        directOnWatch: Boolean = true,
        readsAlongside: Boolean = true,
        untimedSince: Long = 0L,
    ) = DirectReadingView(phone, watch, directOnWatch, readsAlongside, untimedSince, now)

    // ------------------------------------------------------------- status

    @Test
    fun bothReadingInTheLastQuarterHourIsPhoneAndWatch() {
        assertEquals(DirectReadingSummary.PHONE_AND_WATCH, view(ago(1), ago(14)).summary)
    }

    @Test
    fun onlyThePhoneReadingIsPhone() {
        assertEquals(DirectReadingSummary.PHONE, view(ago(1), ago(16)).summary)
        assertEquals(DirectReadingSummary.PHONE, view(ago(1), unknown).summary)
        assertEquals(DirectReadingSummary.PHONE, view(ago(1), never).summary)
    }

    @Test
    fun onlyTheWatchReadingIsWatch() {
        assertEquals(DirectReadingSummary.WATCH, view(ago(20), ago(3)).summary)
        assertEquals(DirectReadingSummary.WATCH, view(never, ago(3)).summary)
    }

    @Test
    fun neitherForOverAQuarterHourKeepsTheOldStatus() {
        assertEquals(DirectReadingSummary.NEITHER, view(ago(16), ago(16)).summary)
        assertEquals(DirectReadingSummary.NEITHER, view(never, unknown).summary)
    }

    @Test
    fun fifteenMinutesIsStillReading() {
        assertTrue(ago(15).isReading(now))
        assertFalse(DirectReadingFact.at(now - 15 * minute - 1).isReading(now))
    }

    // -------------------------------------------------------------- lines

    @Test
    fun aDeviceReadingItSaysWhenItLastDid() {
        val line = view(phone = ago(3)).phoneLine
        assertEquals(DirectReadingLineKind.READING, line.kind)
        assertEquals(now - 3 * minute, line.lastMs)
    }

    @Test
    fun aWatchThatStoppedGetsItsValuesFromThePhone() {
        val line = view(phone = ago(1), watch = ago(180)).watchLine
        assertEquals(DirectReadingLineKind.FROM_OTHER, line.kind)
        assertEquals(now - 180 * minute, line.lastMs)
    }

    @Test
    fun aPhoneThatStoppedGetsItsValuesFromTheWatch() {
        assertEquals(DirectReadingLineKind.FROM_OTHER, view(phone = ago(40), watch = ago(2)).phoneLine.kind)
    }

    @Test
    fun withNeitherReadingNobodyIsGettingValuesFromTheOther() {
        val both = view(phone = ago(40), watch = ago(30))
        assertEquals(DirectReadingLineKind.NOT_READING, both.phoneLine.kind)
        assertEquals(DirectReadingLineKind.NOT_READING, both.watchLine.kind)
    }

    @Test
    fun aDeviceThatNeverReadItHasNoTimeToGive() {
        val line = view(phone = ago(1), watch = never).watchLine
        assertEquals(DirectReadingLineKind.FROM_OTHER, line.kind)
        assertEquals(0L, line.lastMs)
    }

    @Test
    fun anOlderPeerIsUnknownNotNotReading() {
        assertEquals(DirectReadingLineKind.UNKNOWN, view(watch = unknown).watchLine.kind)
        assertEquals(DirectReadingLineKind.UNKNOWN, view(phone = unknown).phoneLine.kind)
    }

    // ----------------------------------------------------------- red rule

    @Test
    fun theWatchLineIsRedOnceItsReadingIsOlderThanAQuarterHour() {
        assertFalse(view(watch = ago(15)).watchAlert)
        assertTrue(view(watch = ago(16)).watchAlert)
        assertTrue(view(watch = ago(180)).watchAlert)
    }

    @Test
    fun neverRedWhileTheWatchIsNotToldToRead() {
        assertFalse(view(watch = ago(180), directOnWatch = false).watchAlert)
        assertFalse(view(watch = unknown, directOnWatch = false, untimedSince = now - 60 * minute).watchAlert)
    }

    @Test
    fun anUnknownWatchTurnsRedOnlyAfterAQuarterHourOfNotKnowing() {
        assertFalse(view(watch = unknown, untimedSince = now - 15 * minute).watchAlert)
        assertTrue(view(watch = unknown, untimedSince = now - 16 * minute).watchAlert)
        assertFalse(view(watch = never, untimedSince = now - 5 * minute).watchAlert)
        assertTrue(view(watch = never, untimedSince = now - 20 * minute).watchAlert)
        // No clock running yet: nothing to go on, so not red.
        assertFalse(view(watch = unknown, untimedSince = 0L).watchAlert)
    }

    @Test
    fun theUntimedClockStartsOnceAndStopsWithATimeOrDirectOff() {
        assertEquals(now, DirectReadingStatus.untimedSince(true, unknown, null, now))
        assertEquals(now - minute, DirectReadingStatus.untimedSince(true, unknown, now - minute, now))
        assertEquals(now - minute, DirectReadingStatus.untimedSince(true, never, now - minute, now))
        assertNull(DirectReadingStatus.untimedSince(true, ago(2), now - minute, now))
        // Off and on again gives the watch its full window back.
        assertNull(DirectReadingStatus.untimedSince(false, unknown, now - 60 * minute, now))
    }

    // ------------------------------------------------- read by both devices

    @Test
    fun onlyASensorBothDevicesCanReadAtOnceChangesItsStatus() {
        assertTrue(view(readsAlongside = true, directOnWatch = true).readByBoth)
        assertFalse(view(readsAlongside = false, directOnWatch = true).readByBoth)
    }

    @Test
    fun aSensorTheWatchWasNotToldToReadKeepsItsStatus() {
        assertFalse(view(watch = unknown, directOnWatch = false).readByBoth)
        assertFalse(view(watch = ago(30), directOnWatch = false).readByBoth)
        // Still reading it as the switch goes off: say so until it stops.
        assertTrue(view(watch = ago(2), directOnWatch = false).readByBoth)
    }

    // --------------------------------------------------------------- wire

    @Test
    fun theAgeTravelsSoTheClocksAreNeverCompared() {
        val sentAt = now
        val age = DirectReadingStatus.wireAge(sentAt - 3 * minute, sentAt)
        assertEquals(3 * minute, age)
        // Received two seconds later on a clock an hour off: still three minutes old.
        val receivedAt = now + 3_600_000L + 2_000L
        val fact = DirectReadingStatus.fromWire(age, receivedAt)
        assertEquals(receivedAt - 3 * minute, fact.lastMs)
        assertTrue(fact.isReading(receivedAt))
    }

    @Test
    fun neverAndMissingAreDifferentOnTheWire() {
        assertEquals(-1L, DirectReadingStatus.wireAge(0L, now))
        assertEquals(never, DirectReadingStatus.fromWire(-1L, now))
        assertEquals(unknown, DirectReadingStatus.fromWire(null, now))
    }

    @Test
    fun aReadingStampedAheadOfTheClockIsAgeZero() {
        assertEquals(0L, DirectReadingStatus.wireAge(now + 5_000L, now))
    }

    // ------------------------------------------------------------ cadence

    @Test
    fun aNewerReadingIsReportedWhileTheWatchIsToldToRead() {
        assertTrue(
            DirectReadingStatus.reportDue(
                directOnWatch = true,
                directReadingMs = now,
                lastReportedDirectMs = now - 5 * minute,
                lastReportedAtMs = now - 5 * minute,
                nowMs = now,
            ),
        )
    }

    @Test
    fun nothingExtraIsSentWithDirectOff() {
        assertFalse(
            DirectReadingStatus.reportDue(
                directOnWatch = false,
                directReadingMs = now,
                lastReportedDirectMs = now - 5 * minute,
                lastReportedAtMs = now - 5 * minute,
                nowMs = now,
            ),
        )
    }

    @Test
    fun theSameReadingIsNotSentTwice() {
        assertFalse(
            DirectReadingStatus.reportDue(
                directOnWatch = true,
                directReadingMs = now - 5 * minute,
                lastReportedDirectMs = now - 5 * minute,
                lastReportedAtMs = now - 5 * minute,
                nowMs = now,
            ),
        )
    }

    @Test
    fun aOneMinuteSensorIsReportedEveryFewMinutesNotEveryReading() {
        val due = { lastAt: Long ->
            DirectReadingStatus.reportDue(
                directOnWatch = true,
                directReadingMs = now,
                lastReportedDirectMs = now - minute,
                lastReportedAtMs = lastAt,
                nowMs = now,
            )
        }
        assertFalse(due(now - minute))
        assertFalse(due(now - 3 * minute))
        assertTrue(due(now - DirectReadingStatus.REPORT_MIN_INTERVAL_MS))
        // Often enough that the other side never sees a reading device as stale.
        assertTrue(DirectReadingStatus.REPORT_MIN_INTERVAL_MS + 5 * minute < DIRECT_READING_FRESH_MS)
    }
}
