package tk.glucodata.alerts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.alerts.WatchAlarmReadiness.Charging
import tk.glucodata.alerts.WatchAlarmReadiness.FRESH_MS
import tk.glucodata.alerts.WatchAlarmReadiness.Report
import tk.glucodata.alerts.WatchAlarmReadiness.Status

/**
 * "Watch when connected" leaves an alarm to the watch only on a fresh report from it:
 * discovery finding the watch app is not enough, and whatever cannot be told rings the
 * phone.
 */
class WatchAlarmReadinessTests {

    private val minute = 60_000L
    private val now = 1_700_000_000_000L

    /** The phone's answer for one alarm, as [AlarmRouting.ringsHere] takes it. */
    private fun phoneRings(discovered: Boolean?, status: Status?, charging: Charging?, at: Long = now): Boolean =
        AlarmRouting.shouldRing(
            onWatch = false,
            AlarmRoutingMode.WATCH_WHEN_CONNECTED,
            WatchAlarmReadiness.reachable(discovered, status, at),
            WatchAlarmReadiness.charging(status, charging, at),
        )

    private fun reportAt(at: Long, readingAt: Long = at, charging: Boolean? = false) =
        Status(at, readingAt) to Charging(charging, at)

    // ------------------------------------------------------------ freshness

    @Test
    fun aFreshReportOffItsChargerSilencesThePhone() {
        val (status, charging) = reportAt(now - 2 * minute)
        assertFalse(phoneRings(discovered = true, status, charging))
    }

    @Test
    fun theWatchAppListedButSilentRingsThePhone() {
        // Discovery lists an installed app that is not running; no report, no hold.
        assertTrue(phoneRings(discovered = true, status = null, charging = null))
        val (status, charging) = reportAt(now - FRESH_MS - 1)
        assertTrue("one report too old", phoneRings(discovered = true, status, charging))
        assertFalse("still fresh at the limit", phoneRings(true, status, charging, at = now - 1))
    }

    @Test
    fun twoReadingIntervalsWithoutAReportAreTheLimit() {
        val (status, charging) = reportAt(now)
        assertFalse(phoneRings(true, status, charging, at = now + 10 * minute))
        assertFalse(phoneRings(true, status, charging, at = now + FRESH_MS))
        assertTrue(phoneRings(true, status, charging, at = now + FRESH_MS + 1))
    }

    @Test
    fun aReportAboutAnOldReadingDoesNotCount() {
        // The watch is up but its readings (own or mirrored) stopped: it has nothing to ring on.
        val (status, charging) = reportAt(now - minute, readingAt = now - FRESH_MS - 1)
        assertTrue(phoneRings(true, status, charging))
        val (recent, recentCharging) = reportAt(now - minute, readingAt = now - 6 * minute)
        assertFalse(phoneRings(true, recent, recentCharging))
    }

    @Test
    fun aReportFromTheFutureDoesNotCount() {
        // The phone's clock moved back since it arrived.
        val (status, charging) = reportAt(now + minute)
        assertTrue(phoneRings(true, status, charging))
    }

    @Test
    fun discoveryStillDecidesFirst() {
        val (status, charging) = reportAt(now)
        assertTrue("out of reach", phoneRings(discovered = false, status, charging))
        assertTrue("cannot tell", phoneRings(discovered = null, status, charging))
        assertEquals(false, WatchAlarmReadiness.reachable(false, status, now))
        assertNull(WatchAlarmReadiness.reachable(null, status, now))
    }

    // ------------------------------------------------------------ charging

    @Test
    fun onItsChargerOrUnknownRingsThePhone() {
        val status = Status(now, now)
        assertTrue(phoneRings(true, status, Charging(true, now)))
        assertTrue("the report did not say", phoneRings(true, status, Charging(null, now)))
        assertTrue("never heard", phoneRings(true, status, null))
    }

    @Test
    fun theChargerStateMayNotBeOlderThanTheReport() {
        val status = Status(now - minute, now - minute)
        assertNull(WatchAlarmReadiness.charging(status, Charging(false, now - 2 * minute), now))
        assertEquals(false, WatchAlarmReadiness.charging(status, Charging(false, now - minute), now))
        // A sensor-ownership report after it says the watch went on its charger.
        assertEquals(true, WatchAlarmReadiness.charging(status, Charging(true, now - 30_000L), now))
        assertNull("no fresh report", WatchAlarmReadiness.charging(Status(now - FRESH_MS - 1, now), Charging(false, now), now))
    }

    // ------------------------------------------------------------ wire

    @Test
    fun theReportRoundTrips() {
        for (charging in listOf(true, false, null)) {
            val report = Report(now - 3_000L, charging)
            assertEquals(report, WatchAlarmReadiness.decode(WatchAlarmReadiness.encode(report)))
        }
    }

    @Test
    fun aLongerReportFromALaterBuildStillReads() {
        val report = Report(now, true)
        val longer = WatchAlarmReadiness.encode(report) + byteArrayOf(7, 7, 7)
        assertEquals(report, WatchAlarmReadiness.decode(longer))
    }

    @Test
    fun anUnreadableReportIsDropped() {
        val good = WatchAlarmReadiness.encode(Report(now, false))
        assertNull(WatchAlarmReadiness.decode(null))
        assertNull(WatchAlarmReadiness.decode(ByteArray(0)))
        assertNull(WatchAlarmReadiness.decode(good.copyOf(good.size - 1)))
        assertNull(WatchAlarmReadiness.decode(good.copyOf().also { it[0] = 2 }))
    }

    // ------------------------------------------------------------ the watch's reports

    private class Watch {
        val reporter = WatchStatusReporter()
        var charging: Boolean? = false
        var chargingReads = 0

        fun evaluated(readingMs: Long, at: Long): Report? =
            reporter.onEvaluated(readingMs, at) { chargingReads++; charging }
    }

    @Test
    fun eachFiveMinuteReadingIsReported() {
        val watch = Watch()
        (0..3).forEach { i ->
            val reading = now + i * 5 * minute
            assertEquals(Report(reading, false), watch.evaluated(reading, reading + 2_000L))
        }
    }

    @Test
    fun theFifteenSecondChecksSayNothingNew() {
        val watch = Watch()
        assertNotNull(watch.evaluated(now, now))
        (1..20).forEach { assertNull(watch.evaluated(now, now + it * 15_000L)) }
        // The charger is read once a minute between readings, not at every check.
        assertEquals(1 + 5, watch.chargingReads)
    }

    @Test
    fun oneMinuteReadingsAreReportedEveryFewMinutes() {
        val watch = Watch()
        val sent = (0..12).mapNotNull { i -> watch.evaluated(now + i * minute, now + i * minute) }
        assertEquals(listOf(now, now + 4 * minute, now + 8 * minute, now + 12 * minute), sent.map { it.lastReadingMs })
    }

    @Test
    fun aChargerChangeIsReportedAtOnce() {
        val watch = Watch()
        watch.evaluated(now, now)
        watch.charging = true
        assertEquals(Report(now, true), watch.evaluated(now, now + minute))
        assertNull(watch.evaluated(now, now + 2 * minute))
        watch.charging = false
        assertEquals(Report(now + 3 * minute, false), watch.evaluated(now + 3 * minute, now + 3 * minute))
    }

    @Test
    fun anOwedReportGoesAtTheNextCall() {
        val watch = Watch()
        watch.evaluated(now, now)
        watch.reporter.owe()
        assertEquals(Report(now, false), watch.evaluated(0L, now + 5_000L))
        assertNull(watch.evaluated(now, now + 10_000L))
    }

    @Test
    fun nothingIsReportedBeforeAReading() {
        val watch = Watch()
        assertNull(watch.evaluated(0L, now))
        assertEquals(0, watch.chargingReads)
    }
}
