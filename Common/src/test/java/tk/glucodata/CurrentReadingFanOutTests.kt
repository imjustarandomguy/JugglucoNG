package tk.glucodata

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.DisplayedReading.Origin
import tk.glucodata.DisplayedReading.Reason

/**
 * One fan-out: every path that accepts a reading tells the current reading's hub once, under
 * the origin it knows, and the surfaces follow the hub's single publish instead of being
 * poked one by one.
 */
class CurrentReadingFanOutTests {
    private val minute = 60_000L
    private val t0 = 1_700_000_000_000L
    private val sensor = "SENSOR-1"

    private fun reading(time: Long) = ReadingHub.Reading(
        sensorId = sensor,
        timeMillis = time,
        value = 120f,
        valueText = "120",
        secondaryText = null,
        tertiaryText = null,
        isMmol = false,
        viewMode = 0,
        rate = 0.5f,
        index = 0,
    )

    private class NoScheduler : ReadingScheduler {
        override fun schedule(atMillis: Long, wake: Runnable) {}
        override fun cancel() {}
    }

    /**
     * A reading accepted as dowithglucose does it: the hub is told once, then the data
     * refresh after storing it asks again ([extraRefreshes] more for a path that stores
     * once more, as the virtual sources do).
     */
    private fun accept(origin: Origin, extraRefreshes: Int): List<DisplayedReading> {
        var current = reading(t0)
        var now = t0
        val published = ArrayList<DisplayedReading>()
        val hub = ReadingHub(
            source = { ReadingHub.Inputs(sensor, current, emptyList(), 5 * minute) },
            worker = { it.run() },
            clock = ReadingClock(NoScheduler()),
            now = { now },
        )
        hub.addListener { published += it }
        published.clear()
        current = reading(t0 + 5 * minute)
        now = t0 + 5 * minute + 2_000L
        hub.onReadingAccepted(sensor, t0 + 5 * minute, origin)
        repeat(1 + extraRefreshes) { hub.onDisplayInputsChanged(Reason.BACKFILL) }
        return published
    }

    @Test
    fun everyOriginPublishesItsReadingOnceAsNew() {
        for ((origin, extra) in listOf(Origin.LOCAL to 0, Origin.FOLLOWER to 1, Origin.PEER to 0)) {
            val published = accept(origin, extra)
            assertEquals("$origin: one publish", 1, published.size)
            assertEquals(Reason.NEW_READING, published.single().reason)
            assertEquals(origin, published.single().origin)
        }
    }

    private val root: File by lazy {
        generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .first { File(it, "Common/src/main/java/tk/glucodata/SuperGattCallback.java").exists() }
    }

    private fun source(path: String): String = File(root, path).readText().replace("\r\n", "\n")

    /** The body of the method declared by [signature] in [text]. */
    private fun body(text: String, signature: String): String {
        val start = text.indexOf(signature)
        check(start >= 0) { "$signature not found" }
        var depth = 0
        var index = text.indexOf('{', start)
        val open = index
        while (index < text.length) {
            when (text[index]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return text.substring(open, index + 1)
            }
            index++
        }
        error("unbalanced $signature")
    }

    @Test
    fun theAcceptingPathTellsTheHubOnceAndPokesNoSurface() {
        val gatt = source("Common/src/main/java/tk/glucodata/SuperGattCallback.java")
        val accept = body(
            gatt,
            "long sensorstartmsec, long showtime, int sensorgen, LiveReadingLanes reading,\n" +
                "            DisplayedReading.Origin origin) {",
        )
        assertEquals(1, Regex("CurrentReadingHub\\.onReadingAccepted\\(").findAll(accept).count())
        assertTrue(accept.contains("CurrentReadingHub.onReadingAccepted(SerialNumber, timmsec, origin);"))
        // The always-on display and the floating value follow the hub's publish.
        assertFalse(gatt.contains("AOD_IMMEDIATE_REFRESH"))
        assertFalse(accept.contains("Floating.floatview"))
        assertFalse(accept.contains("postInvalidate"))
    }

    @Test
    fun externalReadingsReachTheHubUnderTheirOrigin() {
        val gatt = source("Common/src/main/java/tk/glucodata/SuperGattCallback.java")
        val external = body(
            gatt,
            "long timmsec, int sensorgen, DisplayedReading.Origin origin) {",
        )
        assertTrue(external.contains("Notify.glucosetimeout, sensorgen, reading, origin);"))
        // Nightscout, MQ and API sources all follow readings taken elsewhere.
        val bridge = source("Common/src/main/java/tk/glucodata/drivers/VirtualGlucoseSensorBridge.kt")
        assertTrue(bridge.contains("DisplayedReading.Origin.FOLLOWER,"))
    }
}
