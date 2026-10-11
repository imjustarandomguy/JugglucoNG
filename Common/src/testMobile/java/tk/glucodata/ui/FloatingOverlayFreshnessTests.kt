package tk.glucodata.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.DisplayedReading
import tk.glucodata.DisplayedReading.Freshness
import tk.glucodata.DisplayedReading.Origin
import tk.glucodata.DisplayedReading.Reason
import tk.glucodata.ReadingClock
import tk.glucodata.ReadingHub
import tk.glucodata.ReadingScheduler
import tk.glucodata.ui.overlay.FloatingDetailsCardState
import tk.glucodata.ui.overlay.FloatingNextReading
import tk.glucodata.ui.overlay.FloatingPillLook
import tk.glucodata.ui.overlay.FloatingStaleValue

/**
 * The pill, its details card and its next-reading ring draw the device's one current
 * reading, CurrentReadingHub's: the same revision, value, arrow and freshness, changed in
 * the publish that changed them, with no clock of their own. A scripted hub on a fake
 * clock publishes; each surface is represented by the function it renders through (the
 * composables call into JNI while composing, so they cannot be rendered here).
 */
class FloatingOverlayFreshnessTests {
    private val minute = 60_000L
    private val t0 = 1_790_000_000_000L
    private val sensor = "G7-SENSOR"

    /** What the pill drew for one publish. */
    private data class Pill(val revision: Long, val text: String, val arrowRate: Float, val stale: Boolean)

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

    /** A phone whose hub publishes one scripted reading, and the pill drawn for each publish. */
    private inner class Phone(declaredIntervalMs: Long = 5 * minute) {
        var now = t0
        var reading: ReadingHub.Reading? = scripted(t0, "123", 1.4f)
        val scheduler = FakeScheduler()
        val published = ArrayList<DisplayedReading>()
        val hub = ReadingHub(
            source = { ReadingHub.Inputs(sensor, reading, emptyList(), declaredIntervalMs) },
            worker = { it.run() },
            clock = ReadingClock(scheduler),
            now = { now },
        ).also { hub -> hub.addListener { published += it } }

        fun advanceTo(atMillis: Long) {
            now = atMillis
            val wake = scheduler.wake
            if (wake != null && scheduler.at <= atMillis) {
                scheduler.wake = null
                wake.run()
            }
        }

        val last: DisplayedReading get() = published.last()

        fun pill(): Pill = FloatingPillLook.of(last).let { Pill(it.revision, it.text, it.arrowRate, it.stale) }

        fun card() = FloatingDetailsCardState.of(last, isMmol = false, viewMode = 0, nowMillis = now)

        fun ring() = FloatingNextReading.state(
            last.timeMillis, last.nextExpectedAtMillis, now, late = FloatingPillLook.of(last).stale,
        )
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
    fun thePillDrawsTheHubsReadingAsItIsPublished() {
        val phone = Phone()
        val first = phone.hub.current()
        assertEquals(Pill(first.revision, "123", 1.4f, stale = false), phone.pill())
        assertEquals(first.shownText, phone.pill().text)
        assertEquals(first.shownRate, phone.pill().arrowRate, 0f)
        assertEquals(1f, FloatingPillLook.of(first).valueAlpha, 0f)
        assertFalse(phone.card()!!.stale)

        phone.reading = scripted(t0 + 5 * minute, "131", 2.4f)
        phone.advanceTo(t0 + 5 * minute + 3_000L)
        phone.hub.onReadingAccepted(sensor, t0 + 5 * minute, Origin.LOCAL)
        val second = phone.hub.current()
        assertEquals(Reason.NEW_READING, second.reason)
        assertEquals(Pill(second.revision, "131", 2.4f, stale = false), phone.pill())
        assertEquals(t0 + 5 * minute, phone.card()!!.readingTime)
        assertEquals(2, phone.published.size)
    }

    @Test
    fun theStaleMomentDimsThePillAndDropsItsArrowInOnePublish() {
        val phone = Phone()
        val staleAt = phone.last.staleAtMillis
        assertEquals(t0 + 5 * minute + 20_000L, staleAt)

        // No reading comes: only the hub's clock moves the pill.
        phone.advanceTo(staleAt - 1L)
        assertEquals(1, phone.published.size)
        assertEquals(Pill(1L, "123", 1.4f, stale = false), phone.pill())

        phone.advanceTo(staleAt)
        assertEquals(2, phone.published.size)
        assertEquals(Reason.STALE_TICK, phone.last.reason)
        assertEquals(Freshness.STALE, phone.last.freshness)
        val pill = phone.pill()
        assertEquals("123", pill.text)
        assertTrue(pill.stale)
        assertTrue("no arrow on a stale value", pill.arrowRate.isNaN())
        assertEquals(FloatingStaleValue.DIMMED_ALPHA, FloatingPillLook.of(phone.last).valueAlpha, 0f)
        // The card, drawn from the same publish, dims with it.
        assertTrue(phone.card()!!.stale)
    }

    @Test
    fun theRingTurnsLateAtTheHubsStaleMomentAndNotBefore() {
        val phone = Phone()
        val staleAt = phone.last.staleAtMillis

        // Due, then overdue within the grace: full, not late, and no step left to wake for.
        phone.advanceTo(phone.last.nextExpectedAtMillis)
        assertEquals(FloatingNextReading.State(1f, late = false), phone.ring())
        assertNull(FloatingNextReading.nextStepDelay(phone.last.timeMillis, phone.last.nextExpectedAtMillis, phone.now))
        phone.advanceTo(staleAt - 1L)
        assertEquals(FloatingNextReading.State(1f, late = false), phone.ring())

        // Late in the publish that makes the reading stale.
        phone.advanceTo(staleAt)
        assertEquals(FloatingNextReading.State(1f, late = true), phone.ring())
    }

    @Test
    fun thirtyMinutesAfterTheReadingThePillShowsNoValue() {
        val phone = Phone()
        phone.advanceTo(phone.last.staleAtMillis)
        phone.advanceTo(t0 + 30 * minute - 1L)
        assertEquals(2, phone.published.size)

        phone.advanceTo(t0 + 30 * minute)
        assertEquals(3, phone.published.size)
        assertEquals(Freshness.NO_VALUE, phone.last.freshness)
        val look = FloatingPillLook.of(phone.last)
        assertEquals(DisplayedReading.NO_VALUE_TEXT, look.text)
        assertFalse(look.hasValue)
        assertTrue(look.arrowRate.isNaN())
        // "---" in the normal colour, not dimmed.
        assertEquals(1f, look.valueAlpha, 0f)
        // The card still says when the last reading was.
        assertEquals(t0, phone.card()!!.readingTime)
        assertEquals(30L, phone.card()!!.ageMinutes)
    }

    @Test
    fun aNewReadingAfterStalenessIsCurrentAgain() {
        val phone = Phone()
        phone.advanceTo(phone.last.staleAtMillis)
        assertTrue(phone.pill().stale)

        phone.reading = scripted(t0 + 7 * minute, "118", -0.6f)
        phone.advanceTo(t0 + 7 * minute + 3_000L)
        phone.hub.onReadingAccepted(sensor, t0 + 7 * minute, Origin.PEER)
        assertEquals(Pill(phone.last.revision, "118", -0.6f, stale = false), phone.pill())
        assertEquals(1f, FloatingPillLook.of(phone.last).valueAlpha, 0f)
        assertEquals(FloatingNextReading.State(0.01f, late = false), phone.ring())
    }

    @Test
    fun aOneMinuteSensorsPillGoesStaleAtItsOwnDeadline() {
        val phone = Phone(declaredIntervalMs = minute)
        assertEquals(t0 + minute, phone.last.nextExpectedAtMillis)
        phone.advanceTo(t0 + minute + 20_000L)
        assertTrue(phone.pill().stale)
        assertEquals(FloatingNextReading.State(1f, late = true), phone.ring())
    }
}
