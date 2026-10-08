package tk.glucodata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import tk.glucodata.logic.TrendEngine
import tk.glucodata.ui.DisplayValues

/**
 * Every trend-arrow consumer must regress over the SAME point list. Historically
 * each surface assembled its own: the dashboard hero skipped the live
 * augmentation, and the notification paths cut their Room load at the wall
 * clock while TrendEngine windows from the newest point — so whenever the newest
 * reading lagged "now" (always, by up to a full period), the lists differed by
 * one tail point. Invisible everywhere except the ±0.5 mg/dl/min flat dead zone,
 * where the nuance flipped one glyph but not the other for a full period
 * (observed live: dashboard ↗ vs notification → on an identical 134).
 *
 * DisplayTrendSource.resolveTrendPoints is the one canonical resolution now;
 * these tests pin element-wise identity (timestamps AND values, per the
 * acceptance criteria) and identical glyph decisions at the dead-zone edge.
 */
class TrendConsumerPointListTests {

    @Before
    fun registerProductionTrendProvider() {
        // Specific.start() performs this registration in the app. Local JVM
        // tests do not run application startup, so reproduce that wiring here
        // instead of exercising TrendAccess's deliberately degraded fallback.
        TrendAccess.register(TrendVelocityProvider { points, useRaw, isMmol ->
            TrendEngine.calculateTrend(points, useRaw, isMmol).velocity
        })
    }

    private val minute = 60_000L
    private val now = 1_700_000_000_000L
    private val serial = "sensor-1"

    /** Newest reading is 45 s old — the wall-clock lag the old cut tripped over. */
    private val newestTs = now - 45_000L

    /**
     * 21 Room rows, 1-min apart. The interior 20 rise at exactly 0.48 mg/dl/min;
     * the oldest row (exactly 20 min before the newest) dips below the line, so
     * including or dropping it moves the regression across the 0.5 dead-zone
     * edge — the construction that reproduced the live glyph split pre-fix.
     */
    private fun roomBase(): List<GlucosePoint> {
        val points = ArrayList<GlucosePoint>()
        points.add(GlucosePoint(newestTs - 20 * minute, 116f, 0f))
        for (k in 19 downTo 0) {
            points.add(GlucosePoint(newestTs - k * minute, 133.1f - 0.48f * k, 0f))
        }
        return points
    }

    private fun snapshot(ts: Long, value: Float, isMmol: Boolean = false) = CurrentDisplaySource.Snapshot(
        timeMillis = ts,
        rate = Float.NaN,
        sensorId = serial,
        sensorGen = 0,
        index = 0,
        viewMode = 0,
        source = "test",
        autoValue = value,
        rawValue = 0f,
        sharedDisplayValue = 0f,
        sharedMgdl = 0,
        isMmol = isMmol,
        displayValues = DisplayValues(primaryValue = value, primaryStr = "", fullFormatted = "")
    )

    // ---- The four consumers' trend lists, as production assembles them post-fix ----

    /** Dashboard hero: full Room window + snapshot (DashboardComponents.kt). */
    private fun dashboardList(base: List<GlucosePoint>, snap: CurrentDisplaySource.Snapshot?) =
        DisplayTrendSource.resolveTrendPoints(base, snap, null)

    /** Main notification: 3-h chart rows + snapshot (Notify.makearrownotification). */
    private fun notificationList(base: List<GlucosePoint>, snap: CurrentDisplaySource.Snapshot?) =
        DisplayTrendSource.resolveTrendPoints(base, snap, serial)

    /** Alarm notification: rows since now-2×window + snapshot. */
    private fun alarmList(base: List<GlucosePoint>, snap: CurrentDisplaySource.Snapshot?): List<GlucosePoint> {
        val startT = now - 2 * DisplayTrendSource.TREND_WINDOW_MS
        return DisplayTrendSource.resolveTrendPoints(base.filter { it.timestamp >= startT }, snap, serial)
    }

    // ---- The watch's arrows, through DisplayTrendSource.resolveDisplayArrowRate ----

    /** The rows DisplayTrendSource.loadDisplayArrowRate reads: from 2×window before the snapshot. */
    private fun watchSnapshotRows(base: List<GlucosePoint>, snap: CurrentDisplaySource.Snapshot) =
        base.filter { it.timestamp >= snap.timeMillis - 2 * DisplayTrendSource.TREND_WINDOW_MS }

    /** Watch complications, the watch face and the alarm screen (GlucoseComplicationData.displayRate). */
    private fun complicationVelocity(base: List<GlucosePoint>, snap: CurrentDisplaySource.Snapshot) =
        DisplayTrendSource.resolveDisplayArrowRate(watchSnapshotRows(base, snap), snap, serial, 0, false)

    /** Watch main screen's hero and newest row: the store's whole horizon (WearGlucoseStore.trendRate). */
    private fun watchMainVelocity(base: List<GlucosePoint>, snap: CurrentDisplaySource.Snapshot) =
        DisplayTrendSource.resolveDisplayArrowRate(base, snap, serial, 0, false)

    private fun dashboardVelocity(base: List<GlucosePoint>, snap: CurrentDisplaySource.Snapshot) =
        TrendEngine.calculateTrend(dashboardList(base, snap), useRaw = false, isMmol = false).velocity

    private fun deadZoneSide(v: Float) = when {
        v > 0.5f -> 1
        v < -0.5f -> -1
        else -> 0
    }

    /** A G7: one reading every 5 minutes, oldest first, the newest at [newestTs]; [valueAt] by age in readings. */
    private fun g7Rows(count: Int, valueAt: (Int) -> Float): List<GlucosePoint> =
        (count - 1 downTo 0).map { k -> GlucosePoint(newestTs - k * 5 * minute, valueAt(k), 0f) }

    /** GlucosePoint has no value equals — compare the fields the regression reads. */
    private fun triples(points: List<GlucosePoint>) =
        points.map { Triple(it.timestamp, it.value, it.rawValue) }

    // ---- Acceptance 1: element-wise list identity, timestamps AND values ----

    @Test
    fun allConsumersResolveTheIdenticalPointList() {
        val base = roomBase()
        val snap = snapshot(newestTs, 133.1f)

        val dashboard = triples(dashboardList(base, snap))
        val notification = triples(notificationList(base, snap))
        val alarm = triples(alarmList(base, snap))
        val watch = triples(DisplayTrendSource.resolveTrendPoints(watchSnapshotRows(base, snap), snap, serial))

        assertEquals(dashboard, notification)
        assertEquals(dashboard, alarm)
        assertEquals(dashboard, watch)
        // The tail row exactly one window before the newest point stays in for everyone.
        assertEquals(21, dashboard.size)
        assertEquals(newestTs - 20 * minute, dashboard.first().first)
    }

    @Test
    fun listsStayIdenticalWhenRoomLagsTheLiveReading() {
        // The newest reading exists only as the live snapshot, not yet persisted.
        val base = roomBase().dropLast(1)
        val snap = snapshot(newestTs, 133.1f)

        val dashboard = triples(dashboardList(base, snap))
        val notification = triples(notificationList(base, snap))
        val alarm = triples(alarmList(base, snap))
        val watch = triples(DisplayTrendSource.resolveTrendPoints(watchSnapshotRows(base, snap), snap, serial))

        assertEquals(dashboard, notification)
        assertEquals(dashboard, alarm)
        assertEquals(dashboard, watch)
        // The live point is appended for every consumer, dashboard included.
        assertEquals(newestTs, dashboard.last().first)
        assertEquals(133.1f, dashboard.last().second)
    }

    // ---- Acceptance 2: identical glyph decision at the dead-zone edge ----

    @Test
    fun glyphDecisionIsIdenticalAtTheFlatDeadZoneEdge() {
        val base = roomBase()
        val snap = snapshot(newestTs, 133.1f)

        // Dashboard hero (DashboardComponents.kt): canonical list into the engine.
        val dashboardResult = TrendEngine.calculateTrend(dashboardList(base, snap), useRaw = false, isMmol = false)

        // Main notification and alarm notification (Notify.java).
        val notificationVelocity =
            DisplayTrendSource.resolveArrowRate(notificationList(base, snap), snap, 0, false, Float.NaN)
        val alarmVelocity =
            DisplayTrendSource.resolveArrowRate(alarmList(base, snap), snap, 0, false, Float.NaN)

        // Broadcast (BroadcastTrendRate.java): the same canonical list; the snapshot is
        // deliberately not handed to resolveArrowRate so a too-thin history falls back
        // to the caller's native rate instead of the snapshot's own.
        val broadcastVelocity =
            DisplayTrendSource.resolveArrowRate(alarmList(base, snap), null, 0, false, Float.NaN)

        // The watch's complications and main screen.
        val complicationVelocity = complicationVelocity(base, snap)
        val watchMainVelocity = watchMainVelocity(base, snap)

        assertEquals(dashboardResult.velocity, notificationVelocity, 1e-6f)
        assertEquals(dashboardResult.velocity, alarmVelocity, 1e-6f)
        assertEquals(dashboardResult.velocity, broadcastVelocity, 1e-6f)
        assertEquals(dashboardResult.velocity, complicationVelocity, 1e-6f)
        assertEquals(dashboardResult.velocity, watchMainVelocity, 1e-6f)

        // Identical velocity means one glyph decision: every consumer sits on the
        // same side of the ±0.5 dead zone, whatever the estimator reads for the
        // fixture. Pre-fix, the one-tail-point list difference put the dashboard
        // and the notification on opposite sides here.
        assertEquals(deadZoneSide(dashboardResult.velocity), deadZoneSide(notificationVelocity))
        assertEquals(deadZoneSide(dashboardResult.velocity), deadZoneSide(alarmVelocity))
        assertEquals(deadZoneSide(dashboardResult.velocity), deadZoneSide(broadcastVelocity))
        assertEquals(deadZoneSide(dashboardResult.velocity), deadZoneSide(complicationVelocity))
        assertEquals(deadZoneSide(dashboardResult.velocity), deadZoneSide(watchMainVelocity))
    }

    @Test
    fun subDeadZoneDriftReadsFlatOnEveryConsumer() {
        // Interior slope 0.48 with no tail dip: inside the dead zone for everyone.
        val base = roomBase().drop(1)
        val snap = snapshot(newestTs, 133.1f)

        val dashboardResult = TrendEngine.calculateTrend(dashboardList(base, snap), useRaw = false, isMmol = false)
        val notificationVelocity =
            DisplayTrendSource.resolveArrowRate(notificationList(base, snap), snap, 0, false, Float.NaN)

        assertEquals(dashboardResult.velocity, notificationVelocity, 1e-6f)
        assertEquals(dashboardResult.velocity, complicationVelocity(base, snap), 1e-6f)
        assertEquals(dashboardResult.velocity, watchMainVelocity(base, snap), 1e-6f)
        assertEquals(TrendEngine.TrendState.Flat, dashboardResult.state)
        assertTrue("must stay inside the dead zone", kotlin.math.abs(notificationVelocity) <= 0.5f)
    }

    // ---- The watch draws the dashboard's arrow ----
    //
    // The watch used to draw two other ones: its complications took the snapshot's
    // own rate (the alert engine's, over the locally smoothed series, which
    // "collapse into chunks" leaves without the newest reading), and its main
    // screen regressed over the calibrated, smoothed chart series with no live
    // reading. Observed on a G7: the phone ↗, the watch face →.

    @Test
    fun watchArrowsTiltWithTheDashboardJustPastTheFlatBand() {
        // A steady 0.55 mg/dL/min rise: past the flat band by 0.05, a 4.5° tilt
        // that any estimate reading a little lower draws flat.
        val base = g7Rows(7) { k -> 140f - 2.75f * k }
        val snap = snapshot(newestTs, 140f)

        val dashboard = dashboardVelocity(base, snap)
        val complication = complicationVelocity(base, snap)
        val watchMain = watchMainVelocity(base, snap)

        assertEquals(0.55f, dashboard, 1e-4f)
        assertEquals(dashboard, complication, 1e-6f)
        assertEquals(dashboard, watchMain, 1e-6f)
        val tilt = TrendArrowAngle.rotationDegrees(dashboard)
        assertTrue("slightly up, not flat: $tilt", tilt < 0f)
        assertEquals(tilt, TrendArrowAngle.rotationDegrees(complication), 0f)
        assertEquals(tilt, TrendArrowAngle.rotationDegrees(watchMain), 0f)
    }

    @Test
    fun watchArrowsKeepTheNewestReadingTheHistoryLacks() {
        // The stored readings drift up at 0.4 mg/dL/min, inside the flat band. The
        // newest, 4 mg/dL above that line, is in the live reading only: storage has
        // not caught up yet (a collapsed smoothed series lacks it the same way). With
        // it the trend is just past the band; without it, flat.
        val stored = g7Rows(7) { k -> 120f - 2f * k }.dropLast(1)
        val snap = snapshot(newestTs, 124f)

        val dashboard = dashboardVelocity(stored, snap)
        val complication = complicationVelocity(stored, snap)
        val watchMain = watchMainVelocity(stored, snap)
        val storedOnly = TrendEngine.calculateTrend(stored, useRaw = false, isMmol = false).velocity

        assertEquals(0, deadZoneSide(storedOnly))
        assertEquals(1, deadZoneSide(dashboard))
        assertEquals(dashboard, complication, 1e-6f)
        assertEquals(dashboard, watchMain, 1e-6f)
        assertEquals(TrendArrowAngle.rotationDegrees(dashboard), TrendArrowAngle.rotationDegrees(complication), 0f)
    }

    @Test
    fun watchArrowRisesWithTheDashboardWhenItsRowsKeepWholeSeconds() {
        // As seen on a G7 (mmol/L): 5.4, 5.45, 5.5, 5.6, 5.9, 6.1, the phone's arrow up
        // and the watch's flat. Native rows keep whole seconds, but the live reading the
        // watch takes from the G7 itself keeps the milliseconds it arrived at (now less
        // the reading's age), and with local smoothing on, the snapshot carries the
        // smoothed 6.0 where its own stored row says 6.1.
        val readings = listOf(5.4f, 5.45f, 5.5f, 5.6f, 5.9f, 6.1f)
        val watchRows = g7Rows(readings.size) { k -> readings[readings.lastIndex - k] }
        val liveTs = newestTs + 417L
        val snap = snapshot(liveTs, 6.0f, isMmol = true)
        // The phone's Room rows keep each live reading's own time.
        val phoneRows = watchRows.map { GlucosePoint(it.timestamp + 417L, it.value, it.rawValue) }

        val dashboard = TrendEngine.calculateTrend(
            DisplayTrendSource.resolveTrendPoints(phoneRows, snap, null), useRaw = false, isMmol = true
        )
        val watch = DisplayTrendSource.resolveDisplayArrowRate(watchRows, snap, serial, 0, true)
        // Merged as a reading of its own, the live point sat 417 ms after its stored row
        // and 0.1 mmol/L off it: a > 20 mg/dL/min artifact to TrendEngine, which then
        // measured the newest point alone.
        val asSeparateReading = DisplayTrendSource.resolveArrowRate(
            DisplayTrendSource.resolveTrendPoints(watchRows, snap, serial), snap, 0, true, Float.NaN
        )

        assertEquals(0f, asSeparateReading, 0f)
        assertEquals(1, deadZoneSide(dashboard.velocity))
        assertEquals(dashboard.velocity, watch, 1e-4f)
        assertEquals(TrendArrowAngle.rotationDegrees(dashboard.velocity), TrendArrowAngle.rotationDegrees(watch), 0.01f)
    }
}
