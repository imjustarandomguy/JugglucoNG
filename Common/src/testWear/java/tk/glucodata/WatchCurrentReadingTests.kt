package tk.glucodata

import androidx.wear.watchface.complications.data.ComplicationText
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.TimeDifferenceComplicationText
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.DisplayedReading.Freshness
import tk.glucodata.DisplayedReading.Origin
import tk.glucodata.DisplayedReading.Reason
import tk.glucodata.glucosecomplication.GlucoseComplicationData
import tk.glucodata.glucosecomplication.trendArrowText
import tk.glucodata.ui.alarmGlucose
import tk.glucodata.ui.screens.heroReading

/**
 * Every watch surface draws the hub's one reading: the same revision, text, arrow and
 * freshness, changed in the same publish. Each surface is represented by the function it
 * renders through; the watch face draws [DisplayedReading.shownText],
 * [DisplayedReading.shownRate] and [DisplayedReading.isDimmed] directly.
 */
class WatchCurrentReadingTests {
    private val minute = 60_000L
    private val t0 = 1_700_000_000_000L
    private val sensor = "G7-SENSOR"

    /** What one surface showed for one publish. */
    private data class Shown(val revision: Long, val text: String, val arrow: String, val stale: Boolean)

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

    private inner class Watch(declaredIntervalMs: Long) {
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
            surface("face") { Shown(it.revision, it.shownText, trendArrowText(it.shownRate), it.isDimmed) }
            surface("complication") {
                val complication = GlucoseComplicationData.reading(it)
                Shown(
                    it.revision,
                    GlucoseComplicationData.shownText(complication),
                    trendArrowText(complication?.rate),
                    complication?.stale == true,
                )
            }
            surface("ongoing") {
                Shown(it.revision, WearOngoingActivity.statusText(it), trendArrowText(it.shownRate), it.isDimmed)
            }
            surface("hero") {
                val hero = heroReading(it)!!
                Shown(it.revision, hero.text, trendArrowText(hero.rate), hero.stale && it.showsValue)
            }
            surface("alarm") {
                val alarm = alarmGlucose(it, DisplayedReading.NO_VALUE_TEXT, null, Float.NaN)
                Shown(it.revision, alarm.primary, alarm.trend, it.isDimmed)
            }
            // One listener for all, so the first publish (on registering) reaches each of them.
            hub.addListener { published ->
                renders.forEach { (name, render) -> surfaces.getValue(name) += render(published) }
            }
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
        val watch = Watch(declaredIntervalMs = 5 * minute)
        assertEquals(Shown(1L, "123", "↗", false), watch.agreed())

        watch.reading = scripted(t0 + 5 * minute, "131", 2.4f)
        watch.advanceTo(t0 + 5 * minute + 4_000L)
        watch.hub.onReadingAccepted(sensor, t0 + 5 * minute, Origin.PEER)
        assertEquals(Shown(2L, "131", "↑↑", false), watch.agreed())
        assertEquals(Reason.NEW_READING, watch.hub.current().reason)
        watch.surfaces.values.forEach { assertEquals(2, it.size) }
    }

    @Test
    fun theStaleDeadlineReachesEverySurfaceInOnePublish() {
        val watch = Watch(declaredIntervalMs = 5 * minute)
        // The watch is out of range: no event at all, only the clock.
        watch.advanceTo(t0 + 5 * minute + 19_000L)
        assertEquals(1, watch.publishes())
        watch.advanceTo(t0 + 5 * minute + 20_000L)
        assertEquals(2, watch.publishes())
        assertEquals(Shown(2L, "123", "", true), watch.agreed())
        assertEquals(Freshness.STALE, watch.hub.current().freshness)
        assertEquals(Reason.STALE_TICK, watch.hub.current().reason)
    }

    @Test
    fun thirtyMinutesOnEverySurfaceShowsNoValue() {
        val watch = Watch(declaredIntervalMs = 5 * minute)
        watch.advanceTo(t0 + 5 * minute + 20_000L)
        watch.advanceTo(t0 + 30 * minute)
        assertEquals(3, watch.publishes())
        assertEquals(Shown(3L, DisplayedReading.NO_VALUE_TEXT, "", false), watch.agreed())
        assertNull(GlucoseComplicationData.reading(watch.hub.current()))
    }

    @Test
    fun aOneMinuteSensorGoesStaleAfterOneMinuteTwentyAndAFiveMinuteOneDoesNot() {
        val libre = Watch(declaredIntervalMs = minute)
        val g7 = Watch(declaredIntervalMs = 5 * minute)
        libre.advanceTo(t0 + 80_000L)
        g7.advanceTo(t0 + 80_000L)
        assertTrue(libre.agreed().stale)
        assertEquals(Shown(1L, "123", "↗", false), g7.agreed())
    }

    @Test
    fun theAlarmScreenKeepsTheAlarmsValueWithoutACurrentOne() {
        val gone = DisplayedReading.NONE.copy(freshness = Freshness.NO_VALUE, timeMillis = t0)
        val alarm = alarmGlucose(gone, "54", null, -1.2f)
        assertEquals("54", alarm.primary)
        assertEquals(trendArrowText(-1.2f), alarm.trend)
    }

    // --- A stale value says how old it is where it cannot be dimmed. ---

    private fun surrounding(text: ComplicationText): String =
        text.toWireComplicationText().surroundingText.toString()

    @Test
    fun aStaleReadingsTextFormsShowItsValueAndAge() {
        val watch = Watch(declaredIntervalMs = 5 * minute)
        watch.advanceTo(t0 + 5 * minute + 20_000L)
        val stale = GlucoseComplicationData.reading(watch.hub.current())!!
        assertTrue(stale.stale)
        assertEquals(t0, stale.lastReadingMillis)

        val short = GlucoseComplicationData.shortValueData(stale, "Glucose", tapAction = null).text
        assertTrue(short is TimeDifferenceComplicationText)
        assertEquals("123·^1", surrounding(short))
        // The face counts the age on: the text changes with no new data and no wake.
        assertFalse(short.returnsSameText(Instant.ofEpochMilli(t0 + 6 * minute), Instant.ofEpochMilli(t0 + 9 * minute)))

        val long = GlucoseComplicationData.longTextData(stale, "Glucose", tapAction = null).text
        assertTrue(long is TimeDifferenceComplicationText)
        assertEquals("123 · ^1", surrounding(long))
    }

    @Test
    fun aFreshReadingsTextFormsShowItsValueAloneAndNoValueAtThirtyMinutes() {
        val watch = Watch(declaredIntervalMs = 5 * minute)
        val fresh = GlucoseComplicationData.shortValueData(
            GlucoseComplicationData.reading(watch.hub.current()), "Glucose", tapAction = null,
        ).text
        assertTrue(fresh is PlainComplicationText)
        assertEquals("123", surrounding(fresh))
        watch.advanceTo(t0 + 5 * minute + 20_000L)
        watch.advanceTo(t0 + 30 * minute)
        val gone = GlucoseComplicationData.longTextData(
            GlucoseComplicationData.reading(watch.hub.current()), "Glucose", tapAction = null,
        ).text
        assertTrue(gone is PlainComplicationText)
        assertEquals(DisplayedReading.NO_VALUE_TEXT, surrounding(gone))
    }

    @Test
    fun theOngoingActivitySaysHowOldAStaleValueIs() {
        val watch = Watch(declaredIntervalMs = 5 * minute)
        val ago = { at: Long, now: Long -> "${(now - at) / minute} min. ago" }
        assertEquals("123", WearOngoingActivity.statusText(watch.hub.current(), t0 + minute, ago))
        watch.advanceTo(t0 + 5 * minute + 20_000L)
        assertEquals("123 · 5 min. ago", WearOngoingActivity.statusText(watch.hub.current(), t0 + 5 * minute + 20_000L, ago))
        // Refreshed later (the loss alarm's tick, a data change): the age has moved on.
        assertEquals("123 · 11 min. ago", WearOngoingActivity.statusText(watch.hub.current(), t0 + 11 * minute, ago))
        watch.advanceTo(t0 + 30 * minute)
        assertEquals(DisplayedReading.NO_VALUE_TEXT, WearOngoingActivity.statusText(watch.hub.current(), t0 + 30 * minute, ago))
    }

    // --- The other selected sensors go stale on the same clock. ---

    @Test
    fun aSensorShownBesideGoingStaleRedrawsTheWatchSurfacesAtOnce() {
        var now = t0
        val scheduler = FakeScheduler()
        val published = ArrayList<DisplayedReading>()
        val peer = ReadingHub.Other("PEER", t0 - 3 * minute, listOf(t0 - 8 * minute), 0L)
        val hub = ReadingHub(
            source = { ReadingHub.Inputs(sensor, scripted(t0, "123", 1.4f), emptyList(), 5 * minute, others = listOf(peer)) },
            worker = { it.run() },
            clock = ReadingClock(scheduler),
            now = { now },
        )
        hub.addListener { published += it }
        // The peer's next reading is due first: the one clock wakes for it.
        assertEquals(t0 + 2 * minute + 20_000L, scheduler.at)
        now = scheduler.at
        scheduler.wake!!.run()
        val peerStale = published.last()
        assertEquals(Freshness.FRESH, peerStale.freshness)
        assertEquals(Freshness.STALE, peerStale.otherSensors.single().freshness)
        assertFalse("the complications redraw at once, not folded", WatchSurfaces.isRepeat(peerStale.reason))
        assertEquals(t0 + 5 * minute + 20_000L, scheduler.at)
    }

    @Test
    fun theWatchTellsTheHubWhichSensorsItShowsBeside() {
        val root = generateSequence(java.io.File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .first { java.io.File(it, "Common/src/wear/java/tk/glucodata/WatchSurfaces.kt").exists() }
        val surfaces = java.io.File(root, "Common/src/wear/java/tk/glucodata/WatchSurfaces.kt").readText()
        assertTrue(surfaces.contains("CurrentReadingHub.setOtherSensors { tk.glucodata.ui.WearSensorSelection.selected() }"))
    }
}
