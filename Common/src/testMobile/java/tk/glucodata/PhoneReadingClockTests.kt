package tk.glucodata

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import tk.glucodata.DisplayedReading.Freshness
import tk.glucodata.DisplayedReading.Origin

/**
 * The phone's reading clock: one exact wake-up alarm at the moment the shown reading goes
 * stale or is taken down, set through the [PhoneReadingClock.Alarms] seam, sharing the
 * loss-of-signal alarm's wake when that one is due within seconds after it.
 */
class PhoneReadingClockTests {
    private val minute = 60_000L
    private val t0 = 1_700_000_000_000L
    private val sensor = "G7-SENSOR"

    private class FakeAlarms : PhoneReadingClock.Alarms {
        val set = ArrayList<Long>()
        var cancels = 0

        /** The one alarm pending, as AlarmManager keeps one per PendingIntent; 0 for none. */
        var pending = 0L

        override fun setExact(atMillis: Long) {
            set += atMillis
            pending = atMillis
        }

        override fun cancel() {
            cancels++
            pending = 0L
        }
    }

    private val alarms = FakeAlarms()
    private var lossAlarmAt = 0L
    private var now = t0
    private var reading: ReadingHub.Reading? = scripted(t0)
    private val published = ArrayList<DisplayedReading>()
    private lateinit var savedAlarms: PhoneReadingClock.Alarms
    private lateinit var savedLossAlarm: () -> Long
    private lateinit var savedAfterDrawn: (Runnable) -> Unit
    private lateinit var savedBackstop: (Runnable) -> Unit

    /** What the wake's chain and its backstop were handed, run by the test when it chooses. */
    private val drawnSteps = ArrayList<Runnable>()
    private val backstops = ArrayList<Runnable>()

    @Before
    fun installFakes() {
        savedAlarms = PhoneReadingClock.alarms
        savedLossAlarm = PhoneReadingClock.lossAlarmAt
        savedAfterDrawn = PhoneReadingClock.afterDrawn
        savedBackstop = PhoneReadingClock.backstop
        PhoneReadingClock.alarms = alarms
        PhoneReadingClock.lossAlarmAt = { lossAlarmAt }
        PhoneReadingClock.afterDrawn = { drawnSteps += it }
        PhoneReadingClock.backstop = { backstops += it }
    }

    @After
    fun restore() {
        PhoneReadingClock.alarms = savedAlarms
        PhoneReadingClock.lossAlarmAt = savedLossAlarm
        PhoneReadingClock.afterDrawn = savedAfterDrawn
        PhoneReadingClock.backstop = savedBackstop
    }

    private fun scripted(time: Long) = ReadingHub.Reading(
        sensorId = sensor,
        timeMillis = time,
        value = 120f,
        valueText = "120",
        secondaryText = null,
        tertiaryText = null,
        isMmol = false,
        viewMode = 0,
        rate = 0.4f,
        index = 0,
    )

    /** A hub on the phone's clock, as PhoneSurfaces installs it. */
    private fun hub() = ReadingHub(
        source = { ReadingHub.Inputs(sensor, reading, emptyList(), 5 * minute) },
        worker = { it.run() },
        clock = ReadingClock(PhoneReadingClock),
        now = { now },
    ).also { hub -> hub.addListener { published += it } }

    /** The alarm fires: what PhoneReadingClockReceiver hands the hub. */
    private fun fire(hub: ReadingHub) {
        now = alarms.pending
        alarms.pending = 0L
        hub.onDeadline()
    }

    @Test
    fun aReadingArmsOneExactAlarmAtItsStaleMoment() {
        hub()
        assertEquals(listOf(t0 + 5 * minute + 20_000L), alarms.set)
    }

    @Test
    fun aNewReadingMovesTheAlarmBeforeItFires() {
        val hub = hub()
        reading = scripted(t0 + 5 * minute)
        now = t0 + 5 * minute + 2_000L
        hub.onReadingAccepted(sensor, t0 + 5 * minute, Origin.LOCAL)
        assertEquals(t0 + 10 * minute + 20_000L, alarms.pending)
        assertEquals(1, published.count { it.reason == DisplayedReading.Reason.NEW_READING })
    }

    @Test
    fun itsWakeTakesTheReadingStaleThenDown() {
        val hub = hub()
        fire(hub)
        assertEquals(Freshness.STALE, published.last().freshness)
        assertEquals(t0 + 30 * minute, alarms.pending)
        fire(hub)
        assertEquals(Freshness.NO_VALUE, published.last().freshness)
        // Nothing changes by itself any more: no alarm set again.
        assertEquals(0L, alarms.pending)
        assertEquals(2, alarms.set.size)
    }

    @Test
    fun aLossAlarmDueWithinSecondsAfterTheStaleMomentWakesForBoth() {
        // The other device reads the sensor too: the loss alarm comes at reading + 300 s + 20 s.
        lossAlarmAt = t0 + 5 * minute + 20_000L
        val hub = hub()
        // Only a backstop behind it, which the loss alarm's wake moves on before it fires.
        assertEquals(lossAlarmAt + PhoneReadingClock.SHARED_WAKE_MS, alarms.pending)
        now = lossAlarmAt
        hub.onClockChanged() // MobileGlucoseAlarms.handlealarm
        assertEquals(Freshness.STALE, published.last().freshness)
        assertEquals(t0 + 30 * minute, alarms.pending)
    }

    @Test
    fun aLossAlarmAMinuteLaterKeepsTheStaleMomentOnTime() {
        // A Dexcom's loss alarm: reading + 360 s + 20 s.
        lossAlarmAt = t0 + 6 * minute + 20_000L
        hub()
        assertEquals(t0 + 5 * minute + 20_000L, alarms.pending)
    }

    @Test
    fun theSharedWakeRuleInBothDirections() {
        val deadline = t0
        assertEquals(deadline, PhoneReadingClock.wakeAt(deadline, 0L))
        assertEquals(deadline + 10_000L, PhoneReadingClock.wakeAt(deadline, deadline))
        assertEquals(deadline + 20_000L, PhoneReadingClock.wakeAt(deadline, deadline + 10_000L))
        assertEquals(deadline, PhoneReadingClock.wakeAt(deadline, deadline + 10_001L))
        // A loss alarm due before the stale moment cannot make it stale early.
        assertEquals(deadline, PhoneReadingClock.wakeAt(deadline, deadline - 1_000L))
    }

    // --- The wake holds the phone until its publish is drawn. ---

    @Test
    fun aWakeLetsGoOnceItsPublishIsDrawnAndOnlyOnce() {
        var releases = 0
        PhoneReadingClock.untilDrawn { releases++ }
        assertEquals("held while the hub and the notification work", 0, releases)
        drawnSteps.single().run()
        assertEquals(1, releases)
        backstops.single().run()
        assertEquals("the backstop after it releases nothing", 1, releases)
    }

    @Test
    fun aWakeWhosePublishNeverComesLetsGoAtTheBackstop() {
        var releases = 0
        PhoneReadingClock.untilDrawn { releases++ }
        backstops.single().run()
        assertEquals(1, releases)
        drawnSteps.single().run()
        assertEquals(1, releases)
    }

    @Test
    fun theDeadlineWakeLetsGoAfterTheStaleReadingWasPublished() {
        val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val events = java.util.Collections.synchronizedList(ArrayList<String>())
            val hub = ReadingHub(
                source = { Thread.sleep(20L); ReadingHub.Inputs(sensor, reading, emptyList(), 5 * minute) },
                worker = worker,
                clock = ReadingClock(PhoneReadingClock),
                now = { now },
            )
            // The notification, as PhoneSurfaces hands it each publish.
            hub.addListener { events += "drawn ${it.freshness}" }
            hub.awaitCurrent(5_000L)
            PhoneReadingClock.afterDrawn = { task -> hub.afterPending(task) }
            val released = java.util.concurrent.CountDownLatch(1)
            // PhoneReadingClockReceiver: the alarm fires, the broadcast stays pending.
            now = alarms.pending
            hub.onDeadline()
            PhoneReadingClock.untilDrawn { events += "finished"; released.countDown() }
            assertTrue(released.await(5, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(listOf("drawn FRESH", "drawn STALE", "finished"), events.toList())
        } finally {
            worker.shutdownNow()
        }
    }

    @Test
    fun bothWakesHoldThePhoneUntilDrawn() {
        val root = generateSequence(java.io.File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .first { java.io.File(it, "Common/src/mobile/java/tk/glucodata/PhoneReadingClock.kt").exists() }
        val clock = java.io.File(root, "Common/src/mobile/java/tk/glucodata/PhoneReadingClock.kt").readText()
        val receiver = clock.substringAfter("class PhoneReadingClockReceiver")
        assertTrue(receiver.contains("val pending = goAsync()"))
        assertTrue(receiver.contains("PhoneReadingClock.untilDrawn { pending.finish() }"))
        val alarms = java.io.File(root, "Common/src/mobile/java/tk/glucodata/MobileGlucoseAlarms.java").readText()
        assertTrue(alarms.contains("PhoneReadingClock.reconcileAwake();"))
        assertFalse(alarms.contains("CurrentReadingHub.onClockChanged();"))
    }
}
