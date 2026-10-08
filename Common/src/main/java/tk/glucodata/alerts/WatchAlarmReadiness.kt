package tk.glucodata.alerts

import java.nio.ByteBuffer
import tk.glucodata.Applic
import tk.glucodata.Log
import tk.glucodata.MessageSender
import tk.glucodata.SensorOwnershipRuntime
import tk.glucodata.WearMessagePath

/**
 * Whether the watch can take the alarms "Watch when connected" leaves to it.
 *
 * Capability discovery lists the watch app while it is installed, also when it
 * is not running. So the phone holds an alarm for the watch only on a fresh
 * report from it (`/sync2/watchstatus`): received within [FRESH_MS], about a
 * reading the watch has (read by itself or mirrored from the phone) no older
 * than that, and a charger state no older than that report. Anything less, and
 * the phone rings.
 *
 * The watch reports while that mode is on: with its readings, at most every
 * [REPORT_EVERY_MS] unless its charger state changed, and when the phone comes
 * back into reach. Its sensor-ownership reports carry the charger state too
 * ([tk.glucodata.SensorOwnershipRuntime]), so a charger change between
 * readings gets through within its minute tick.
 */
object WatchAlarmReadiness {
    private const val LOG_ID = "WatchAlarmReadiness"

    /** Two 5-minute reading intervals and a minute of slack: one lost report is no drop-out. */
    const val FRESH_MS = 11 * 60_000L

    /** At most one report per 5-minute reading; faster sensors report this often. */
    const val REPORT_EVERY_MS = 4 * 60_000L

    /** How often the watch reads its charger state between readings. */
    const val CHARGING_CHECK_MS = 60_000L

    /** What the watch says: its newest reading's time, and whether it is on its charger. */
    internal data class Report(val lastReadingMs: Long, val charging: Boolean?)

    /** The phone's copy of the last report: when it arrived, and the reading it was about. */
    internal data class Status(val receivedAtMs: Long, val lastReadingMs: Long)

    /** The last charger state the phone heard, from either report, and when. */
    internal data class Charging(val charging: Boolean?, val receivedAtMs: Long)

    // ------------------------------------------------------------ the decision

    /**
     * Whether [status] counts at [nowMs]: received within [FRESH_MS] (not after
     * [nowMs]: the clock moved), about a reading no older than [FRESH_MS].
     */
    internal fun isFresh(status: Status?, nowMs: Long): Boolean {
        val report = status ?: return false
        return nowMs - report.receivedAtMs in 0..FRESH_MS && nowMs - report.lastReadingMs <= FRESH_MS
    }

    /**
     * The "watch reachable" of [AlarmRouting.shouldRing]: discovery finds the
     * watch app ([discovered], null when it cannot tell) and its report is fresh.
     */
    internal fun reachable(discovered: Boolean?, status: Status?, nowMs: Long): Boolean? =
        if (discovered != true) discovered else isFresh(status, nowMs)

    /** The charger state, when [status] is fresh and [charging] no older than it; null otherwise. */
    internal fun charging(status: Status?, charging: Charging?, nowMs: Long): Boolean? {
        if (status == null || !isFresh(status, nowMs)) return null
        val heard = charging ?: return null
        return if (heard.receivedAtMs >= status.receivedAtMs) heard.charging else null
    }

    // ------------------------------------------------------------ the phone

    @Volatile private var status: Status? = null
    @Volatile private var statusCharging: Charging? = null

    /** Phone: whether the watch can ring now, for [AlarmRouting]. */
    @JvmStatic
    fun watchReachable(discovered: Boolean?): Boolean? = reachable(discovered, status, System.currentTimeMillis())

    /**
     * Phone: whether the watch is on its charger, from the newer of its status report
     * and its last sensor-ownership report, when no older than its status.
     */
    @JvmStatic
    fun watchCharging(): Boolean? {
        val heard = listOfNotNull(statusCharging, SensorOwnershipRuntime.peerChargingHeard())
            .maxByOrNull { it.receivedAtMs }
        return charging(status, heard, System.currentTimeMillis())
    }

    /** Phone: how old the last report is, for the log line of a held alarm; -1 without one. */
    @JvmStatic
    fun reportAgeMs(): Long = status?.let { System.currentTimeMillis() - it.receivedAtMs } ?: -1L

    /** Phone: a `/sync2/watchstatus` report. Never throws. */
    @JvmStatic
    fun onWatchReport(data: ByteArray?) {
        if (Applic.isWearable) return
        val report = decode(data)
        if (report == null) {
            Log.w(LOG_ID, "ignoring an unreadable watch status (${data?.size ?: 0} bytes)")
            return
        }
        val now = System.currentTimeMillis()
        status = Status(now, report.lastReadingMs)
        statusCharging = Charging(report.charging, now)
    }

    // ------------------------------------------------------------ the watch

    private val reporter = WatchStatusReporter()

    @Volatile private var reporting = false

    /**
     * Watch: the alerts evaluated their current reading, at [readingMs] (read here or
     * mirrored). Reports to the phone while "Watch when connected" is on. Never throws.
     */
    @JvmStatic
    fun onWatchEvaluated(readingMs: Long) {
        if (!Applic.isWearable) return
        try {
            val on = AlertRepository.loadAlarmRouting() == AlarmRoutingMode.WATCH_WHEN_CONNECTED
            if (on && !reporting) reporter.owe()
            reporting = on
            if (!on) return
            val report = reporter.onEvaluated(readingMs, System.currentTimeMillis(), SensorOwnershipRuntime::localCharging) ?: return
            MessageSender.sendSyncMessage(WearMessagePath.SYNC2_WATCH_STATUS, encode(report))
        } catch (t: Throwable) {
            Log.stack(LOG_ID, "onWatchEvaluated", t)
        }
    }

    /**
     * Discovery found, or lost, the other device. The phone forgets a watch out of
     * reach (it may go on its charger without a word); the watch reports again as the
     * phone comes back.
     */
    @JvmStatic
    fun onPeerReachabilityChanged(reachable: Boolean) {
        if (!Applic.isWearable) {
            if (!reachable) {
                status = null
                statusCharging = null
            }
            return
        }
        if (!reachable) return
        reporter.owe()
        onWatchEvaluated(0L)
    }

    // ------------------------------------------------------------ wire

    private const val VERSION = 1
    private const val SIZE = 1 + 1 + 8
    private const val CHARGING_KNOWN = 0x01
    private const val CHARGING_ON = 0x02

    internal fun encode(report: Report): ByteArray {
        val flags = when (report.charging) {
            null -> 0
            true -> CHARGING_KNOWN or CHARGING_ON
            false -> CHARGING_KNOWN
        }
        return ByteBuffer.allocate(SIZE)
            .put(VERSION.toByte())
            .put(flags.toByte())
            .putLong(report.lastReadingMs)
            .array()
    }

    /** Reads its own fields; a later build may append more. Null when unreadable. */
    internal fun decode(data: ByteArray?): Report? {
        if (data == null || data.size < SIZE || data[0].toInt() != VERSION) return null
        val buffer = ByteBuffer.wrap(data)
        buffer.get()
        val flags = buffer.get().toInt()
        val lastReadingMs = buffer.long
        val charging = if (flags and CHARGING_KNOWN == 0) null else flags and CHARGING_ON != 0
        return Report(lastReadingMs, charging)
    }
}

/** The watch's side of [WatchAlarmReadiness]: when a report is due, and what it says. */
internal class WatchStatusReporter {
    private var lastReadingMs = 0L
    private var lastSentAtMs = 0L
    private var lastSentReadingMs = 0L
    private var lastSentCharging: Boolean? = null
    private var chargingReadAtMs = 0L
    private var owed = true

    /** The phone came into reach, or the mode was switched on: the next call reports. */
    @Synchronized
    fun owe() {
        owed = true
    }

    /**
     * The report due after the alerts evaluated [readingMs] at [nowMs], or null. A new
     * reading reports once [WatchAlarmReadiness.REPORT_EVERY_MS] has passed since the
     * last report; a charger change reports at once. [chargingNow] is read on a new
     * reading, when owed, and between readings at most every
     * [WatchAlarmReadiness.CHARGING_CHECK_MS].
     */
    @Synchronized
    fun onEvaluated(readingMs: Long, nowMs: Long, chargingNow: () -> Boolean?): WatchAlarmReadiness.Report? {
        val newReading = readingMs > lastReadingMs
        if (newReading) lastReadingMs = readingMs
        if (lastReadingMs <= 0L) return null
        if (!newReading && !owed && nowMs - chargingReadAtMs in 0 until WatchAlarmReadiness.CHARGING_CHECK_MS) {
            return null
        }
        chargingReadAtMs = nowMs
        val charging = chargingNow()
        val readingDue = lastReadingMs > lastSentReadingMs &&
            nowMs - lastSentAtMs !in 0 until WatchAlarmReadiness.REPORT_EVERY_MS
        if (!owed && charging == lastSentCharging && !readingDue) return null
        owed = false
        lastSentAtMs = nowMs
        lastSentReadingMs = lastReadingMs
        lastSentCharging = charging
        return WatchAlarmReadiness.Report(lastReadingMs, charging)
    }
}
