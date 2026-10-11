package tk.glucodata

import org.junit.After
import org.junit.Assert.assertEquals
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

    @Before
    fun installFakes() {
        savedAlarms = PhoneReadingClock.alarms
        savedLossAlarm = PhoneReadingClock.lossAlarmAt
        PhoneReadingClock.alarms = alarms
        PhoneReadingClock.lossAlarmAt = { lossAlarmAt }
    }

    @After
    fun restore() {
        PhoneReadingClock.alarms = savedAlarms
        PhoneReadingClock.lossAlarmAt = savedLossAlarm
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
}
