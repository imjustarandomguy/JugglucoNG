package tk.glucodata.widget

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.DisplayedReading
import tk.glucodata.ReadingHub

class WidgetRefreshGateTests {
    private val gate = WidgetRefreshGate()
    private val minute = 60_000L
    private val t0 = 1_790_000_000_000L

    @Test
    fun afterAProcessStartTheFirstScreenOnDraws() {
        assertTrue(gate.needsRenderOnScreenOn())
    }

    @Test
    fun aRenderNeedsNothingAtScreenOnHoweverLongAgo() {
        // No clock of its own: the hub's stale publish is an update like any other.
        gate.renderedAll(gate.beginRender())
        assertFalse(gate.needsRenderOnScreenOn())
    }

    @Test
    fun anUpdateWhileTheScreenIsOffIsDrawnAtScreenOn() {
        gate.renderedAll(gate.beginRender())
        gate.deferWhileScreenOff()
        assertTrue(gate.needsRenderOnScreenOn())
        gate.renderedAll(gate.beginRender())
        assertFalse(gate.needsRenderOnScreenOn())
    }

    @Test
    fun anUpdateDeferredDuringARenderKeepsTheWidgetsDirty() {
        val token = gate.beginRender()
        // The screen goes off and a reading arrives after this render loaded its data.
        gate.deferWhileScreenOff()
        gate.renderedAll(token)
        assertTrue(gate.isDirty())
        assertTrue(gate.needsRenderOnScreenOn())
    }

    private fun published(now: Long): DisplayedReading = ReadingHub.compose(
        ReadingHub.Inputs(
            sensorId = "S1",
            reading = ReadingHub.Reading("S1", t0, 123f, "123", null, null, false, 0, 1.2f, 0),
            recentTimes = emptyList(),
            declaredIntervalMillis = 5 * minute,
        ),
        now,
    )

    @Test
    fun aHubThatKeptUpIsNotBehind() {
        val fresh = published(t0 + minute)
        assertFalse(WidgetRefreshGate.hubIsBehind(fresh, fresh.staleAtMillis - 1L))
        val stale = published(fresh.staleAtMillis)
        assertFalse(WidgetRefreshGate.hubIsBehind(stale, stale.noValueAtMillis - 1L))
        val gone = published(t0 + 30 * minute)
        assertFalse(WidgetRefreshGate.hubIsBehind(gone, t0 + 600 * minute))
        assertFalse(WidgetRefreshGate.hubIsBehind(DisplayedReading.NONE, t0))
    }

    @Test
    fun aDeadlineTheHubSleptThroughIsBehind() {
        val fresh = published(t0 + minute)
        assertTrue(WidgetRefreshGate.hubIsBehind(fresh, fresh.staleAtMillis))
        val stale = published(fresh.staleAtMillis)
        assertTrue(WidgetRefreshGate.hubIsBehind(stale, stale.noValueAtMillis + minute))
    }
}
