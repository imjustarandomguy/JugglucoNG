package tk.glucodata

import java.io.File
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

    @Test
    fun aFirstDirectReadingIsReportedAtOnce() {
        // The watch said it holds the G7 ten seconds ago, before its first
        // reading was noted: the phone has to hear about that reading now, not
        // five minutes later with the next one.
        assertTrue(
            DirectReadingStatus.reportDue(
                directOnWatch = true,
                directReadingMs = now,
                lastReportedDirectMs = 0L,
                lastReportedAtMs = now - 10_000L,
                nowMs = now,
            ),
        )
    }

    @Test
    fun theFirstReadingAfterAPauseIsReportedAtOnce() {
        assertTrue(
            DirectReadingStatus.reportDue(
                directOnWatch = true,
                directReadingMs = now,
                lastReportedDirectMs = now - 40 * minute,
                lastReportedAtMs = now - minute,
                nowMs = now,
            ),
        )
        // Still nothing extra with direct off.
        assertFalse(
            DirectReadingStatus.reportDue(
                directOnWatch = false,
                directReadingMs = now,
                lastReportedDirectMs = 0L,
                lastReportedAtMs = now - 10_000L,
                nowMs = now,
            ),
        )
    }

    // ---------------------------------------------------------- noting it

    @Test
    fun aDirectReadingCountsAtItsOwnTimeNotWhenItArrived() {
        // A G7 answering with the reading before: five minutes old on arrival.
        assertEquals(now - 5 * minute, DirectReadingStatus.directReadingTime(now - 5 * minute, now))
        // Never ahead of this device's clock, and now when the driver gave no time.
        assertEquals(now, DirectReadingStatus.directReadingTime(now + 5_000L, now))
        assertEquals(now, DirectReadingStatus.directReadingTime(0L, now))
    }

    private fun isNew(readingMs: Long, lastDirectMs: Long, lastSyncedMs: Long = 0L) =
        DirectReadingStatus.isNewDirectReading(readingMs, now, lastDirectMs, lastSyncedMs)

    @Test
    fun theReadingBeforeServedAgainIsNotANewDirectReading() {
        // 2026-10-09 21:22: the phone read 21:17 itself, then asked a moment
        // early and the G7 answered with 21:17 again (age=300), stamped a
        // second either side of the first time. It said "reading, last 1 min ago".
        val last = now - 5 * minute
        assertFalse(isNew(last + 900L, last))
        assertFalse(isNew(last - 900L, last))
        assertFalse(isNew(last - 5 * minute, last))
        assertTrue(isNew(now - 4_000L, last))
    }

    @Test
    fun theReadingBeforeThatCameFromTheOtherDeviceIsNotNewEither() {
        // The phone missed T, the watch read it and the phone was given it; asked
        // a moment early, the G7 then answers the phone with T.
        val missed = now - 5 * minute
        assertFalse(isNew(missed + 700L, lastDirectMs = missed - 5 * minute, lastSyncedMs = missed))
        // One nobody gave it is a reading it lacked: it counts, at its own time.
        assertTrue(isNew(missed, lastDirectMs = missed - 5 * minute, lastSyncedMs = missed - 5 * minute))
    }

    @Test
    fun aLiveReadingTheOtherDeviceDeliveredFirstStillCounts() {
        // The phone pushed the watch the reading a few seconds before the
        // watch's own session got it: the watch read it too.
        val reading = now - 7_000L
        assertTrue(isNew(reading, lastDirectMs = 0L, lastSyncedMs = reading))
    }

    @Test
    fun aOneMinuteSensorsEveryReadingIsNew() {
        assertTrue(isNew(now, now - minute))
        assertTrue(DirectReadingStatus.SAME_READING_MS < minute)
        assertTrue(DirectReadingStatus.LATE_READING_MS < 4 * minute)
    }

    @Test
    fun theFirstDirectReadingEverIsNew() {
        assertTrue(isNew(now, 0L))
        assertFalse(isNew(0L, 0L))
    }

    // ---------------------------------------- the phone's push while both read

    private val reading = now - 4_000L

    private fun phonePushes(watch: DirectReadingFact, newestMs: Long = reading) =
        DirectReadingStatus.phonePushesWhileBothRead(watch, newestMs)

    @Test
    fun aWatchThatIsNotReadingTheSensorIsPushedEveryReading() {
        // 2026-10-11: the watch held the G7 but had lost its bond, and got each
        // value only from its loss alarm, 320 s late.
        assertTrue(phonePushes(DirectReadingFact.at(now - 60 * minute)))
        assertTrue(phonePushes(never))
        assertTrue(phonePushes(unknown))
    }

    @Test
    fun aWatchWhoseWordOnTheSameReadingIsNotInYetIsPushed() {
        // Its last report is of the reading before: its session may have ended
        // after the phone's. The watch takes only a slot it lacks.
        assertTrue(phonePushes(DirectReadingFact.at(reading - 5 * minute)))
    }

    @Test
    fun aWatchThatSaidItReadTheSameReadingIsNotPushed() {
        // On this device's clock from an age: a second or two either side.
        assertFalse(phonePushes(DirectReadingFact.at(reading + 1_500L)))
        assertFalse(phonePushes(DirectReadingFact.at(reading - 2_000L)))
        // Or a later one: this push carries nothing it lacks.
        assertFalse(phonePushes(DirectReadingFact.at(reading + 5 * minute)))
    }

    @Test
    fun theSameReadingIsHalfAMinuteEitherWay() {
        val same = DirectReadingStatus.SAME_READING_MS
        assertFalse(phonePushes(DirectReadingFact.at(reading - same)))
        assertTrue(phonePushes(DirectReadingFact.at(reading - same - 1L)))
        // Well inside a G7's five minutes, so the reading before never passes for it.
        assertTrue(same < 5 * minute / 2)
    }

    @Test
    fun nothingToPushIsNotPushed() {
        assertFalse(phonePushes(unknown, newestMs = 0L))
        assertFalse(phonePushes(never, newestMs = 0L))
    }

    @Test
    fun theDriverHandsOverTheReadingTimeNotTheArrivalTime() {
        val source = File(repoRoot(), "Common/src/main/java/tk/glucodata/SuperGattCallback.java").readText()
        assertTrue(source.contains("SensorOwnershipRuntime.noteDirectReading(SerialNumber, sampleTimeMs);"))
        assertFalse(source.contains("noteDirectReading(SerialNumber, System.currentTimeMillis())"))
        // And what the other device fills in is remembered for the check above.
        val sync = File(repoRoot(), "Common/src/main/java/tk/glucodata/WearSync2.kt").readText()
        assertTrue(sync.contains("SensorOwnershipRuntime.noteSyncedReading(serial,"))
    }

    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            if (File(dir, "Common/src/main/cpp/g.cpp").isFile) return dir
            dir = dir.parentFile
        }
        throw AssertionError("repo root not found")
    }
}
