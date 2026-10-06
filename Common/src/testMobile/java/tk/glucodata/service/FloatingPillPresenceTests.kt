package tk.glucodata.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FloatingPillPresenceTests {
    @Test
    fun offScreenUntilTheScreenIsOnAndTheReadingsAreLoaded() {
        val presence = FloatingPillPresence()
        assertFalse(presence.shown)

        assertTrue(presence.onScreen(true))
        assertFalse(presence.shown)

        presence.onReadingsLoaded()
        assertTrue(presence.shown)
    }

    @Test
    fun screenOffTakesThePillOffAndItsReadingsCountAsStale() {
        val presence = FloatingPillPresence()
        presence.onScreen(true)
        presence.onReadingsLoaded()

        assertFalse(presence.onScreen(false))
        assertFalse(presence.shown)

        // Back on: what was loaded before the screen went off is not current.
        assertTrue(presence.onScreen(true))
        assertFalse(presence.shown)
        presence.onReadingsLoaded()
        assertTrue(presence.shown)
    }

    @Test
    fun readingsLoadedWhileTheScreenIsOffAreIgnored() {
        val presence = FloatingPillPresence()
        presence.onReadingsLoaded()
        assertFalse(presence.shown)

        presence.onScreen(true)
        presence.onReadingsLoaded()
        presence.onScreen(false)
        presence.onReadingsLoaded()
        assertTrue(presence.onScreen(true))
        assertFalse(presence.shown)
    }

    @Test
    fun repeatedScreenStatesDoNotReloadOrHide() {
        val presence = FloatingPillPresence()
        presence.onScreen(true)
        presence.onReadingsLoaded()

        assertFalse(presence.onScreen(true))
        assertTrue(presence.shown)

        presence.onScreen(false)
        assertFalse(presence.onScreen(false))
        assertFalse(presence.shown)
    }
}
