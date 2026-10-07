package tk.glucodata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.PriorityQueue
import kotlin.random.Random

class GlucoseUpdateBroadcasterTests {
    /** A clock and a delayed-task queue that the test advances by hand. */
    private class FakeTime {
        var now = 0L
            private set
        private var order = 0L
        private val tasks = PriorityQueue<Triple<Long, Long, Runnable>>(
            compareBy<Triple<Long, Long, Runnable>>({ it.first }, { it.second })
        )

        fun schedule(delayMs: Long, run: Runnable) {
            tasks.add(Triple(now + delayMs, order++, run))
        }

        fun advanceTo(timeMs: Long) {
            while (tasks.isNotEmpty() && tasks.peek()!!.first <= timeMs) {
                val task = tasks.poll()!!
                now = task.first
                task.third.run()
            }
            now = timeMs
        }
    }

    private class Harness {
        val time = FakeTime()
        val deliveries = mutableListOf<Long>()
        private val coalescer = GlucoseUpdateBroadcaster.Coalescer(
            intervalMs = INTERVAL,
            clock = { time.now },
            schedule = time::schedule,
            deliver = { deliveries += time.now }
        )

        fun requestAt(timeMs: Long) {
            time.advanceTo(timeMs)
            coalescer.request()
        }
    }

    @Test fun firstRequestDeliversAtOnce() {
        val h = Harness()
        h.requestAt(300_000L)
        assertEquals(listOf(300_000L), h.deliveries)
    }

    @Test fun requestAfterAnEarlyOneIsDeliveredAtTheEndOfTheInterval() {
        // The reported case: the Room sync's refresh comes first, while the live
        // value is still the previous reading, and the notification's refresh
        // 50 ms later carries the new one. That second request used to be
        // dropped, leaving the widget a reading behind for five minutes.
        val h = Harness()
        h.requestAt(300_000L)
        h.requestAt(300_050L)
        assertEquals(listOf(300_000L), h.deliveries)
        h.time.advanceTo(300_000L + INTERVAL)
        assertEquals(listOf(300_000L, 300_000L + INTERVAL), h.deliveries)
    }

    @Test fun burstFoldsIntoOneTrailingDelivery() {
        val h = Harness()
        for (t in 0L until 900L step 100L) h.requestAt(600_000L + t)
        h.time.advanceTo(700_000L)
        assertEquals(listOf(600_000L, 600_000L + INTERVAL), h.deliveries)
    }

    @Test fun spacedRequestsEachDeliverAtOnce() {
        val h = Harness()
        h.requestAt(0L)
        h.requestAt(300_000L)
        h.requestAt(600_000L)
        assertEquals(listOf(0L, 300_000L, 600_000L), h.deliveries)
    }

    @Test fun requestAfterTheTrailingDeliveryGetsItsOwn() {
        val h = Harness()
        h.requestAt(0L)
        h.requestAt(500L)
        h.requestAt(INTERVAL + 200L)
        h.time.advanceTo(10_000L)
        assertEquals(listOf(0L, INTERVAL, 2 * INTERVAL), h.deliveries)
    }

    @Test fun lastRequestIsAlwaysDeliveredAndDeliveriesStaySpaced() {
        val random = Random(431)
        repeat(200) {
            val h = Harness()
            var t = random.nextLong(0L, 10_000L)
            val requests = mutableListOf<Long>()
            repeat(random.nextInt(1, 30)) {
                requests += t
                h.requestAt(t)
                t += random.nextLong(0L, 2 * INTERVAL)
            }
            h.time.advanceTo(t + 2 * INTERVAL)

            // The last delivery reads what the last request published.
            assertTrue("a delivery at or after the last request ${requests.last()}: ${h.deliveries}",
                h.deliveries.last() >= requests.last())
            h.deliveries.zipWithNext().forEach { (a, b) ->
                assertTrue("deliveries $a and $b at least $INTERVAL ms apart", b - a >= INTERVAL)
            }
            assertTrue("never more deliveries than requests",
                h.deliveries.size <= requests.size)
        }
    }

    private companion object {
        const val INTERVAL = GlucoseUpdateBroadcaster.MIN_BROADCAST_INTERVAL_MS
    }
}
