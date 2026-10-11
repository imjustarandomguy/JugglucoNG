package tk.glucodata.ui.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.DisplayedReading
import tk.glucodata.ReadingHub
import tk.glucodata.ui.PeerCurrentReading

/**
 * The pill's secondary slot on the hub's reading: with no second or third lane of its own,
 * it shows the first selected peer sensor whose reading is fresh, by the dashboard's rule
 * (SecondarySensorDisplay.fallback), the peer's primary value.
 */
class FloatingPillPeerTests {
    private val minute = 60_000L
    private val window = 330_000L
    private val t0 = 1_790_000_000_000L

    private fun reading(
        at: Long = t0,
        secondary: String? = null,
        tertiary: String? = null,
    ): DisplayedReading = ReadingHub.compose(
        ReadingHub.Inputs(
            sensorId = "MAIN",
            reading = ReadingHub.Reading("MAIN", t0, 123f, "123", secondary, tertiary, false, 0, 1.2f, 0),
            recentTimes = emptyList(),
            declaredIntervalMillis = 5 * minute,
        ),
        at,
    )

    private fun peer(id: String, at: Long = t0, text: String = "118") =
        PeerCurrentReading(id, text, secondaryStr = null, rate = 0f, timeMillis = at)

    private fun peerFor(shown: DisplayedReading, peers: List<PeerCurrentReading>, now: Long = shown.timeMillis) =
        FloatingPillLook.peerFor(shown, peers, now, window)

    @Test
    fun aFreshPeerFillsAnEmptySecondarySlot() {
        val shown = reading()
        val first = peer("PEER-A")
        val chosen = peerFor(shown, listOf(first, peer("PEER-B", text = "130")))
        assertSame(first, chosen)
        assertEquals("118", FloatingPillLook.of(shown, chosen).secondaryText)
    }

    @Test
    fun theReadingsOwnLanesKeepTheSlot() {
        val peers = listOf(peer("PEER"))
        assertNull(peerFor(reading(secondary = "131"), peers))
        assertNull(peerFor(reading(tertiary = "131"), peers))
        assertEquals("131", FloatingPillLook.of(reading(secondary = "131"), null).secondaryText)
    }

    @Test
    fun theMainSensorAndStalePeersAreSkipped() {
        val shown = reading()
        assertNull(peerFor(shown, listOf(peer("MAIN"), peer("PEER", at = t0 - window - 1L))))
        val fresh = peer("PEER-B")
        assertSame(fresh, peerFor(shown, listOf(peer("PEER-A", at = t0 - window - 1L), fresh)))
    }

    @Test
    fun aPeerGoesStaleOnItsOwnClock() {
        val shown = reading()
        val older = peer("PEER", at = t0 - window + minute)
        assertSame(older, peerFor(shown, listOf(older), now = t0))
        assertNull(peerFor(shown, listOf(older), now = t0 + minute + 1L))
    }

    @Test
    fun aDimmedValueKeepsItsPeerAndNoValueHasNone() {
        val peers = listOf(peer("PEER", at = t0 + 6 * minute))
        val stale = reading(at = t0 + 6 * minute)
        assertEquals(DisplayedReading.Freshness.STALE, stale.freshness)
        val look = FloatingPillLook.of(stale, peerFor(stale, peers, now = t0 + 6 * minute))
        assertEquals("118", look.secondaryText)
        assertTrue(look.valueAlpha < 1f)

        val gone = reading(at = t0 + 31 * minute)
        assertEquals(DisplayedReading.Freshness.NO_VALUE, gone.freshness)
        assertNull(peerFor(gone, listOf(peer("PEER", at = t0 + 31 * minute)), now = t0 + 31 * minute))
        assertNull(FloatingPillLook.of(gone, peer("PEER")).secondaryText)
    }
}
