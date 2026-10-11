package tk.glucodata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.DisplayedReading.Freshness
import tk.glucodata.DisplayedReading.Origin
import tk.glucodata.DisplayedReading.Reason
import tk.glucodata.logic.TrendEngine
import tk.glucodata.ui.DashboardCurrentReading

/**
 * Every phone surface draws the hub's one reading: the same revision, text, arrow and
 * freshness, changed in the same publish. Each surface is represented by what it renders
 * through: the ongoing notification by its look ([NotificationRefreshPolicy.ongoingLook]),
 * which also decides whether the status-bar icon spells out the value; the always-on
 * display and the floating value by [DisplayedReading.shownText], [DisplayedReading.shownRate]
 * and [DisplayedReading.isDimmed]; an alarm's banner and screen by
 * [DisplayedReading.alarmRate]; the dashboard hero by [DashboardCurrentReading].
 */
class PhoneCurrentReadingTests {
    private val minute = 60_000L
    private val t0 = 1_700_000_000_000L
    private val sensor = "G7-SENSOR"

    /** What one surface showed for one publish. */
    private data class Shown(val revision: Long, val text: String, val rate: Float, val freshness: String)

    private class FakeScheduler : ReadingScheduler {
        var at = 0L
        var wake: Runnable? = null
        override fun schedule(atMillis: Long, wake: Runnable) {
            at = atMillis
            this.wake = wake
        }

        override fun cancel() {
            at = 0L
            wake = null
        }
    }

    private inner class Phone(declaredIntervalMs: Long) {
        var now = t0
        var reading: ReadingHub.Reading? = scripted(t0, "123", 1.4f)
        val scheduler = FakeScheduler()
        val surfaces = linkedMapOf<String, MutableList<Shown>>()
        val hub = ReadingHub(
            source = { ReadingHub.Inputs(sensor, reading, emptyList(), declaredIntervalMs) },
            worker = { it.run() },
            clock = ReadingClock(scheduler),
            now = { now },
        )

        private val renders = linkedMapOf<String, (DisplayedReading) -> Shown>()

        init {
            surface("notification") {
                val look = NotificationRefreshPolicy.ongoingLook(true, it.freshness, it.showsValue, it.timeMillis)
                when (look) {
                    NotificationRefreshPolicy.OngoingLook.READING -> Shown(it.revision, it.valueText, it.shownRate, "FRESH")
                    // makeStaleReadingNotification: the value kept, no arrow.
                    NotificationRefreshPolicy.OngoingLook.STALE_READING ->
                        Shown(it.revision, it.shownLanesText, Float.NaN, "STALE")
                    else -> Shown(it.revision, DisplayedReading.NO_VALUE_TEXT, Float.NaN, "NO_VALUE")
                }
            }
            surface("statusIcon") {
                // Only the reading look spells the value out; any other shows the no-value icon.
                val look = NotificationRefreshPolicy.ongoingLook(true, it.freshness, it.showsValue, it.timeMillis)
                val spelled = look == NotificationRefreshPolicy.OngoingLook.READING
                Shown(
                    it.revision,
                    if (it.showsValue) it.valueText else DisplayedReading.NO_VALUE_TEXT,
                    if (spelled) it.shownRate else Float.NaN,
                    when {
                        spelled -> "FRESH"
                        it.showsValue -> "STALE"
                        else -> "NO_VALUE"
                    },
                )
            }
            surface("aod") { Shown(it.revision, it.shownText, it.shownRate, look(it)) }
            surface("floating") { Shown(it.revision, it.shownText, it.shownRate, look(it)) }
            // An alarm on this reading with no rate of its own: the banner's and the screen's arrow.
            surface("alarm") { Shown(it.revision, it.shownText, it.alarmRate(Float.NaN), look(it)) }
            surface("hero") {
                val history = TrendEngine.TrendResult(TrendEngine.TrendState.Flat, 0.1f, 0f, 1f, 0.2f)
                val state = DashboardCurrentReading.dataState(it, true, now)
                val trend = DashboardCurrentReading.trend(it, history)
                Shown(
                    it.revision,
                    if (state.isFresh) it.valueText else DisplayedReading.NO_VALUE_TEXT,
                    if (it.showsArrow) trend.velocity else Float.NaN,
                    when {
                        state.isFresh && it.isDimmed -> "STALE"
                        state.isFresh -> "FRESH"
                        else -> "NO_VALUE"
                    },
                )
            }
            // One listener for all, as PhoneSurfaces is, so the first publish reaches each.
            hub.addListener { published ->
                renders.forEach { (name, render) -> surfaces.getValue(name) += render(published) }
            }
        }

        private fun look(reading: DisplayedReading): String = when {
            reading.showsValue && reading.isDimmed -> "STALE"
            reading.showsValue -> "FRESH"
            else -> "NO_VALUE"
        }

        private fun surface(name: String, render: (DisplayedReading) -> Shown) {
            surfaces[name] = ArrayList()
            renders[name] = render
        }

        fun advanceTo(atMillis: Long) {
            now = atMillis
            val wake = scheduler.wake
            if (wake != null && scheduler.at <= atMillis) {
                scheduler.wake = null
                wake.run()
            }
        }

        /** What every surface showed last; asserts they all agree. */
        fun agreed(): Shown {
            val last = surfaces.mapValues { it.value.last() }
            val first = last.values.first()
            last.forEach { (name, shown) -> assertEquals("$name drew something else", first, shown) }
            return first
        }

        fun publishes(): Int = surfaces.values.first().size
    }

    private fun scripted(time: Long, text: String, rate: Float) = ReadingHub.Reading(
        sensorId = sensor,
        timeMillis = time,
        value = text.toFloat(),
        valueText = text,
        secondaryText = null,
        tertiaryText = null,
        isMmol = false,
        viewMode = 0,
        rate = rate,
        index = 0,
    )

    @Test
    fun oneReadingReachesEverySurfaceIdentically() {
        val phone = Phone(declaredIntervalMs = 5 * minute)
        assertEquals(Shown(1L, "123", 1.4f, "FRESH"), phone.agreed())

        phone.reading = scripted(t0 + 5 * minute, "131", 2.4f)
        phone.advanceTo(t0 + 5 * minute + 3_000L)
        phone.hub.onReadingAccepted(sensor, t0 + 5 * minute, Origin.LOCAL)
        assertEquals(Shown(2L, "131", 2.4f, "FRESH"), phone.agreed())
        assertEquals(Reason.NEW_READING, phone.hub.current().reason)
        phone.surfaces.values.forEach { assertEquals(2, it.size) }
    }

    @Test
    fun theStaleDeadlineReachesEverySurfaceInOnePublish() {
        val phone = Phone(declaredIntervalMs = 5 * minute)
        // No reading comes: no event at all, only the clock.
        phone.advanceTo(t0 + 5 * minute + 19_999L)
        assertEquals(1, phone.publishes())
        phone.advanceTo(t0 + 5 * minute + 20_000L)
        assertEquals(2, phone.publishes())
        // The value kept, dimmed, without its arrow, everywhere at once.
        assertEquals(Shown(2L, "123", Float.NaN, "STALE"), phone.agreed())
        assertEquals(Freshness.STALE, phone.hub.current().freshness)
        assertEquals(Reason.STALE_TICK, phone.hub.current().reason)
        // The next wake takes the value down.
        assertEquals(t0 + 30 * minute, phone.scheduler.at)
    }

    @Test
    fun thirtyMinutesOnEverySurfaceShowsNoValue() {
        val phone = Phone(declaredIntervalMs = 5 * minute)
        phone.advanceTo(t0 + 5 * minute + 20_000L)
        phone.advanceTo(t0 + 30 * minute)
        assertEquals(3, phone.publishes())
        assertEquals(Shown(3L, DisplayedReading.NO_VALUE_TEXT, Float.NaN, "NO_VALUE"), phone.agreed())
        assertEquals(
            NotificationRefreshPolicy.OngoingLook.NO_VALUE,
            NotificationRefreshPolicy.ongoingLook(
                true,
                phone.hub.current().freshness,
                phone.hub.current().showsValue,
                phone.hub.current().timeMillis,
            ),
        )
        // Nothing changes by itself any more: the clock has nothing to wake for.
        assertEquals(null, phone.scheduler.wake)
    }

    @Test
    fun aOneMinuteSensorGoesStaleEverywhereAfterOneMinuteTwenty() {
        val libre = Phone(declaredIntervalMs = minute)
        libre.advanceTo(t0 + 80_000L)
        assertEquals(Shown(2L, "123", Float.NaN, "STALE"), libre.agreed())
    }

    @Test
    fun anAlarmDrawsTheCurrentReadingsArrowOnItsBannerAndScreen() {
        val phone = Phone(declaredIntervalMs = 5 * minute)
        val fresh = phone.hub.current()
        // The arrow every surface draws, not the rate the alert engine fired with.
        assertEquals(1.4f, fresh.alarmRate(-3f), 0f)
        // A fresh reading without a trend yet keeps the fired rate.
        assertEquals(-3f, fresh.copy(rate = Float.NaN, arrowDegrees = Float.NaN).alarmRate(-3f), 0f)
        phone.advanceTo(t0 + 6 * minute)
        assertTrue("no arrow on a stale reading", phone.hub.current().alarmRate(-3f).isNaN())
    }

    @Test
    fun anAlarmFiredBeforeItsReadingIsInPlaceDrawsThatReadingsArrow() {
        // dowithglucose hands reading N to the alert engine before the live slot and the
        // stored row hold it: the current reading is still N-1, fresh, rising.
        val phone = Phone(declaredIntervalMs = 5 * minute)
        val previous = phone.hub.current()
        assertEquals(1.4f, previous.shownRate, 0f)
        val n = t0 + 5 * minute
        phone.now = n + 2_000L
        assertEquals("N's arrow, not N-1's", -2.1f, previous.alarmRate(n, -3f) { -2.1f }, 0f)
        assertEquals("N without a trend yet: the fired rate", -3f, previous.alarmRate(n, -3f) { Float.NaN }, 0f)
        // N-1 already stale when N comes: still N's arrow, not none.
        phone.advanceTo(n + 20_000L)
        assertEquals(Freshness.STALE, phone.hub.current().freshness)
        assertEquals(-2.1f, phone.hub.current().alarmRate(n + 30_000L, -3f) { -2.1f }, 0f)
        // Once N is in place, the arrow every surface draws, whatever the engine resolved.
        phone.reading = scripted(n, "131", -1.8f)
        phone.hub.onReadingAccepted(sensor, n, Origin.LOCAL)
        assertEquals(-1.8f, phone.hub.current().alarmRate(n, -3f) { error("not needed") }, 0f)
        assertEquals(-1.8f, phone.hub.current().alarmRate(n + 1_000L, -3f) { error("same reading") }, 0f)
    }

    @Test
    fun theAlarmArrowAsksForTheReadingTheAlertsWereEvaluatedOn() {
        val root = generateSequence(java.io.File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .first { java.io.File(it, "Common/src/main/java/tk/glucodata/Notify.java").exists() }
        val notify = java.io.File(root, "Common/src/main/java/tk/glucodata/Notify.java").readText().replace("\r\n", "\n")
        val start = notify.indexOf("static float alarmArrowRate(notGlucose glucose) {")
        assertTrue(start >= 0)
        val body = notify.substring(start, notify.indexOf("\n    }\n", start))
        assertTrue(body.contains("alerts.evaluatedReading(), alerts.evaluatedReadingTimeMs()"))
        assertTrue(body.contains("CurrentReadingHub.alarmRate("))
    }

    @Test
    fun theHeroAgesItsNoDataLabelWithoutChangingFreshness() {
        val phone = Phone(declaredIntervalMs = 5 * minute)
        phone.advanceTo(t0 + 30 * minute)
        val gone = phone.hub.current()
        val at31 = DashboardCurrentReading.dataState(gone, true, t0 + 31 * minute)
        val at46 = DashboardCurrentReading.dataState(gone, true, t0 + 46 * minute)
        assertEquals(at31.kind, at46.kind)
        assertEquals(15 * minute, at46.ageMillis - at31.ageMillis)
    }

    @Test
    fun theHeroShowsOnlyItsOwnSensorsReading() {
        val phone = Phone(declaredIntervalMs = 5 * minute)
        val shown = phone.hub.current()
        val same = { a: String?, b: String? -> a == b }
        assertEquals(shown, DashboardCurrentReading.forSensor(shown, sensor, same))
        assertEquals(shown, DashboardCurrentReading.forSensor(shown, "", same))
        assertEquals(null, DashboardCurrentReading.forSensor(shown, "OTHER", same))
        assertEquals(null, DashboardCurrentReading.forSensor(DisplayedReading.NONE, sensor, same))
    }
}
