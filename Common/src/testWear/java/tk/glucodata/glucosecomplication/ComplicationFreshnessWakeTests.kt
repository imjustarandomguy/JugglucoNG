package tk.glucodata.glucosecomplication

import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import tk.glucodata.ReadingClock
import tk.glucodata.ReadingHub
import tk.glucodata.ReadingScheduler

/**
 * The watch's stale alarm keeps the watch awake until the complications have been asked to
 * show the reading it takes stale, not just until its receiver returns.
 */
class ComplicationFreshnessWakeTests {
    private val minute = 60_000L
    private val t0 = 1_700_000_000_000L

    private lateinit var savedAfterDrawn: (Runnable) -> Unit
    private lateinit var savedBackstop: (Runnable) -> Unit
    private val drawnSteps = ArrayList<Runnable>()
    private val backstops = ArrayList<Runnable>()

    @Before
    fun installFakes() {
        savedAfterDrawn = ComplicationFreshness.afterDrawn
        savedBackstop = ComplicationFreshness.backstop
        ComplicationFreshness.afterDrawn = { drawnSteps += it }
        ComplicationFreshness.backstop = { backstops += it }
    }

    @After
    fun restore() {
        ComplicationFreshness.afterDrawn = savedAfterDrawn
        ComplicationFreshness.backstop = savedBackstop
    }

    @Test
    fun theWakeLetsGoOnceTheComplicationsWereAskedAndOnlyOnce() {
        var releases = 0
        ComplicationFreshness.untilDrawn { releases++ }
        assertEquals(0, releases)
        drawnSteps.single().run()
        assertEquals(1, releases)
        backstops.single().run()
        assertEquals(1, releases)
    }

    @Test
    fun aWakeWhosePublishNeverComesLetsGoAtTheBackstop() {
        var releases = 0
        ComplicationFreshness.untilDrawn { releases++ }
        backstops.single().run()
        drawnSteps.single().run()
        assertEquals(1, releases)
    }

    private class NoScheduler : ReadingScheduler {
        override fun schedule(atMillis: Long, wake: Runnable) {}
        override fun cancel() {}
    }

    @Test
    fun theStaleWakeLetsGoAfterTheStalePublish() {
        val worker = Executors.newSingleThreadExecutor()
        try {
            var now = t0
            val events = Collections.synchronizedList(ArrayList<String>())
            val reading = ReadingHub.Reading("G7", t0, 123f, "123", null, null, false, 0, 0.5f, 0)
            val hub = ReadingHub(
                source = { Thread.sleep(20L); ReadingHub.Inputs("G7", reading, emptyList(), 5 * minute) },
                worker = worker,
                clock = ReadingClock(NoScheduler()),
                now = { now },
            )
            // WatchSurfaces, which hands each publish to the complications.
            hub.addListener { events += "complications ${it.freshness}" }
            hub.awaitCurrent(5_000L)
            ComplicationFreshness.afterDrawn = { task -> hub.afterPending(task) }
            val released = CountDownLatch(1)
            // ComplicationFreshnessReceiver: the alarm fires, the broadcast stays pending.
            now = t0 + 5 * minute + 20_000L
            hub.onDeadline()
            ComplicationFreshness.untilDrawn { events += "finished"; released.countDown() }
            assertTrue(released.await(5, TimeUnit.SECONDS))
            assertEquals(listOf("complications FRESH", "complications STALE", "finished"), events.toList())
        } finally {
            worker.shutdownNow()
        }
    }

    @Test
    fun theReceiverKeepsItsBroadcastPendingUntilDrawn() {
        val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .first { File(it, "Common/src/wear/java/tk/glucodata/glucosecomplication/ComplicationFreshness.kt").exists() }
        val receiver = File(root, "Common/src/wear/java/tk/glucodata/glucosecomplication/ComplicationFreshness.kt")
            .readText().substringAfter("class ComplicationFreshnessReceiver")
        assertTrue(receiver.contains("val pending = goAsync()"))
        assertTrue(receiver.contains("ComplicationFreshness.untilDrawn { pending.finish() }"))
    }
}
