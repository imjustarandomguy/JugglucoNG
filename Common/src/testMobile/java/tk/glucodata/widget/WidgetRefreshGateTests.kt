package tk.glucodata.widget

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetRefreshGateTests {
    private val freshness = 330_000L
    private val gate = WidgetRefreshGate(freshness)
    private val reading = 1_000_000L

    @Test
    fun afterAProcessStartTheFirstScreenOnDraws() {
        assertTrue(gate.needsRenderOnScreenOn(reading))
    }

    @Test
    fun aFreshRenderNeedsNothingAtScreenOnUntilItAges() {
        gate.renderedAll(gate.beginRender(), reading)
        assertFalse(gate.needsRenderOnScreenOn(reading + 60_000L))
        assertFalse(gate.needsRenderOnScreenOn(reading + freshness))
        // The loss alarm may have come while the screen was off, or not at all:
        // never show an aged value as fresh at screen-on.
        assertTrue(gate.needsRenderOnScreenOn(reading + freshness + 1L))
    }

    @Test
    fun anUpdateWhileTheScreenIsOffIsDrawnAtScreenOn() {
        gate.renderedAll(gate.beginRender(), reading)
        gate.deferWhileScreenOff()
        assertTrue(gate.needsRenderOnScreenOn(reading + 1_000L))
        gate.renderedAll(gate.beginRender(), reading + 300_000L)
        assertFalse(gate.needsRenderOnScreenOn(reading + 301_000L))
    }

    @Test
    fun aStaleRenderDoesNotRedrawAtEveryScreenOn() {
        gate.renderedAll(gate.beginRender(), 0L)
        assertFalse(gate.needsRenderOnScreenOn(reading + 10 * freshness))
    }

    @Test
    fun anUpdateDeferredDuringARenderKeepsTheWidgetsDirty() {
        val token = gate.beginRender()
        // The screen goes off and a reading arrives after this render loaded its data.
        gate.deferWhileScreenOff()
        gate.renderedAll(token, reading)
        assertTrue(gate.isDirty())
        assertTrue(gate.needsRenderOnScreenOn(reading + 1_000L))
    }
}
