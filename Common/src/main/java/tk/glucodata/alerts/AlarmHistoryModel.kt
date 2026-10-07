package tk.glucodata.alerts

import java.util.Calendar
import java.util.TimeZone
import tk.glucodata.WearProtocol

/**
 * The alarm history: what rang, where, with which value, and how it ended.
 *
 * Everything in this file is plain logic with no Android in it, so the rules are
 * unit-tested as they stand; [AlarmHistory] is the thin runtime around them.
 */

/** Which device rang. The code is what travels on the wire and on disk. */
enum class AlarmDevice(val code: Char) {
    PHONE('p'),
    WATCH('w');

    companion object {
        fun fromCode(code: String): AlarmDevice? =
            if (code.length == 1) entries.firstOrNull { it.code == code[0] } else null
    }
}

/**
 * How an alarm ended. One per event; once an event is closed its outcome never
 * changes, so the first of these to happen is the one recorded.
 */
enum class AlarmOutcome(val code: Char) {
    /** Not over yet: nobody has acted and the condition has not cleared. */
    ACTIVE('a'),

    /** Somebody dismissed it: alarm screen, notification action, or opening the notification. */
    DISMISSED('d'),

    /** Somebody snoozed it; [AlarmEvent.snoozeMinutes] says for how long. */
    SNOOZED('s'),

    /**
     * Nobody acted, and the condition that raised it cleared on its own: the value
     * came back past the threshold, a reading arrived, the forecast no longer held.
     */
    RECOVERED('r'),

    /**
     * Nobody acted, and it stopped some other way: the same alarm fired again on the
     * same device (superseded), it was switched off or left its active hours, or it
     * stayed unanswered for [AlarmHistoryPolicy.OPEN_TIMEOUT_MS] (timed out).
     */
    ENDED('e');

    companion object {
        /**
         * An unknown code comes from a newer build that closed the event for a reason
         * this one cannot name. It is still closed, so it reads as [ENDED].
         */
        fun fromCode(code: String): AlarmOutcome =
            if (code.length == 1) entries.firstOrNull { it.code == code[0] } ?: ENDED else ENDED
    }
}

data class AlarmEvent(
    /** Unique per device: device code, fire time and alert id. See [AlarmHistoryPolicy.newId]. */
    val id: String,
    val firedAtMs: Long,
    /** Any alert id, known to this build or not ([AlertType.id]). */
    val alertTypeId: Int,
    /** The value the alarm showed, in [unit]; NaN when it carried none (sensor expiry, signal loss). */
    val value: Float,
    /** The unit the value was displayed in: 1 = mmol/L, anything else mg/dL (as `Applic.unit`). */
    val unit: Int,
    val device: AlarmDevice,
    val outcome: AlarmOutcome = AlarmOutcome.ACTIVE,
    /** Minutes, for [AlarmOutcome.SNOOZED] only. */
    val snoozeMinutes: Int = 0,
    /** When it was closed; 0 while [AlarmOutcome.ACTIVE]. */
    val endedAtMs: Long = 0L,
    /** Watch only, never on the wire: this state of the event has not reached the phone yet. */
    val pendingDelivery: Boolean = false,
) {
    val isOpen: Boolean get() = outcome == AlarmOutcome.ACTIVE
}

object AlarmHistoryPolicy {
    /** Kept for this long, on the phone. */
    const val RETENTION_MS = 30L * 24L * 60L * 60L * 1000L

    /** An event nobody answered and nothing resolved is closed as timed out after this. */
    const val OPEN_TIMEOUT_MS = 6L * 60L * 60L * 1000L

    /** A ceiling under the 30 days, so a misbehaving alert cannot grow the file without bound. */
    const val PHONE_MAX_EVENTS = 3000

    /** The watch's buffer: events it still has to deliver, plus the open ones it may still close. */
    const val WATCH_MAX_EVENTS = 100

    fun newId(device: AlarmDevice, alertTypeId: Int, firedAtMs: Long): String =
        "${device.code}$firedAtMs-$alertTypeId"

    /**
     * Holds: the alarm is paused but its episode goes on (a persistent high falling
     * fast, a persistent low rising or covered by a very low). The event stays open.
     */
    private val HOLD_REASONS = setOf(
        "persistent-high-falling",
        "persistent-low-rising",
        "persistent-low-very-low",
    )

    /**
     * What a clear from the alert runtime means for an open event; null for a hold,
     * which leaves it open. Switching off, leaving the active hours and losing the
     * sensor end time stop the alarm without anything having recovered; every other
     * reason is the condition itself clearing.
     */
    fun outcomeForClearReason(reason: String?): AlarmOutcome? {
        val r = reason ?: return AlarmOutcome.ENDED
        if (r in HOLD_REASONS) return null
        if (r.endsWith("-disabled") || r.endsWith("-time-inactive") ||
            r.endsWith("-no-endtime") || r == "sensor-handover-window"
        ) {
            return AlarmOutcome.ENDED
        }
        return AlarmOutcome.RECOVERED
    }

    /**
     * A new alarm. An open event of the same alert on the same device is superseded:
     * it is closed as [AlarmOutcome.ENDED], so one alert never has two open events.
     */
    fun onFired(events: List<AlarmEvent>, fired: AlarmEvent, nowMs: Long): List<AlarmEvent> {
        val closed = events.map { event ->
            if (event.isOpen && event.device == fired.device && event.alertTypeId == fired.alertTypeId) {
                closeEvent(event, AlarmOutcome.ENDED, 0, nowMs)
            } else {
                event
            }
        }
        return (closed.filterNot { it.id == fired.id } + fired).sortedBy { it.firedAtMs }
    }

    /**
     * Closes the newest open event of [alertTypeId] on [device]. Returns the list and the
     * closed event, or the list unchanged and null when nothing of that alert is open
     * (a snooze from the settings, a second dismiss): there is no alarm to attribute it to.
     */
    fun close(
        events: List<AlarmEvent>,
        alertTypeId: Int,
        device: AlarmDevice,
        outcome: AlarmOutcome,
        snoozeMinutes: Int,
        nowMs: Long,
    ): Pair<List<AlarmEvent>, AlarmEvent?> {
        if (outcome == AlarmOutcome.ACTIVE) return events to null
        val target = events
            .filter { it.isOpen && it.device == device && it.alertTypeId == alertTypeId }
            .maxByOrNull { it.firedAtMs }
            ?: return events to null
        val closed = closeEvent(target, outcome, snoozeMinutes, nowMs)
        return events.map { if (it.id == target.id) closed else it } to closed
    }

    private fun closeEvent(event: AlarmEvent, outcome: AlarmOutcome, snoozeMinutes: Int, endedAtMs: Long) =
        event.copy(
            outcome = outcome,
            snoozeMinutes = if (outcome == AlarmOutcome.SNOOZED) snoozeMinutes.coerceAtLeast(0) else 0,
            endedAtMs = endedAtMs.coerceAtLeast(event.firedAtMs),
            // The watch owes the phone this new state.
            pendingDelivery = event.device == AlarmDevice.WATCH,
        )

    /**
     * Phone: merges what a watch reported. One event per id; a closed state beats an
     * open one, so an older report arriving late cannot reopen an alarm.
     */
    fun upsert(events: List<AlarmEvent>, incoming: List<AlarmEvent>): List<AlarmEvent> {
        if (incoming.isEmpty()) return events
        val byId = LinkedHashMap<String, AlarmEvent>()
        events.forEach { byId[it.id] = it }
        incoming.forEach { raw ->
            val event = raw.copy(pendingDelivery = false)
            val existing = byId[event.id]
            if (existing == null || !(event.isOpen && !existing.isOpen)) {
                byId[event.id] = event
            }
        }
        return byId.values.sortedBy { it.firedAtMs }
    }

    /**
     * Applies the time rules: an event open for longer than [OPEN_TIMEOUT_MS] is closed
     * as timed out, anything fired before the retention window goes, and at most
     * [maxEvents] of the newest stay.
     */
    fun prune(events: List<AlarmEvent>, nowMs: Long, maxEvents: Int): List<AlarmEvent> {
        val oldest = nowMs - RETENTION_MS
        val kept = events
            .filter { it.firedAtMs >= oldest }
            .map { event ->
                if (event.isOpen && nowMs - event.firedAtMs >= OPEN_TIMEOUT_MS) {
                    closeEvent(event, AlarmOutcome.ENDED, 0, event.firedAtMs + OPEN_TIMEOUT_MS)
                } else {
                    event
                }
            }
            .sortedBy { it.firedAtMs }
        return if (kept.size > maxEvents) kept.takeLast(maxEvents.coerceAtLeast(0)) else kept
    }

    /**
     * The watch's buffer bound. A closed event the phone already has is of no further use
     * on the watch, so it goes; past [max] the oldest go, whatever their state, so a watch
     * away from its phone for weeks keeps the latest alarms rather than the first.
     */
    fun boundWatchBuffer(events: List<AlarmEvent>, max: Int = WATCH_MAX_EVENTS): List<AlarmEvent> {
        val useful = events.filter { it.isOpen || it.pendingDelivery }.sortedBy { it.firedAtMs }
        return if (useful.size > max) useful.takeLast(max.coerceAtLeast(0)) else useful
    }

    /**
     * Watch: the phone took [sent]. Only an event still exactly as it was sent is marked
     * delivered; one that changed in the meantime (an outcome recorded during the send)
     * stays pending, so its new state goes out next.
     */
    fun acknowledge(events: List<AlarmEvent>, sent: List<AlarmEvent>): List<AlarmEvent> {
        if (sent.isEmpty()) return events
        val sentById = sent.associateBy { it.id }
        return events.map { event ->
            val asSent = sentById[event.id]
            if (asSent != null && event.pendingDelivery && event == asSent) {
                event.copy(pendingDelivery = false)
            } else {
                event
            }
        }
    }

    /** Newest first, grouped by local day; the key is the day's local midnight. */
    fun groupByDay(
        events: List<AlarmEvent>,
        zone: TimeZone = TimeZone.getDefault(),
    ): List<Pair<Long, List<AlarmEvent>>> {
        val calendar = Calendar.getInstance(zone)
        return events
            .sortedByDescending { it.firedAtMs }
            .groupBy { event ->
                calendar.timeInMillis = event.firedAtMs
                calendar.set(Calendar.HOUR_OF_DAY, 0)
                calendar.set(Calendar.MINUTE, 0)
                calendar.set(Calendar.SECOND, 0)
                calendar.set(Calendar.MILLISECOND, 0)
                calendar.timeInMillis
            }
            .toList()
    }
}

/**
 * The text form of an event, for the watch-to-phone message and for the file each
 * device keeps.
 *
 * Message (UTF-8), one event per line:
 *
 *     v:1
 *     e1|<id>|<firedAtMs>|<alertTypeId>|<value>|<unit>|<device>|<outcome>|<snoozeMinutes>|<endedAtMs>
 *
 * The first line is [WearProtocol.versionLine]; a message from a newer protocol is
 * ignored whole. `e1` versions the line on its own: a line in another format is
 * skipped, and fields appended after the tenth are ignored, so a later build can add
 * either without breaking this one. `value` is `NaN` when the alarm carried none;
 * `device` is `p`/`w` and `outcome` one of `a d s r e` ([AlarmOutcome]).
 *
 * On disk the line carries one more field, the watch's "not yet delivered" flag.
 */
object AlarmHistoryCodec {
    const val LINE_FORMAT = "e1"

    /** Events per message: the watch's whole buffer fits in one. */
    const val MAX_EVENTS_PER_MESSAGE = AlarmHistoryPolicy.WATCH_MAX_EVENTS

    /** A message carrying more lines than this is not one this protocol produces. */
    private const val MAX_DECODED_LINES = 1000

    private const val MAX_ID_LENGTH = 64
    private const val MAX_SNOOZE_MINUTES = 7 * 24 * 60
    private const val SEP = '|'
    private val ID_PATTERN = Regex("[A-Za-z0-9_.-]+")

    fun encodeLine(event: AlarmEvent, withLocalState: Boolean = false): String = buildString {
        append(LINE_FORMAT)
        append(SEP).append(event.id)
        append(SEP).append(event.firedAtMs)
        append(SEP).append(event.alertTypeId)
        append(SEP).append(if (event.value.isFinite()) event.value.toString() else "NaN")
        append(SEP).append(event.unit)
        append(SEP).append(event.device.code)
        append(SEP).append(event.outcome.code)
        append(SEP).append(event.snoozeMinutes)
        append(SEP).append(event.endedAtMs)
        if (withLocalState) {
            append(SEP).append(if (event.pendingDelivery) '1' else '0')
        }
    }

    /** One line, or null for a line in another format or with an unusable field. */
    fun decodeLine(line: String, withLocalState: Boolean = false): AlarmEvent? {
        if (!line.startsWith(LINE_FORMAT + SEP)) return null
        val fields = line.trimEnd('\r').split(SEP)
        if (fields.size < 10) return null
        val id = fields[1].takeIf { it.length in 1..MAX_ID_LENGTH && ID_PATTERN.matches(it) } ?: return null
        val firedAtMs = fields[2].toLongOrNull()?.takeIf { it > 0L } ?: return null
        val alertTypeId = fields[3].toIntOrNull()?.takeIf { it >= 0 } ?: return null
        val value = fields[4].toFloatOrNull()?.takeIf { it.isFinite() } ?: Float.NaN
        val unit = fields[5].toIntOrNull() ?: return null
        val device = AlarmDevice.fromCode(fields[6]) ?: return null
        val outcome = AlarmOutcome.fromCode(fields[7])
        val snoozeMinutes = if (outcome == AlarmOutcome.SNOOZED) {
            fields[8].toIntOrNull()?.coerceIn(0, MAX_SNOOZE_MINUTES) ?: 0
        } else {
            0
        }
        val endedAtMs = if (outcome == AlarmOutcome.ACTIVE) 0L else fields[9].toLongOrNull()?.coerceAtLeast(0L) ?: 0L
        val pending = withLocalState && fields.getOrNull(10) == "1"
        return AlarmEvent(
            id = id,
            firedAtMs = firedAtMs,
            alertTypeId = alertTypeId,
            value = value,
            unit = unit,
            device = device,
            outcome = outcome,
            snoozeMinutes = snoozeMinutes,
            endedAtMs = endedAtMs,
            pendingDelivery = pending,
        )
    }

    fun encodeMessage(events: List<AlarmEvent>): ByteArray = buildString {
        append(WearProtocol.versionLine()).append('\n')
        events.take(MAX_EVENTS_PER_MESSAGE).forEach { append(encodeLine(it)).append('\n') }
    }.toByteArray(Charsets.UTF_8)

    /** The events a message carries; nothing for an empty, unreadable or newer-protocol one. */
    fun decodeMessage(data: ByteArray?): List<AlarmEvent> {
        if (data == null || data.isEmpty()) return emptyList()
        val text = runCatching { data.toString(Charsets.UTF_8) }.getOrNull() ?: return emptyList()
        if (!WearProtocol.accepts(WearProtocol.declaredVersion(text))) return emptyList()
        return text.lineSequence()
            .take(MAX_DECODED_LINES)
            .mapNotNull { decodeLine(it) }
            .toList()
    }

    fun encodeStore(events: List<AlarmEvent>): String =
        events.joinToString(separator = "") { encodeLine(it, withLocalState = true) + "\n" }

    fun decodeStore(text: String?): List<AlarmEvent> =
        text?.lineSequence()?.mapNotNull { decodeLine(it, withLocalState = true) }?.toList().orEmpty()
}

/** Time left on a snooze, as the screens show it. */
object SnoozeTimeLeft {
    private const val MINUTE_MS = 60_000L

    /** How often a screen showing no snooze looks again, so a new one appears. */
    const val IDLE_REFRESH_MS = 15_000L

    /**
     * Whole minutes left, rounded up: ten seconds to go reads "1 min", never "0 min",
     * and a fresh 15-minute snooze reads 15. Zero once it has run out.
     */
    fun minutesLeft(snoozeUntilMs: Long, nowMs: Long): Int {
        val leftMs = snoozeUntilMs - nowMs
        if (leftMs <= 0L) return 0
        return ((leftMs + MINUTE_MS - 1L) / MINUTE_MS).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    /** (hours, minutes) for display. */
    fun split(minutes: Int): Pair<Int, Int> {
        val safe = minutes.coerceAtLeast(0)
        return (safe / 60) to (safe % 60)
    }

    /**
     * When to look again: as the soonest displayed minute count changes, never later than
     * [IDLE_REFRESH_MS] (a snooze set elsewhere then shows up), never sooner than a second.
     */
    fun refreshDelayMs(snoozeUntilMs: List<Long>, nowMs: Long): Long {
        val nextChange = snoozeUntilMs
            .map { it - nowMs }
            .filter { it > 0L }
            .minOfOrNull { left -> (left % MINUTE_MS).let { if (it == 0L) MINUTE_MS else it } }
            ?: IDLE_REFRESH_MS
        return nextChange.coerceIn(1_000L, IDLE_REFRESH_MS)
    }
}
