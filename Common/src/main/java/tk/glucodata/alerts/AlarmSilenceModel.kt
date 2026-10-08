package tk.glucodata.alerts

import java.nio.ByteBuffer
import java.nio.ByteOrder
import tk.glucodata.AlertDeliveryPolicy

/*
 * What silences an alarm, as phone and watch share it: a snooze per alert type, the
 * dismissal of an alarm's episode, and the quiet window. The runtime is
 * [AlarmSilenceSync]; everything here is pure, so the rules can be tested.
 *
 * Every time below is in the clock of the device holding the value. No time crosses the
 * wire as a date: [AlarmSilenceCodec] sends how long ago a change was made and how long
 * a snooze or window still has to run, and the receiver puts them back on its own clock.
 * A phone and a watch whose clocks disagree by seconds or by hours still agree on when a
 * snooze ends. What is left is the transit time of the message, which makes a received
 * change look that much more recent and a received snooze end that much later: seconds
 * at most, which the tolerances below absorb.
 *
 * Which change came last is decided by [ChangeId] where both devices send one: both
 * devices compute the same order from the ids alone, whatever the transit times, so
 * changes made in quick succession on either device, a state sent twice and a late
 * message are each ordered the same way on both. The received times decide only between
 * this build and one that sends no ids.
 */

/**
 * Which change an entry holds, and its place in the order both devices compute: a hybrid
 * logical clock reading ([hlcMs], [counter]) and [origin], a random id of the device that
 * made it. [hlcMs] is the later of that device's wall clock (epoch ms) and the highest
 * [hlcMs] it had made or seen when it made the change; [counter] orders the changes
 * within one [hlcMs] ([next]). A change made after seeing another is ordered after it,
 * and changes made without seeing each other by their wall clocks, then [counter], then
 * [origin] ([compareTo]).
 * Assumes network-synced clocks: with a skew both devices still keep one order, less true to real time.
 *
 * A change taken from the other device keeps its id. Null in an entry from a build that
 * does not send ids.
 */
data class ChangeId(val hlcMs: Long, val counter: Int, val origin: Int) : Comparable<ChangeId> {
    override fun compareTo(other: ChangeId): Int = when {
        hlcMs != other.hlcMs -> hlcMs.compareTo(other.hlcMs)
        counter != other.counter -> counter.compareTo(other.counter)
        else -> origin.compareTo(other.origin)
    }

    companion object {
        /**
         * The id of a change [origin] makes when its wall clock reads [wallMs] and the
         * highest id it made or saw is [last] (null: none): after both.
         */
        @JvmStatic
        fun next(last: ChangeId?, wallMs: Long, origin: Int): ChangeId =
            if (last == null || wallMs > last.hlcMs) {
                ChangeId(wallMs, 0, origin)
            } else {
                ChangeId(last.hlcMs, last.counter + 1, origin)
            }

        /** The highest id in [state]: a change made after receiving it goes above it. */
        @JvmStatic
        fun newest(state: SilenceState): ChangeId? =
            (state.snoozes.mapNotNull { it.id } + state.dismissals.mapNotNull { it.id } + listOfNotNull(state.quiet?.id))
                .maxOrNull()
    }
}

/**
 * One alert type's snooze as this device last changed it. [untilMs] is the end, 0 when
 * the change cancelled it; [changedAtMs] is when that change was made. A snooze whose
 * end has passed is no snooze ([activeAt]), but its change still counts as the latest.
 */
data class SnoozeEntry(
    val typeId: Int,
    val changedAtMs: Long,
    val untilMs: Long,
    val preemptive: Boolean,
    val id: ChangeId? = null,
) {
    fun activeAt(nowMs: Long): Boolean = untilMs > nowMs
}

/** The last dismissal of one alert type this device made, or took from the other one. */
data class DismissEntry(val typeId: Int, val dismissedAtMs: Long, val id: ChangeId? = null)

/**
 * The quiet window as last changed: [untilMs] 0 when that change ended it. The mode and
 * the breakthrough settings travel with it, so the other device silences the same way
 * and breaks through after the same time.
 */
data class QuietEntry(
    val changedAtMs: Long,
    val untilMs: Long,
    val mode: String,
    val breakthroughMinutes: Int,
    val breakthroughScope: String,
    val id: ChangeId? = null,
) {
    fun activeAt(nowMs: Long): Boolean = untilMs > nowMs
}

/** Everything one device holds, in its own clock. */
data class SilenceState(
    val snoozes: List<SnoozeEntry> = emptyList(),
    val dismissals: List<DismissEntry> = emptyList(),
    val quiet: QuietEntry? = null,
)

/** What applying a peer's state asks of this device; [AlarmSilenceSync] carries it out. */
sealed class SilenceAction {
    /** Snooze [typeId] until [untilMs], as a change made at [changedAtMs]. */
    data class Snooze(
        val typeId: Int,
        val untilMs: Long,
        val preemptive: Boolean,
        val minutes: Int,
        val changedAtMs: Long,
        val id: ChangeId? = null,
    ) : SilenceAction()

    /** End [typeId]'s snooze, as a change made at [changedAtMs]. */
    data class ClearSnooze(val typeId: Int, val changedAtMs: Long, val id: ChangeId? = null) : SilenceAction()

    /**
     * The peer's change is newer but leaves the same snooze (or none on both): only the
     * change time and id are taken, so an expired snooze is never applied and nothing reruns.
     */
    data class AdoptSnoozeTime(val typeId: Int, val changedAtMs: Long, val id: ChangeId? = null) : SilenceAction()

    /**
     * The peer dismissed [typeId] at [dismissedAtMs]. Whether that reaches an alarm here
     * is [AlarmSilencePolicy.dismissApplies], against this device's own alarm.
     */
    data class Dismiss(val typeId: Int, val dismissedAtMs: Long, val id: ChangeId? = null) : SilenceAction()

    data class StartQuiet(val entry: QuietEntry) : SilenceAction()
    data class EndQuiet(val changedAtMs: Long, val id: ChangeId? = null) : SilenceAction()
    data class AdoptQuietTime(val changedAtMs: Long, val id: ChangeId? = null) : SilenceAction()
}

/** The actions a peer's state asks for, and whether this device should answer with its own. */
data class SilenceReconciliation(val actions: List<SilenceAction>, val reply: Boolean)

/**
 * The rules.
 *
 *  - Snooze, per alert type, and the quiet window: the last change wins, on either
 *    device ([order]). A change that leaves no snooze (a cancel, or a snooze whose end has passed)
 *    is still a change: it ends an older snooze on the other device, and an expired
 *    snooze is never applied as one.
 *  - Dismissal: it reaches the other device's alarm of the same type only when that
 *    alarm started before the dismissal, give or take [DISMISS_START_TOLERANCE_MS]. A
 *    newer alarm keeps ringing, and a dismissal arriving after the alarm has ended does
 *    nothing.
 *  - Answers: a device answers a state with its own only when it holds something newer,
 *    and never answers an answer, so two devices settle in at most one round trip. Both
 *    end with the newest change: the device that made it sent it unasked, and a device
 *    holding a newer change than one it receives unasked answers with it.
 */
object AlarmSilencePolicy {
    /**
     * How far the transit time may move a received time. Between changes without ids,
     * two this close are the same change seen once more; snooze and window ends this
     * close are the same end.
     */
    const val SAME_CHANGE_TOLERANCE_MS = 5_000L

    /**
     * An alarm that started this long after a dismissal still counts as the one dismissed:
     * the other device usually fires a few seconds after this one, on the same reading.
     */
    const val DISMISS_START_TOLERANCE_MS = 15_000L

    /**
     * Older records are not sent. Nothing they decide is left by then: a snooze or a quiet
     * window lasts at most a day ([QuietWindow.MAX_DURATION_MS]), and a dismissal only
     * matters to an alarm that started before it and still rings.
     */
    const val RETENTION_MS = 26L * 60L * 60L * 1000L

    /** How one change stands to another: made after it, the same change, or made before it. */
    enum class Order { NEWER, SAME, OLDER }

    /**
     * How change a ([aChangedAtMs], [aId]) stands to change b; a missing b is older than
     * anything.
     *
     *  - Both with ids: the ids ([ChangeId.compareTo]), whatever the times say, which the
     *    transit time moves; both devices order any two changes the same way. Equal is
     *    the same change (sent again, or sent back).
     *  - Either without an id (an older build): the times, two within the tolerance
     *    being the same change.
     */
    @JvmStatic
    fun order(aChangedAtMs: Long, aId: ChangeId?, bChangedAtMs: Long?, bId: ChangeId?): Order {
        if (bChangedAtMs == null) return Order.NEWER
        if (aId != null && bId != null) return compare(aId.compareTo(bId).toLong(), 0L)
        return when {
            aChangedAtMs > bChangedAtMs + SAME_CHANGE_TOLERANCE_MS -> Order.NEWER
            bChangedAtMs > aChangedAtMs + SAME_CHANGE_TOLERANCE_MS -> Order.OLDER
            else -> Order.SAME
        }
    }

    /** Change a was made after change b ([order]); a missing b is older than anything. */
    @JvmStatic
    @JvmOverloads
    fun isNewer(aChangedAtMs: Long, bChangedAtMs: Long?, aId: ChangeId? = null, bId: ChangeId? = null): Boolean =
        order(aChangedAtMs, aId, bChangedAtMs, bId) == Order.NEWER

    private fun compare(a: Long, b: Long): Order = when {
        a > b -> Order.NEWER
        a < b -> Order.OLDER
        else -> Order.SAME
    }

    /** Whether [a] and [b] leave the same snooze at [nowMs]; a missing one is no snooze. */
    @JvmStatic
    fun sameSnooze(a: SnoozeEntry?, b: SnoozeEntry?, nowMs: Long): Boolean {
        if (a == null || b == null || !a.activeAt(nowMs) || !b.activeAt(nowMs)) {
            return (a?.activeAt(nowMs) == true) == (b?.activeAt(nowMs) == true)
        }
        return Math.abs(a.untilMs - b.untilMs) <= SAME_CHANGE_TOLERANCE_MS && a.preemptive == b.preemptive
    }

    /** Whether [a] and [b] leave the same quiet window at [nowMs]; a missing one is none. */
    @JvmStatic
    fun sameQuiet(a: QuietEntry?, b: QuietEntry?, nowMs: Long): Boolean {
        if (a == null || b == null || !a.activeAt(nowMs) || !b.activeAt(nowMs)) {
            return (a?.activeAt(nowMs) == true) == (b?.activeAt(nowMs) == true)
        }
        return Math.abs(a.untilMs - b.untilMs) <= SAME_CHANGE_TOLERANCE_MS &&
            AlertDeliveryPolicy.normalizeQuietMode(a.mode) == AlertDeliveryPolicy.normalizeQuietMode(b.mode)
    }

    /** Whether [from] would change what [to]'s holder has: newer, and not the same snooze. */
    @JvmStatic
    fun snoozeUpdates(from: SnoozeEntry?, to: SnoozeEntry?, nowMs: Long): Boolean =
        from != null && isNewer(from.changedAtMs, to?.changedAtMs, from.id, to?.id) && !sameSnooze(from, to, nowMs)

    @JvmStatic
    fun quietUpdates(from: QuietEntry?, to: QuietEntry?, nowMs: Long): Boolean =
        from != null && isNewer(from.changedAtMs, to?.changedAtMs, from.id, to?.id) && !sameQuiet(from, to, nowMs)

    @JvmStatic
    fun dismissalUpdates(from: DismissEntry?, to: DismissEntry?): Boolean =
        from != null && isNewer(from.dismissedAtMs, to?.dismissedAtMs, from.id, to?.id)

    /** The minutes a snooze was set for, for the alarm history: at least one. */
    @JvmStatic
    fun snoozeMinutes(changedAtMs: Long, untilMs: Long): Int =
        ((untilMs - changedAtMs + 30_000L) / 60_000L).coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()

    /**
     * What [incoming], a peer's state already on this device's clock, asks of this
     * device, which holds [local]. [incomingIsReply]: the peer sent it as an answer, which
     * is never answered.
     */
    @JvmStatic
    fun reconcile(
        local: SilenceState,
        incoming: SilenceState,
        incomingIsReply: Boolean,
        nowMs: Long,
    ): SilenceReconciliation {
        val actions = ArrayList<SilenceAction>()
        var reply = false

        val localSnoozes = local.snoozes.associateBy { it.typeId }
        val incomingSnoozes = newestSnoozes(incoming.snoozes)
        for ((typeId, theirs) in incomingSnoozes) {
            val ours = localSnoozes[typeId]
            if (!isNewer(theirs.changedAtMs, ours?.changedAtMs, theirs.id, ours?.id)) continue
            actions += when {
                sameSnooze(theirs, ours, nowMs) -> SilenceAction.AdoptSnoozeTime(typeId, theirs.changedAtMs, theirs.id)
                theirs.activeAt(nowMs) -> SilenceAction.Snooze(
                    typeId = typeId,
                    untilMs = theirs.untilMs,
                    preemptive = theirs.preemptive,
                    minutes = snoozeMinutes(theirs.changedAtMs, theirs.untilMs),
                    changedAtMs = theirs.changedAtMs,
                    id = theirs.id,
                )
                // Theirs is the later change and leaves no snooze: ours, older, ends.
                else -> SilenceAction.ClearSnooze(typeId, theirs.changedAtMs, theirs.id)
            }
        }
        if (local.snoozes.any { snoozeUpdates(it, incomingSnoozes[it.typeId], nowMs) }) reply = true

        val localDismissals = local.dismissals.associateBy { it.typeId }
        val incomingDismissals = newestDismissals(incoming.dismissals)
        for ((typeId, theirs) in incomingDismissals) {
            if (dismissalUpdates(theirs, localDismissals[typeId])) {
                actions += SilenceAction.Dismiss(typeId, theirs.dismissedAtMs, theirs.id)
            }
        }
        if (local.dismissals.any { dismissalUpdates(it, incomingDismissals[it.typeId]) }) reply = true

        val theirQuiet = incoming.quiet
        val ourQuiet = local.quiet
        if (theirQuiet != null && isNewer(theirQuiet.changedAtMs, ourQuiet?.changedAtMs, theirQuiet.id, ourQuiet?.id)) {
            actions += when {
                sameQuiet(theirQuiet, ourQuiet, nowMs) -> SilenceAction.AdoptQuietTime(theirQuiet.changedAtMs, theirQuiet.id)
                theirQuiet.activeAt(nowMs) -> SilenceAction.StartQuiet(theirQuiet)
                else -> SilenceAction.EndQuiet(theirQuiet.changedAtMs, theirQuiet.id)
            }
        }
        if (quietUpdates(ourQuiet, theirQuiet, nowMs)) reply = true

        return SilenceReconciliation(actions, reply && !incomingIsReply)
    }

    /**
     * Whether a dismissal made at [dismissedAtMs] reaches this device's alarm of the same
     * type, which started at [alarmStartedAtMs] (0: none is running) and may already be
     * [alreadyDismissed]. Only an alarm that started before the dismissal, within the
     * tolerance, is the one dismissed: a newer alarm keeps ringing.
     */
    @JvmStatic
    fun dismissApplies(dismissedAtMs: Long, alarmStartedAtMs: Long, alreadyDismissed: Boolean): Boolean =
        !alreadyDismissed && alarmStartedAtMs > 0L &&
            alarmStartedAtMs <= dismissedAtMs + DISMISS_START_TOLERANCE_MS

    /**
     * How long to wait before looking again for the alarm a dismissal is about, when none
     * was running yet: until the tolerance has run out (an alarm starting by then still
     * counts as the one dismissed), or -1 when it already has.
     */
    @JvmStatic
    fun dismissRecheckDelayMs(dismissedAtMs: Long, nowMs: Long): Long {
        val left = dismissedAtMs + DISMISS_START_TOLERANCE_MS - nowMs
        return if (left >= 0L) left else -1L
    }

    /** [state] without what is too old to send: see [RETENTION_MS]. */
    @JvmStatic
    fun retained(state: SilenceState, nowMs: Long): SilenceState {
        val oldest = nowMs - RETENTION_MS
        return SilenceState(
            snoozes = state.snoozes.filter { it.changedAtMs >= oldest },
            dismissals = state.dismissals.filter { it.dismissedAtMs >= oldest },
            quiet = state.quiet?.takeIf { it.changedAtMs >= oldest },
        )
    }

    private fun newestSnoozes(entries: List<SnoozeEntry>): Map<Int, SnoozeEntry> {
        val out = LinkedHashMap<Int, SnoozeEntry>()
        for (entry in entries) {
            val known = out[entry.typeId]
            if (known == null || isNewer(entry.changedAtMs, known.changedAtMs, entry.id, known.id)) out[entry.typeId] = entry
        }
        return out
    }

    private fun newestDismissals(entries: List<DismissEntry>): Map<Int, DismissEntry> {
        val out = LinkedHashMap<Int, DismissEntry>()
        for (entry in entries) {
            val known = out[entry.typeId]
            if (known == null || isNewer(entry.dismissedAtMs, known.dismissedAtMs, entry.id, known.id)) out[entry.typeId] = entry
        }
        return out
    }
}

/**
 * The `/sync2/silence` payload. Big-endian.
 *
 *     [u8 format = 1][u8 flags: bit 0 = an answer]
 *     then sections, each [u8 tag][u16 length][length bytes]:
 *       1 snoozes,    18 bytes each: [u8 alert type][i64 change age ms][i64 remaining ms][u8 bit 0 = preemptive]
 *       2 dismissals,  9 bytes each: [u8 alert type][i64 dismissal age ms]
 *       3 quiet window, one:         [i64 change age ms][i64 remaining ms][u8 breakthrough minutes]
 *                                    [u8 n][n bytes mode][u8 n][n bytes breakthrough scope]
 *       4 reserved, skipped
 *       5 change ids, 18 bytes each: [u8 section of the entry: 1, 2 or 3][u8 alert type, 0 for 3]
 *                                    [i64 hlc ms][i32 counter][i32 origin]
 *
 * An age is how long before sending the change was made; a remaining time is how long
 * the snooze or window still runs, 0 when the change left none. A reader skips the
 * sections it does not know, so a later build can add one without a new format, and
 * a payload it cannot read is dropped whole. An older build reads the entries without
 * their ids (section 5); an entry without an id record has none ([ChangeId]).
 */
object AlarmSilenceCodec {
    const val FORMAT = 1
    private const val FLAG_REPLY = 0x01
    private const val FLAG_PREEMPTIVE = 0x01

    const val SECTION_SNOOZES = 1
    const val SECTION_DISMISSALS = 2
    const val SECTION_QUIET = 3
    const val SECTION_IDS = 5

    private const val SNOOZE_BYTES = 1 + 8 + 8 + 1
    private const val DISMISS_BYTES = 1 + 8
    private const val ID_BYTES = 1 + 1 + 8 + 4 + 4

    /** Nothing still running ends further out than this: the quiet window's cap, and a margin. */
    const val MAX_REMAINING_MS = 25L * 60L * 60L * 1000L

    /** Nothing older is sent ([AlarmSilencePolicy.RETENTION_MS]); a larger age is not a real one. */
    const val MAX_AGE_MS = 7L * 24L * 60L * 60L * 1000L

    data class Message(val state: SilenceState, val reply: Boolean)

    /** [state], held on a clock reading [nowMs], as ages and remaining times. */
    @JvmStatic
    fun encode(state: SilenceState, nowMs: Long, reply: Boolean): ByteArray {
        val snoozes = state.snoozes.filter { it.typeId in 0..255 }
        val dismissals = state.dismissals.filter { it.typeId in 0..255 }
        val quiet = state.quiet?.let { encodeQuiet(it, nowMs) }
        val ids = ArrayList<Triple<Int, Int, ChangeId>>()
        snoozes.forEach { entry -> entry.id?.let { ids += Triple(SECTION_SNOOZES, entry.typeId, it) } }
        dismissals.forEach { entry -> entry.id?.let { ids += Triple(SECTION_DISMISSALS, entry.typeId, it) } }
        state.quiet?.id?.let { ids += Triple(SECTION_QUIET, 0, it) }
        var size = 2
        if (snoozes.isNotEmpty()) size += 3 + snoozes.size * SNOOZE_BYTES
        if (dismissals.isNotEmpty()) size += 3 + dismissals.size * DISMISS_BYTES
        if (quiet != null) size += 3 + quiet.size
        if (ids.isNotEmpty()) size += 3 + ids.size * ID_BYTES
        val out = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)
        out.put(FORMAT.toByte())
        out.put((if (reply) FLAG_REPLY else 0).toByte())
        if (snoozes.isNotEmpty()) {
            out.put(SECTION_SNOOZES.toByte())
            out.putShort((snoozes.size * SNOOZE_BYTES).toShort())
            for (entry in snoozes) {
                out.put(entry.typeId.toByte())
                out.putLong(age(entry.changedAtMs, nowMs))
                out.putLong(remaining(entry.untilMs, nowMs))
                out.put((if (entry.preemptive) FLAG_PREEMPTIVE else 0).toByte())
            }
        }
        if (dismissals.isNotEmpty()) {
            out.put(SECTION_DISMISSALS.toByte())
            out.putShort((dismissals.size * DISMISS_BYTES).toShort())
            for (entry in dismissals) {
                out.put(entry.typeId.toByte())
                out.putLong(age(entry.dismissedAtMs, nowMs))
            }
        }
        if (quiet != null) {
            out.put(SECTION_QUIET.toByte())
            out.putShort(quiet.size.toShort())
            out.put(quiet)
        }
        if (ids.isNotEmpty()) {
            out.put(SECTION_IDS.toByte())
            out.putShort((ids.size * ID_BYTES).toShort())
            for ((section, typeId, id) in ids) {
                out.put(section.toByte())
                out.put(typeId.toByte())
                out.putLong(id.hlcMs)
                out.putInt(id.counter)
                out.putInt(id.origin)
            }
        }
        return out.array()
    }

    /**
     * The state in [data], put on the receiver's clock reading [nowMs], or null when it is
     * not a payload this build can read. Never throws.
     */
    @JvmStatic
    fun decode(data: ByteArray?, nowMs: Long): Message? {
        if (data == null || data.size < 2) return null
        return try {
            val buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
            val format = buffer.get().toInt() and 0xff
            if (format < 1) return null
            val flags = buffer.get().toInt() and 0xff
            val snoozes = ArrayList<SnoozeEntry>()
            val dismissals = ArrayList<DismissEntry>()
            var quiet: QuietEntry? = null
            val ids = HashMap<Pair<Int, Int>, ChangeId>()
            while (buffer.hasRemaining()) {
                if (buffer.remaining() < 3) return null
                val tag = buffer.get().toInt() and 0xff
                val length = buffer.short.toInt() and 0xffff
                if (length > buffer.remaining()) return null
                val section = ByteArray(length)
                buffer.get(section)
                val body = ByteBuffer.wrap(section).order(ByteOrder.BIG_ENDIAN)
                when (tag) {
                    SECTION_SNOOZES -> {
                        if (length % SNOOZE_BYTES != 0) return null
                        repeat(length / SNOOZE_BYTES) {
                            val typeId = body.get().toInt() and 0xff
                            val ageMs = body.long
                            val remainingMs = body.long
                            val preemptive = (body.get().toInt() and FLAG_PREEMPTIVE) != 0
                            if (plausibleAge(ageMs) && plausibleRemaining(remainingMs)) {
                                snoozes += SnoozeEntry(
                                    typeId = typeId,
                                    changedAtMs = nowMs - ageMs,
                                    untilMs = if (remainingMs > 0L) nowMs + remainingMs else 0L,
                                    preemptive = preemptive,
                                )
                            }
                        }
                    }
                    SECTION_DISMISSALS -> {
                        if (length % DISMISS_BYTES != 0) return null
                        repeat(length / DISMISS_BYTES) {
                            val typeId = body.get().toInt() and 0xff
                            val ageMs = body.long
                            if (plausibleAge(ageMs)) dismissals += DismissEntry(typeId, nowMs - ageMs)
                        }
                    }
                    SECTION_QUIET -> quiet = decodeQuiet(body, nowMs)
                    SECTION_IDS -> {
                        if (length % ID_BYTES != 0) return null
                        repeat(length / ID_BYTES) {
                            val section = body.get().toInt() and 0xff
                            val typeId = body.get().toInt() and 0xff
                            val hlcMs = body.long
                            val counter = body.int
                            val origin = body.int
                            if (hlcMs > 0L && counter >= 0) ids[section to typeId] = ChangeId(hlcMs, counter, origin)
                        }
                    }
                    else -> Unit // a later build's section
                }
            }
            val state = SilenceState(
                snoozes = snoozes.map { it.copy(id = ids[SECTION_SNOOZES to it.typeId]) },
                dismissals = dismissals.map { it.copy(id = ids[SECTION_DISMISSALS to it.typeId]) },
                quiet = quiet?.copy(id = ids[SECTION_QUIET to 0]),
            )
            Message(state, reply = (flags and FLAG_REPLY) != 0)
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun encodeQuiet(entry: QuietEntry, nowMs: Long): ByteArray {
        val mode = entry.mode.toByteArray(Charsets.UTF_8).take(255).toByteArray()
        val scope = entry.breakthroughScope.toByteArray(Charsets.UTF_8).take(255).toByteArray()
        val out = ByteBuffer.allocate(8 + 8 + 1 + 1 + mode.size + 1 + scope.size).order(ByteOrder.BIG_ENDIAN)
        out.putLong(age(entry.changedAtMs, nowMs))
        out.putLong(remaining(entry.untilMs, nowMs))
        out.put(entry.breakthroughMinutes.coerceIn(0, 255).toByte())
        out.put(mode.size.toByte())
        out.put(mode)
        out.put(scope.size.toByte())
        out.put(scope)
        return out.array()
    }

    private fun decodeQuiet(body: ByteBuffer, nowMs: Long): QuietEntry? {
        val ageMs = body.long
        val remainingMs = body.long
        val breakthroughMinutes = body.get().toInt() and 0xff
        val mode = ByteArray(body.get().toInt() and 0xff).also { body.get(it) }
        val scope = ByteArray(body.get().toInt() and 0xff).also { body.get(it) }
        if (!plausibleAge(ageMs) || !plausibleRemaining(remainingMs)) return null
        return QuietEntry(
            changedAtMs = nowMs - ageMs,
            untilMs = if (remainingMs > 0L) nowMs + remainingMs else 0L,
            mode = String(mode, Charsets.UTF_8),
            breakthroughMinutes = breakthroughMinutes,
            breakthroughScope = String(scope, Charsets.UTF_8),
        )
    }

    /** A clock that stepped back makes a change look as if it is still to come: it is now. */
    private fun age(changedAtMs: Long, nowMs: Long): Long = (nowMs - changedAtMs).coerceAtLeast(0L)

    private fun remaining(untilMs: Long, nowMs: Long): Long =
        if (untilMs > nowMs) untilMs - nowMs else 0L

    private fun plausibleAge(ageMs: Long): Boolean = ageMs in 0L..MAX_AGE_MS

    private fun plausibleRemaining(remainingMs: Long): Boolean = remainingMs in 0L..MAX_REMAINING_MS
}
