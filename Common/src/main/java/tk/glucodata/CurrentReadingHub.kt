package tk.glucodata

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import tk.glucodata.DisplayedReading.Freshness
import tk.glucodata.DisplayedReading.Origin
import tk.glucodata.DisplayedReading.Reason
import tk.glucodata.ui.util.GlucoseFormatter

/**
 * The current reading of one device, resolved once and handed to every surface.
 *
 * Every accepted reading and every change of what is displayed asks for a recompute; the
 * recomputes run one at a time on one worker and fold together while one is running. A
 * result is published (to [readings] and to the listeners) only when something drawn
 * changed, under a new [DisplayedReading.revision] and with the [Reason] it changed for.
 * The [ReadingClock] wakes it once at the next deadline, so a reading goes stale on every
 * surface in the same publish.
 *
 * Idle until someone reads it: a device whose surfaces still resolve on their own pays
 * nothing for it.
 */
class ReadingHub internal constructor(
    private val source: Source,
    private val worker: Executor,
    private val clock: ReadingClock,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** One reading as the display resolver gives it. */
    data class Reading(
        val sensorId: String?,
        val timeMillis: Long,
        val value: Float,
        val valueText: String,
        val secondaryText: String?,
        val tertiaryText: String?,
        val isMmol: Boolean,
        val viewMode: Int,
        /** The display arrow's rate in mg/dL per minute, NaN without one. */
        val rate: Float,
        val index: Int,
    )

    /** What one recompute reads. */
    class Inputs(
        /** The sensor the device displays; null without one. */
        val sensorId: String?,
        /** Its newest reading within [ReadingCadence.NO_VALUE_AFTER_MS], or null. */
        val reading: Reading?,
        /** Times of its recent readings, for the interval measured from them. */
        val recentTimes: List<Long>,
        /** The interval its driver declares, or 0. */
        val declaredIntervalMillis: Long,
        /** Without [reading]: the newest reading time known at all, or 0 for none. */
        val latestTimeMillis: Long = 0L,
    )

    /** Reads the [Inputs] at a moment. Runs on the hub's worker. */
    fun interface Source {
        fun read(nowMillis: Long): Inputs
    }

    /** Called on the hub's worker after each publish. */
    fun interface Listener {
        fun onReading(reading: DisplayedReading)
    }

    private val state = MutableStateFlow(DisplayedReading.NONE)
    private val readOnlyState = state.asStateFlow()
    private val firstRecompute = CountDownLatch(1)
    private val listeners = CopyOnWriteArrayList<Listener>()
    private val lock = Any()
    private val deadlineWake = Runnable { onDeadline() }

    // Guarded by lock.
    private var active = false
    private var queued = false
    private var pendingReason: Reason? = null
    private val acceptedOrigins = LinkedHashMap<Long, Origin>()

    // Worker only.
    private var published: DisplayedReading? = null
    private var revision = 0L

    /** The published reading; reading it starts the hub. */
    val readings: StateFlow<DisplayedReading>
        get() {
            activate()
            return readOnlyState
        }

    /** The published reading, [DisplayedReading.NONE] until the first publish. */
    fun current(): DisplayedReading {
        activate()
        return state.value
    }

    /**
     * [current], waiting up to [timeoutMillis] for the first publish of a hub that has just
     * started, so a surface drawn at process start does not show "no value" for a moment.
     * Not from a [Listener]: they run on the worker that publishes.
     */
    fun awaitCurrent(timeoutMillis: Long): DisplayedReading {
        val shown = current()
        if (shown.revision > 0L) return shown
        try {
            firstRecompute.await(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        return state.value
    }

    fun addListener(listener: Listener) {
        listeners.addIfAbsent(listener)
        activate()
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    /**
     * A reading of [sensorId] taken at [timeMillis] was accepted by the path that names its
     * [origin]. Called once per reading, after it is in place for the resolver.
     */
    fun onReadingAccepted(sensorId: String?, timeMillis: Long, origin: Origin) {
        if (timeMillis > 0L) {
            synchronized(lock) {
                acceptedOrigins[timeMillis] = origin
                while (acceptedOrigins.size > MAX_ORIGINS) {
                    acceptedOrigins.remove(acceptedOrigins.keys.first())
                }
            }
        }
        request(Reason.NEW_READING)
    }

    /** Something that changes what is shown changed: stored data ([Reason.BACKFILL]) or a setting. */
    fun onDisplayInputsChanged(reason: Reason) {
        request(reason)
    }

    /**
     * The clock may have jumped or slept past a deadline: screen on, time or zone change.
     * Freshness is worked out again from the wall clock.
     */
    fun onClockChanged() {
        clock.disarm()
        request(Reason.STALE_TICK)
    }

    /** The [ReadingClock]'s wake. */
    fun onDeadline() {
        clock.disarm()
        request(Reason.STALE_TICK)
    }

    /** Replaces the clock's scheduler; the next recompute arms the new one. */
    fun setScheduler(scheduler: ReadingScheduler) {
        clock.scheduler.cancel()
        clock.scheduler = scheduler
        clock.disarm()
        request(Reason.STALE_TICK)
    }

    /** Starts the hub; the first recompute publishes with [Reason.STARTUP]. */
    fun activate() {
        synchronized(lock) {
            if (active) return
            active = true
        }
        request(Reason.STARTUP)
    }

    private fun request(reason: Reason) {
        synchronized(lock) {
            pendingReason = strongest(pendingReason, reason)
            if (!active || queued) return
            queued = true
        }
        worker.execute(::recompute)
    }

    private fun recompute() {
        val trigger = synchronized(lock) {
            queued = false
            pendingReason.also { pendingReason = null }
        } ?: return
        try {
            val at = now()
            val origins = synchronized(lock) { HashMap(acceptedOrigins) }
            val next = compose(source.read(at), at) { time -> originOf(origins, time) }
            val previous = published
            if (previous == null || !next.sameDisplayAs(previous)) {
                val publish = next.copy(reason = classify(previous, next, trigger), revision = ++revision)
                published = publish
                state.value = publish
                if (Log.doLog) Log.i(LOG_ID, describe(publish))
                listeners.forEach { listener ->
                    try {
                        listener.onReading(publish)
                    } catch (t: Throwable) {
                        Log.stack(LOG_ID, "listener", t)
                    }
                }
            }
        } catch (t: Throwable) {
            Log.stack(LOG_ID, "recompute", t)
        }
        published?.let { clock.arm(it, deadlineWake) }
        firstRecompute.countDown()
    }

    companion object {
        private const val LOG_ID = "CurrentReadingHub"
        private const val MAX_ORIGINS = 8

        /** A reading and the time an accepting path gave it, at most this far apart, are one. */
        private const val SAME_READING_MS = 1_000L

        /**
         * [inputs] at [nowMillis], as shown. [originOf] names where the reading at a time
         * came from. Pure: the publishing rules are tested through it.
         */
        internal fun compose(
            inputs: Inputs,
            nowMillis: Long,
            originOf: (Long) -> Origin = { Origin.STORED },
        ): DisplayedReading {
            val reading = inputs.reading?.takeIf {
                it.timeMillis > 0L && it.value.isFinite() && it.value > 0f && it.valueText.isNotBlank()
            }
            val timeMillis = reading?.timeMillis ?: inputs.latestTimeMillis.coerceAtLeast(0L)
            val interval = ReadingCadence.intervalMillis(
                inputs.declaredIntervalMillis,
                if (reading != null) inputs.recentTimes + reading.timeMillis else inputs.recentTimes,
            )
            var freshness = ReadingCadence.freshness(
                sensorPresent = !inputs.sensorId.isNullOrBlank() || reading != null,
                timeMillis = timeMillis,
                intervalMillis = interval,
                nowMillis = nowMillis,
            )
            // A reading that is known to exist but could not be resolved has nothing to show.
            if (reading == null && (freshness == Freshness.FRESH || freshness == Freshness.STALE)) {
                freshness = Freshness.NO_VALUE
            }
            val shown = if (freshness == Freshness.FRESH || freshness == Freshness.STALE) reading else null
            val rate = shown?.rate?.takeIf { it.isFinite() } ?: Float.NaN
            val hasTime = timeMillis > 0L
            return DisplayedReading(
                sensorId = shown?.sensorId ?: inputs.sensorId ?: reading?.sensorId,
                timeMillis = timeMillis,
                mgdl = shown?.let { if (it.isMmol) it.value * GlucoseFormatter.MGDL_PER_MMOL else it.value }
                    ?: Float.NaN,
                value = shown?.value ?: Float.NaN,
                valueText = shown?.valueText.orEmpty(),
                secondaryText = shown?.secondaryText,
                tertiaryText = shown?.tertiaryText,
                isMmol = shown?.isMmol ?: false,
                viewMode = shown?.viewMode ?: 0,
                rate = rate,
                arrowDegrees = if (rate.isFinite()) TrendArrowAngle.rotationDegrees(rate) else Float.NaN,
                index = shown?.index ?: 0,
                freshness = freshness,
                intervalMillis = interval,
                nextExpectedAtMillis = if (hasTime) ReadingCadence.nextExpectedAt(timeMillis, interval) else 0L,
                staleAtMillis = if (hasTime) ReadingCadence.staleAt(timeMillis, interval) else 0L,
                noValueAtMillis = if (hasTime) ReadingCadence.noValueAt(timeMillis) else 0L,
                origin = if (hasTime) originOf(timeMillis) else Origin.STORED,
                reason = Reason.STARTUP,
                revision = 0L,
            )
        }

        /** Why [next] is published after [previous], asked for by [trigger]. */
        internal fun classify(previous: DisplayedReading?, next: DisplayedReading, trigger: Reason): Reason = when {
            previous == null -> Reason.STARTUP
            next.showsValue && next.timeMillis > previous.timeMillis -> Reason.NEW_READING
            // Another sensor, unit or view mode, whatever was stored at the time: a setting,
            // to be shown at once rather than folded with backfills.
            next.showsValue && previous.showsValue && (
                next.sensorId != previous.sensorId ||
                    next.isMmol != previous.isMmol ||
                    next.viewMode != previous.viewMode
                ) -> Reason.SETTINGS
            // Asked for by a reading that turned out not to be newer: one filled in behind.
            trigger == Reason.NEW_READING || trigger == Reason.STARTUP -> Reason.BACKFILL
            else -> trigger
        }

        /** Of two folded requests, the one a publish is more likely to be about. */
        private fun strongest(held: Reason?, incoming: Reason): Reason {
            if (held == null) return incoming
            return if (PRIORITY.indexOf(incoming) < PRIORITY.indexOf(held)) incoming else held
        }

        private val PRIORITY = listOf(
            Reason.NEW_READING,
            Reason.SETTINGS,
            Reason.BACKFILL,
            Reason.STALE_TICK,
            Reason.STARTUP,
        )

        private fun originOf(origins: Map<Long, Origin>, timeMillis: Long): Origin =
            origins.entries.firstOrNull { kotlin.math.abs(it.key - timeMillis) < SAME_READING_MS }?.value
                ?: Origin.STORED

        private fun describe(reading: DisplayedReading): String =
            "publish rev=${reading.revision} ${reading.reason} ${reading.freshness} " +
                "${reading.sensorId} at=${reading.timeMillis} \"${reading.valueText}\" rate=${reading.rate} " +
                "interval=${reading.intervalMillis} staleAt=${reading.staleAtMillis} origin=${reading.origin}"
    }
}

/**
 * The device's one [ReadingHub]. Surfaces render [readings] (or listen) and never resolve
 * the current reading themselves; reading paths report what they accepted.
 */
object CurrentReadingHub {
    private val source = StoredReadingSource()

    // Started on the first recompute, which waits for the first reader.
    private val worker by lazy {
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "CurrentReadingHub").apply { isDaemon = true }
        }
    }

    private val hub = ReadingHub(source, { worker.execute(it) }, ReadingClock(HandlerReadingScheduler()))

    @JvmStatic
    val readings: StateFlow<DisplayedReading>
        get() = hub.readings

    @JvmStatic
    fun current(): DisplayedReading = hub.current()

    /** [ReadingHub.awaitCurrent]: for a surface drawn at process start. */
    @JvmStatic
    fun awaitCurrent(timeoutMillis: Long): DisplayedReading = hub.awaitCurrent(timeoutMillis)

    @JvmStatic
    fun addListener(listener: ReadingHub.Listener) = hub.addListener(listener)

    @JvmStatic
    fun removeListener(listener: ReadingHub.Listener) = hub.removeListener(listener)

    @JvmStatic
    fun onReadingAccepted(sensorId: String?, timeMillis: Long, origin: Origin) =
        hub.onReadingAccepted(sensorId, timeMillis, origin)

    @JvmStatic
    fun onDisplayInputsChanged(reason: Reason) = hub.onDisplayInputsChanged(reason)

    @JvmStatic
    fun onClockChanged() = hub.onClockChanged()

    @JvmStatic
    fun onDeadline() = hub.onDeadline()

    /** Starts publishing: at process start on a device whose surfaces read the hub. */
    @JvmStatic
    fun activate() = hub.activate()

    /** The clock's scheduler for this platform, installed before [activate]. */
    @JvmStatic
    fun setScheduler(scheduler: ReadingScheduler) = hub.setScheduler(scheduler)

    /**
     * Another sensor of this device under the same rules, resolved on the caller's thread and
     * not published: for a surface that shows several sensors side by side.
     */
    @JvmStatic
    fun resolveFor(sensorId: String): DisplayedReading {
        val now = System.currentTimeMillis()
        return ReadingHub.compose(source.read(now, sensorId), now)
    }

    /**
     * The sensor this device displays, when it is not [SensorIdentity.resolveMainSensor]
     * (the watch follows its own selection).
     */
    @JvmStatic
    fun setDisplaySensor(resolver: () -> String?) {
        source.displaySensor = resolver
        hub.onDisplayInputsChanged(Reason.SETTINGS)
    }
}

/**
 * [ReadingHub.Inputs] from the display resolver and the stored readings: the reading
 * [CurrentDisplaySource] resolves, or the newest stored one when that is newer (a reading
 * the other device sent lands in storage, and the live value the resolver prefers can be
 * one reading behind it), and the arrow over the measured rows the dashboard regresses over.
 */
internal class StoredReadingSource : ReadingHub.Source {
    @Volatile
    var displaySensor: () -> String? = { SensorIdentity.resolveMainSensor() }

    /** The display arrow's rate for a snapshot, over measured rows of its sensor. */
    @Volatile
    var displayRate: (List<GlucosePoint>, CurrentDisplaySource.Snapshot) -> Float = ::dashboardRate

    override fun read(nowMillis: Long): ReadingHub.Inputs =
        read(nowMillis, runCatching { displaySensor() }.getOrNull())

    fun read(nowMillis: Long, sensorId: String?): ReadingHub.Inputs {
        val sensor = sensorId?.takeIf { it.isNotBlank() }
        val isMmol = Applic.unit == 1
        val history = runCatching {
            NotificationHistorySource.getDisplayHistory(nowMillis - HISTORY_MS, isMmol, sensor)
        }.getOrDefault(emptyList())
        val snapshot = newestSnapshot(sensor, history)
        val reading = snapshot?.let {
            ReadingHub.Reading(
                sensorId = it.sensorId,
                timeMillis = it.timeMillis,
                value = it.primaryValue,
                valueText = it.primaryStr,
                secondaryText = it.secondaryStr,
                tertiaryText = it.tertiaryStr,
                isMmol = it.isMmol,
                viewMode = it.viewMode,
                rate = runCatching { displayRate(history, it) }.getOrDefault(Float.NaN),
                index = it.index,
            )
        }
        val latest = if (reading != null) 0L else latestStoredTime(history, nowMillis, isMmol, sensor)
        return ReadingHub.Inputs(
            sensorId = sensor ?: snapshot?.sensorId,
            reading = reading,
            recentTimes = history.map { it.timestamp },
            declaredIntervalMillis = declaredIntervalMillis(snapshot?.sensorId ?: sensor),
            latestTimeMillis = latest,
        )
    }

    private fun newestSnapshot(sensor: String?, history: List<GlucosePoint>): CurrentDisplaySource.Snapshot? {
        val live = runCatching {
            CurrentDisplaySource.resolveCurrent(ReadingCadence.NO_VALUE_AFTER_MS, sensor, ReadingCadence.NO_VALUE_AFTER_MS)
        }.getOrNull()
        val newestRow = history.lastOrNull { it.value.isFinite() && it.value > 0f }
            ?: return live
        if (live != null && newestRow.timestamp <= live.timeMillis + SAME_READING_MS) return live
        // Resolved through the same calibration, smoothing and view mode as a live reading.
        return runCatching {
            CurrentDisplaySource.resolveIncomingReading(
                reading = LiveReadingLanes.stock(newestRow.value, newestRow.rawValue),
                rate = Float.NaN,
                targetTimeMillis = newestRow.timestamp,
                preferredSensorId = sensor,
            )
        }.getOrNull() ?: live
    }

    private fun latestStoredTime(history: List<GlucosePoint>, nowMillis: Long, isMmol: Boolean, sensor: String?): Long =
        history.lastOrNull()?.timestamp ?: runCatching {
            NotificationHistorySource.getDisplayHistory(nowMillis - LATEST_LOOKBACK_MS, isMmol, sensor)
                .lastOrNull()?.timestamp
        }.getOrNull() ?: 0L

    private fun declaredIntervalMillis(sensor: String?): Long {
        if (sensor.isNullOrBlank()) return 0L
        return runCatching {
            SensorBluetooth.mygatts()
                .firstOrNull { SensorIdentity.matches(it.SerialNumber, sensor) }
                ?.readingIntervalMillis()
        }.getOrNull() ?: 0L
    }

    private companion object {
        /** Enough for a dozen five-minute gaps, and the trend window behind a 30-min-old reading. */
        const val HISTORY_MS = 65L * 60_000L

        /** How far back a reading counts as "had one" rather than "awaiting the first". */
        const val LATEST_LOOKBACK_MS = 12L * 60L * 60_000L

        /** A stored row this much newer than the live reading is a newer reading. */
        const val SAME_READING_MS = 30_000L

        /**
         * The rate the dashboard and the notification draw: the measured rows plus the live
         * reading, cut to the trend window at the newest point, regressed in the view
         * mode's lane. Not [CurrentDisplaySource.Snapshot.rate], the alert engine's, which is
         * measured over the smoothed series.
         */
        fun dashboardRate(history: List<GlucosePoint>, snapshot: CurrentDisplaySource.Snapshot): Float =
            DisplayTrendSource.resolveArrowRate(
                DisplayTrendSource.resolveTrendPoints(history, snapshot, snapshot.sensorId),
                snapshot,
                snapshot.viewMode,
                snapshot.isMmol,
                Float.NaN,
            )
    }
}
