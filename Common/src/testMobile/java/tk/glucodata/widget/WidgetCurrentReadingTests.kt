package tk.glucodata.widget

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

/**
 * Both widgets draw the device's one current reading, CurrentReadingHub's: the same
 * revision, value, arrow and freshness, changed by the publish that changed them. Their
 * stale look comes from the hub's stale publish, not from the loss alarm, and with the
 * screen off a publish only marks them dirty, to be drawn once at screen on.
 */
class WidgetCurrentReadingTests {
    private val minute = 60_000L
    private val t0 = 1_790_000_000_000L
    private val sensor = "G7-SENSOR"

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

    /**
     * A phone whose hub publishes one scripted reading, and what its widgets do with each
     * publish: drawn at once with the screen on (the reading they draw is kept), marked
     * dirty with it off, as GlucoseWidgets does.
     */
    private inner class Phone(declaredIntervalMs: Long = 5 * minute) {
        var now = t0
        var screenOn = true
        var reading: ReadingHub.Reading? = scripted(t0, "123", 1.4f)
        val scheduler = FakeScheduler()
        val gate = WidgetRefreshGate()
        val drawn = ArrayList<WidgetReading?>()
        val hub = ReadingHub(
            source = { ReadingHub.Inputs(sensor, reading, emptyList(), declaredIntervalMs) },
            worker = { it.run() },
            clock = ReadingClock(scheduler),
            now = { now },
        )

        init {
            hub.addListener { onPublish() }
        }

        private fun onPublish() {
            if (!screenOn) {
                gate.deferWhileScreenOff()
                return
            }
            render()
        }

        fun render() {
            val token = gate.beginRender()
            drawn += WidgetReading.of(hub.current())
            gate.renderedAll(token)
        }

        fun screenOnRender() {
            screenOn = true
            if (WidgetRefreshGate.hubIsBehind(hub.current(), now)) hub.onClockChanged()
            else if (gate.needsRenderOnScreenOn()) render()
        }

        fun advanceTo(atMillis: Long) {
            now = atMillis
            val wake = scheduler.wake
            if (wake != null && scheduler.at <= atMillis) {
                scheduler.wake = null
                wake.run()
            }
        }
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
    fun theWidgetsDrawTheHubsReadingAsItIsPublished() {
        val phone = Phone()
        val first = phone.hub.current()
        val shown = phone.drawn.last()!!
        assertEquals(first.revision, shown.revision)
        assertEquals(first.shownText, shown.valueText)
        assertEquals(first.shownRate, shown.rate, 0f)
        assertEquals(Freshness.FRESH, shown.freshness)
        assertTrue(shown.isFresh)

        phone.reading = scripted(t0 + 5 * minute, "131", 2.4f)
        phone.advanceTo(t0 + 5 * minute + 3_000L)
        phone.hub.onReadingAccepted(sensor, t0 + 5 * minute, Origin.LOCAL)
        val second = phone.drawn.last()!!
        assertEquals(phone.hub.current().revision, second.revision)
        assertEquals(Reason.NEW_READING, phone.hub.current().reason)
        assertEquals("131", second.valueText)
        assertEquals(2.4f, second.rate, 0f)
        assertEquals(2, phone.drawn.size)
    }

    @Test
    fun theStaleMomentStrikesTheValueAndDropsTheArrowInOnePublish() {
        val phone = Phone()
        val staleAt = phone.hub.current().staleAtMillis
        assertEquals(t0 + 5 * minute + 20_000L, staleAt)

        phone.advanceTo(staleAt - 1L)
        assertEquals(1, phone.drawn.size)
        phone.advanceTo(staleAt)
        assertEquals(2, phone.drawn.size)
        assertEquals(Reason.STALE_TICK, phone.hub.current().reason)
        val stale = phone.drawn.last()!!
        assertEquals("123", stale.valueText)
        assertTrue(stale.isStale)
        assertFalse(stale.isFresh)
        assertTrue("no arrow on a stale value", stale.rate.isNaN())
        assertEquals(t0, stale.timeMillis)
    }

    @Test
    fun thirtyMinutesAfterTheReadingTheWidgetsShowNoValue() {
        val phone = Phone()
        phone.advanceTo(phone.hub.current().staleAtMillis)
        phone.advanceTo(t0 + 30 * minute)
        assertEquals(3, phone.drawn.size)
        val gone = phone.drawn.last()!!
        assertEquals(Freshness.NO_VALUE, gone.freshness)
        assertEquals(DisplayedReading.NO_VALUE_TEXT, gone.valueText)
        assertFalse(gone.hasValue)
        // "---" is not a value to strike through; the time of the last one is still known.
        assertFalse(gone.isStale)
        assertTrue(gone.rate.isNaN())
        assertEquals(t0, gone.timeMillis)
    }

    @Test
    fun aStaleMomentWhileTheScreenIsOffIsDrawnAtScreenOn() {
        val phone = Phone()
        phone.screenOn = false
        phone.advanceTo(phone.hub.current().staleAtMillis)
        // Published, but nobody can see the widgets: nothing drawn, only marked.
        assertEquals(1, phone.drawn.size)
        assertTrue(phone.gate.isDirty())

        phone.advanceTo(phone.hub.current().staleAtMillis + 2 * minute)
        phone.screenOnRender()
        assertEquals(2, phone.drawn.size)
        assertTrue(phone.drawn.last()!!.isStale)
        assertFalse(phone.gate.isDirty())
    }

    @Test
    fun aDeadlineTheHubsClockSleptThroughIsDrawnFromItsRepublish() {
        val phone = Phone()
        phone.screenOn = false
        // The phone slept: the hub's wake did not run at the stale moment.
        phone.now = phone.hub.current().staleAtMillis + 2 * minute
        assertEquals(Freshness.FRESH, phone.hub.current().freshness)

        phone.screenOnRender()
        assertEquals(Reason.STALE_TICK, phone.hub.current().reason)
        assertEquals(2, phone.drawn.size)
        assertTrue(phone.drawn.last()!!.isStale)
    }

    @Test
    fun noReadingAtAllIsNotAReading() {
        assertNull(WidgetReading.of(DisplayedReading.NONE))
        assertNull(WidgetReading.of(DisplayedReading.NONE.copy(freshness = Freshness.NO_SENSOR)))
    }
}
