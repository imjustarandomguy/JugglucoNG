package tk.glucodata

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A queued connect used to latch out every other connect request for the same sensor until it
 * ran. Behind a Dexcom warmup retry, scheduled half an hour out on an executor whose clock stops
 * while the CPU sleeps, that dropped the loss-of-signal reconnect, every app start and the loss
 * alarm's reconnectall; only pausing the sensor let a connect through again.
 */
class PendingConnectPolicyTests {

    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()

    @After
    fun tearDown() {
        scheduler.shutdownNow()
    }

    private fun queued(delayMillis: Long) =
        scheduler.schedule({}, delayMillis, TimeUnit.MILLISECONDS)

    @Test
    fun aSoonerRequestReplacesAWarmupRetry() {
        val warmupRetry = queued(28L * 60L * 1000L)

        assertTrue(PendingConnectPolicy.cancelForSoonerRequest(warmupRetry, 0L))
        assertTrue(warmupRetry.isCancelled)
    }

    @Test
    fun aSoonerDelayedRequestAlsoReplacesIt() {
        val pending = queued(10L * 60L * 1000L)

        assertTrue(PendingConnectPolicy.cancelForSoonerRequest(pending, 500L))
        assertTrue(pending.isCancelled)
    }

    @Test
    fun aRequestDueAtTheSameTimeOrLaterKeepsThePendingConnect() {
        val pending = queued(60_000L)

        assertFalse(PendingConnectPolicy.cancelForSoonerRequest(pending, 60_000L))
        assertFalse(PendingConnectPolicy.cancelForSoonerRequest(pending, 5L * 60L * 1000L))
        assertFalse(pending.isCancelled)
    }

    @Test
    fun aNegativeDelayIsNotSoonerThanAnOverdueConnect() {
        val release = CountDownLatch(1)
        // Keep the only executor thread busy so the next connect is due but not yet started.
        scheduler.schedule({ release.await(5, TimeUnit.SECONDS) }, 0L, TimeUnit.MILLISECONDS)
        val overdue = queued(0L)
        Thread.sleep(5L)

        assertFalse(PendingConnectPolicy.cancelForSoonerRequest(overdue, -5_000L))
        assertFalse(overdue.isCancelled)
        release.countDown()
    }

    @Test
    fun aConnectTheExecutorHasStartedIsNotWithdrawn() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        // A started connect waits for the callback's monitor, held by connectDevice.
        val running = scheduler.schedule({
            started.countDown()
            release.await(5, TimeUnit.SECONDS)
        }, 0L, TimeUnit.MILLISECONDS)
        assertTrue(started.await(5, TimeUnit.SECONDS))

        assertFalse(PendingConnectPolicy.cancelForSoonerRequest(running, 0L))
        assertFalse(running.isCancelled)
        release.countDown()
    }

    @Test
    fun noFutureMeansNothingToReplace() {
        assertFalse(PendingConnectPolicy.cancelForSoonerRequest(null, 0L))
    }

    @Test
    fun connectDeviceAsksThePolicyBeforeSkipping() {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        var source: File? = null
        while (dir != null && source == null) {
            source = File(dir, "Common/src/main/java/tk/glucodata/SuperGattCallback.java")
                .takeIf { it.isFile }
                ?: File(dir, "src/main/java/tk/glucodata/SuperGattCallback.java").takeIf { it.isFile }
            dir = dir.parentFile
        }
        val text = requireNotNull(source) { "SuperGattCallback.java not found" }
            .readText().replace(Regex("\\s+"), " ")
        val body = text.substring(text.indexOf("public synchronized boolean connectDevice(long delayMillis)"))
        val pendingBranch = body.substring(body.indexOf("if (connectPending)"), body.indexOf("getConnectDevice();"))

        assertTrue(
            "a pending connect must give way to a sooner request",
            pendingBranch.contains("PendingConnectPolicy.cancelForSoonerRequest(pendingConnectFuture, delayMillis)"),
        )
        assertTrue(
            "the replaced connect must release the latch before scheduling the new one",
            pendingBranch.contains("clearPendingConnect();"),
        )
    }
}
