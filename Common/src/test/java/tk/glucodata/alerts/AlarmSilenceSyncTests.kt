package tk.glucodata.alerts

import java.io.File
import java.nio.ByteBuffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.AlertDeliveryPolicy
import tk.glucodata.WearMessagePath
import tk.glucodata.WearProtocol

/**
 * Snoozes, dismissals and the quiet window shared between phone and watch: the wire
 * format, the reconcile rules (last change wins, expired snoozes are never applied),
 * the dismissal's episode guard, clocks that disagree, answers that end, and the
 * wiring that keeps a change from the other device from being sent back.
 */
class AlarmSilenceSyncTests {

    private val t0 = 1_760_000_000_000L
    private val second = 1_000L
    private val minute = 60_000L
    private val hour = 60L * minute

    private val low = AlertType.LOW.id
    private val high = AlertType.HIGH.id

    @After
    fun cleanup() {
        AlertType.entries.forEach(AlertStateTracker::resetState)
    }

    // ------------------------------------------------------------ wire format

    @Test
    fun timesArriveOnTheReceiversClockWhateverTheSkew() {
        val state = SilenceState(
            snoozes = listOf(SnoozeEntry(low, t0 - 2 * minute, t0 + 58 * minute, preemptive = true)),
            dismissals = listOf(DismissEntry(high, t0 - 30 * second)),
            quiet = QuietEntry(t0 - 10 * second, t0 + 50 * minute, AlertDeliveryPolicy.QUIET_NOTIFICATION_ONLY, 7, AlertDeliveryPolicy.BREAKTHROUGH_VERY_ONLY),
        )
        val bytes = AlarmSilenceCodec.encode(state, t0, reply = false)
        for (skew in listOf(0L, 7 * second, -7 * second, 3 * hour + 7 * second, -26 * hour)) {
            val receiverNow = t0 + skew
            val message = AlarmSilenceCodec.decode(bytes, receiverNow)!!
            val snooze = message.state.snoozes.single()
            assertEquals(receiverNow - 2 * minute, snooze.changedAtMs)
            assertEquals(receiverNow + 58 * minute, snooze.untilMs)
            assertTrue(snooze.preemptive)
            assertEquals(receiverNow - 30 * second, message.state.dismissals.single().dismissedAtMs)
            val quiet = message.state.quiet!!
            assertEquals(receiverNow - 10 * second, quiet.changedAtMs)
            assertEquals(receiverNow + 50 * minute, quiet.untilMs)
            assertEquals(AlertDeliveryPolicy.QUIET_NOTIFICATION_ONLY, quiet.mode)
            assertEquals(7, quiet.breakthroughMinutes)
            assertEquals(AlertDeliveryPolicy.BREAKTHROUGH_VERY_ONLY, quiet.breakthroughScope)
            assertFalse(message.reply)
        }
    }

    @Test
    fun aCancelAndAnExpiredSnoozeBothTravelAsNoSnooze() {
        val state = SilenceState(
            snoozes = listOf(
                SnoozeEntry(low, t0 - minute, 0L, false),
                SnoozeEntry(high, t0 - hour, t0 - minute, false),
            ),
        )
        val decoded = AlarmSilenceCodec.decode(AlarmSilenceCodec.encode(state, t0, reply = true), t0 + 5 * hour)!!
        assertTrue(decoded.reply)
        assertEquals(listOf(0L, 0L), decoded.state.snoozes.map { it.untilMs })
        assertEquals(listOf(t0 + 5 * hour - minute, t0 + 5 * hour - hour), decoded.state.snoozes.map { it.changedAtMs })
    }

    /** The layout, byte for byte: changing it is a protocol change, not a refactor. */
    @Test
    fun theWireLayoutIsPinned() {
        val state = SilenceState(
            snoozes = listOf(SnoozeEntry(5, t0 - 1_000L, t0 + 2_000L, preemptive = true)),
            dismissals = listOf(DismissEntry(1, t0 - 3_000L)),
            quiet = QuietEntry(t0 - 4_000L, t0 + 5_000L, "vibrate_only", 10, "all"),
        )
        val expected = ByteBuffer.allocate(2 + 3 + 18 + 3 + 9 + 3 + 8 + 8 + 1 + 1 + 12 + 1 + 3)
            .put(1).put(0)
            .put(1).putShort(18).put(5).putLong(1_000L).putLong(2_000L).put(1)
            .put(2).putShort(9).put(1).putLong(3_000L)
            .put(3).putShort((8 + 8 + 1 + 1 + 12 + 1 + 3).toShort()).putLong(4_000L).putLong(5_000L).put(10)
            .put(12).put("vibrate_only".toByteArray()).put(3).put("all".toByteArray())
            .array()
        assertArrayEquals(expected, AlarmSilenceCodec.encode(state, t0, reply = false))
        assertEquals(2, AlarmSilenceCodec.encode(SilenceState(), t0, reply = false).size)
    }

    @Test
    fun anUnreadablePayloadIsDroppedWhole() {
        val good = AlarmSilenceCodec.encode(
            SilenceState(snoozes = listOf(SnoozeEntry(low, t0, t0 + hour, false))), t0, reply = false
        )
        val unreadable = listOf(
            null,
            byteArrayOf(),
            byteArrayOf(1),
            byteArrayOf(0, 0), // format 0
            good.copyOf(good.size - 1), // section shorter than its length
            byteArrayOf(1, 0, 1, 0), // section header cut short
            byteArrayOf(1, 0, 1, 0, 3, 0, 0, 0), // snooze section not a whole record
            byteArrayOf(1, 0, 3, 0, 2, 0, 0), // quiet section cut short
        )
        unreadable.forEach { assertNull("payload ${it?.toList()}", AlarmSilenceCodec.decode(it, t0)) }
        assertNotNull(AlarmSilenceCodec.decode(good, t0))
    }

    /** A later build may add a section, or a format, without this build dropping what it can read. */
    @Test
    fun sectionsThisBuildDoesNotKnowAreSkipped() {
        val known = AlarmSilenceCodec.encode(
            SilenceState(dismissals = listOf(DismissEntry(low, t0 - minute))), t0, reply = false
        )
        val later = ByteBuffer.allocate(known.size + 3 + 4)
            .put(2).put(0) // format 2
            .put(9).putShort(4).putInt(0x7eadbeef) // a section from the future
            .put(known, 2, known.size - 2)
            .array()
        val decoded = AlarmSilenceCodec.decode(later, t0)!!
        assertEquals(listOf(DismissEntry(low, t0 - minute)), decoded.state.dismissals)
    }

    @Test
    fun anImplausibleRecordIsDroppedAndTheRestKept() {
        val bytes = ByteBuffer.allocate(2 + 3 + 3 * 18)
            .put(1).put(0)
            .put(1).putShort((3 * 18).toShort())
            .put(low.toByte()).putLong(-1L).putLong(minute).put(0) // change "in the future"
            .put(high.toByte()).putLong(minute).putLong(AlarmSilenceCodec.MAX_REMAINING_MS + 1).put(0) // runs past a day
            .put(AlertType.VERY_LOW.id.toByte()).putLong(minute).putLong(hour).put(0)
            .array()
        val decoded = AlarmSilenceCodec.decode(bytes, t0)!!
        assertEquals(listOf(AlertType.VERY_LOW.id), decoded.state.snoozes.map { it.typeId })
    }

    // ------------------------------------------------------------ old builds

    @Test
    fun anOlderBuildDropsTheNewPathAndTheProtocolVersionStays() {
        assertEquals("a bump makes older peers drop every managed message", 1, WearProtocol.VERSION)
        val path = WearMessagePath.SYNC2_SILENCE.wire
        assertEquals("/sync2/silence", path)
        // Both manifests already deliver /sync2 (WearMessagePathManifestTests checks the
        // prefixes); an older receiver resolves the path to null and ignores it.
        assertTrue(path.startsWith("/sync2/"))
        val olderPaths = WearMessagePath.entries.filter { it != WearMessagePath.SYNC2_SILENCE }.map { it.wire }
        assertFalse("not a path an older build acts on", path in olderPaths)
        assertEquals(WearMessagePath.SYNC2_SILENCE, WearMessagePath.fromWire(path))
    }

    // ------------------------------------------------------------ snooze: last change wins

    private fun local(vararg snoozes: SnoozeEntry) = SilenceState(snoozes = snoozes.toList())

    @Test
    fun aNewerSnoozeFromTheOtherDeviceIsApplied() {
        val result = AlarmSilencePolicy.reconcile(
            local = SilenceState(),
            incoming = local(SnoozeEntry(low, t0 - minute, t0 + 59 * minute, preemptive = true)),
            incomingIsReply = false,
            nowMs = t0,
        )
        assertEquals(
            listOf(SilenceAction.Snooze(low, t0 + 59 * minute, preemptive = true, minutes = 60, changedAtMs = t0 - minute)),
            result.actions,
        )
        assertFalse(result.reply)
    }

    @Test
    fun anOlderChangeOrTheSameOneSeenAgainKeepsWhatIsHere() {
        val ours = SnoozeEntry(low, t0 - minute, t0 + 29 * minute, false)
        val older = SnoozeEntry(low, t0 - 10 * minute, t0 + hour, false)
        val sameWithinTransit = SnoozeEntry(low, t0 - minute + 4 * second, t0 + 29 * minute + 4 * second, false)
        for (theirs in listOf(older, sameWithinTransit)) {
            val result = AlarmSilencePolicy.reconcile(local(ours), local(theirs), incomingIsReply = false, nowMs = t0)
            assertTrue("$theirs", result.actions.isEmpty())
        }
    }

    @Test
    fun aNewerCancelEndsAnOlderSnooze() {
        val result = AlarmSilencePolicy.reconcile(
            local(SnoozeEntry(low, t0 - 10 * minute, t0 + 20 * minute, false)),
            local(SnoozeEntry(low, t0 - minute, 0L, false)),
            incomingIsReply = false,
            nowMs = t0,
        )
        assertEquals(listOf(SilenceAction.ClearSnooze(low, t0 - minute)), result.actions)
    }

    /**
     * The owner's rule: last change wins, expired snoozes ignored. A snooze shortened to
     * 15 minutes on one device ends a two-hour one set earlier on the other, and is never
     * itself applied once it has run out.
     */
    @Test
    fun aNewerSnoozeThatHasRunOutEndsAnOlderOneAndIsNeverApplied() {
        val result = AlarmSilencePolicy.reconcile(
            local(SnoozeEntry(low, t0 - 50 * minute, t0 + 70 * minute, false)),
            local(SnoozeEntry(low, t0 - 20 * minute, t0 - 5 * minute, false)),
            incomingIsReply = false,
            nowMs = t0,
        )
        assertEquals(listOf(SilenceAction.ClearSnooze(low, t0 - 20 * minute)), result.actions)
        assertTrue(result.actions.none { it is SilenceAction.Snooze })
    }

    @Test
    fun aNewerChangeThatLeavesTheSameSnoozeOnlyTakesItsTime() {
        val expiredThere = SnoozeEntry(low, t0 - 20 * minute, t0 - 5 * minute, false)
        val noneHere = AlarmSilencePolicy.reconcile(SilenceState(), local(expiredThere), false, t0)
        assertEquals(listOf(SilenceAction.AdoptSnoozeTime(low, t0 - 20 * minute)), noneHere.actions)

        val sameEnd = AlarmSilencePolicy.reconcile(
            local(SnoozeEntry(low, t0 - 10 * minute, t0 + 20 * minute, false)),
            local(SnoozeEntry(low, t0 - 2 * minute, t0 + 20 * minute + 2 * second, false)),
            false,
            t0,
        )
        assertEquals(listOf(SilenceAction.AdoptSnoozeTime(low, t0 - 2 * minute)), sameEnd.actions)
    }

    @Test
    fun eachAlertTypeIsDecidedOnItsOwn() {
        val result = AlarmSilencePolicy.reconcile(
            local(SnoozeEntry(low, t0 - minute, t0 + 14 * minute, false), SnoozeEntry(high, t0 - hour, t0 + hour, false)),
            local(SnoozeEntry(low, t0 - hour, 0L, false), SnoozeEntry(high, t0 - minute, t0 + 29 * minute, false)),
            incomingIsReply = false,
            nowMs = t0,
        )
        assertEquals(listOf(SilenceAction.Snooze(high, t0 + 29 * minute, false, 30, t0 - minute)), result.actions)
        assertTrue("the low snooze here is newer", result.reply)
    }

    @Test
    fun theHistoryGetsTheMinutesTheSnoozeWasSetFor() {
        assertEquals(15, AlarmSilencePolicy.snoozeMinutes(t0, t0 + 15 * minute))
        assertEquals(15, AlarmSilencePolicy.snoozeMinutes(t0, t0 + 15 * minute + 3 * second))
        assertEquals(1, AlarmSilencePolicy.snoozeMinutes(t0, t0 + 5 * second))
    }

    // ------------------------------------------------------------ answers

    @Test
    fun aDeviceAnswersOnlyWithSomethingNewerAndNeverAnswersAnAnswer() {
        val newerHere = local(SnoozeEntry(low, t0 - minute, t0 + 14 * minute, false))
        assertTrue(AlarmSilencePolicy.reconcile(newerHere, SilenceState(), false, t0).reply)
        assertFalse(AlarmSilencePolicy.reconcile(newerHere, SilenceState(), true, t0).reply)

        // Nothing to say: no records, or only a record that leaves no snooze.
        assertFalse(AlarmSilencePolicy.reconcile(SilenceState(), SilenceState(), false, t0).reply)
        val nothingRunning = local(SnoozeEntry(low, t0 - minute, 0L, false))
        assertFalse(AlarmSilencePolicy.reconcile(nothingRunning, SilenceState(), false, t0).reply)

        val dismissedHere = SilenceState(dismissals = listOf(DismissEntry(low, t0 - minute)))
        assertTrue(AlarmSilencePolicy.reconcile(dismissedHere, SilenceState(), false, t0).reply)
        assertFalse(AlarmSilencePolicy.reconcile(dismissedHere, dismissedHere, false, t0).reply)
    }

    // ------------------------------------------------------------ dismissal

    @Test
    fun aDismissalReachesOnlyAnAlarmThatStartedBeforeIt() {
        val dismissedAt = t0
        val tolerance = AlarmSilencePolicy.DISMISS_START_TOLERANCE_MS
        assertTrue(AlarmSilencePolicy.dismissApplies(dismissedAt, t0 - 3 * minute, alreadyDismissed = false))
        assertTrue("the same reading, a few seconds later there", AlarmSilencePolicy.dismissApplies(dismissedAt, t0 + 10 * second, false))
        assertTrue(AlarmSilencePolicy.dismissApplies(dismissedAt, t0 + tolerance, false))
        assertFalse("a newer alarm keeps ringing", AlarmSilencePolicy.dismissApplies(dismissedAt, t0 + tolerance + 1, false))
        assertFalse("a late dismissal for an episode that ended does nothing", AlarmSilencePolicy.dismissApplies(dismissedAt, 0L, false))
        assertFalse("taken once", AlarmSilencePolicy.dismissApplies(dismissedAt, t0 - minute, alreadyDismissed = true))
    }

    @Test
    fun anAlarmNotRunningYetIsLookedForUntilTheToleranceRunsOut() {
        val tolerance = AlarmSilencePolicy.DISMISS_START_TOLERANCE_MS
        assertEquals(tolerance - 2 * second, AlarmSilencePolicy.dismissRecheckDelayMs(t0, t0 + 2 * second))
        assertEquals(0L, AlarmSilencePolicy.dismissRecheckDelayMs(t0, t0 + tolerance))
        assertEquals(-1L, AlarmSilencePolicy.dismissRecheckDelayMs(t0, t0 + tolerance + 1))
        assertEquals("a dismissal caught up with after a long absence", -1L, AlarmSilencePolicy.dismissRecheckDelayMs(t0, t0 + hour))
    }

    @Test
    fun aDismissalAlreadyKnownHereIsNotTakenAgain() {
        val theirs = SilenceState(dismissals = listOf(DismissEntry(low, t0 - minute)))
        val fresh = AlarmSilencePolicy.reconcile(SilenceState(), theirs, false, t0)
        assertEquals(listOf(SilenceAction.Dismiss(low, t0 - minute)), fresh.actions)
        val sameOne = SilenceState(dismissals = listOf(DismissEntry(low, t0 - minute + 3 * second)))
        assertTrue(AlarmSilencePolicy.reconcile(sameOne, theirs, false, t0).actions.isEmpty())
        val newerHere = SilenceState(dismissals = listOf(DismissEntry(low, t0)))
        assertTrue(AlarmSilencePolicy.reconcile(newerHere, theirs, false, t0).actions.isEmpty())
    }

    /** The tracker's episode start is what the guard compares; a held episode has one too. */
    @Test
    fun theEpisodeStartIsTheFirstFiringOrHoldAndEndsWithTheEpisode() {
        val type = AlertType.PRE_HIGH
        AlertStateTracker.resetState(type)
        assertEquals(0L, AlertStateTracker.episodeStartedAtMs(type))
        val before = System.currentTimeMillis()
        AlertStateTracker.onAlertHeld(type)
        val started = AlertStateTracker.episodeStartedAtMs(type)
        assertTrue(started >= before)
        AlertStateTracker.resetState(type)
        AlertStateTracker.onAlertTriggered(type)
        assertTrue(AlertStateTracker.episodeStartedAtMs(type) >= started)
        AlertStateTracker.resetState(type)
        assertEquals(0L, AlertStateTracker.episodeStartedAtMs(type))
    }

    /** A dismissal from the other device is taken as this device's own: same tracker path. */
    @Test
    fun aDismissalFromTheOtherDeviceAcknowledgesTheEpisodeLikeALocalOne() {
        val type = AlertType.FALLING_FAST
        AlertStateTracker.resetState(type)
        AlertStateTracker.onAlertTriggered(type)
        assertFalse(AlertStateTracker.wasLastFiringAcknowledged(type))
        assertTrue(AlertStateTracker.onAlertDismissed(type, fromPeer = true))
        assertTrue(AlertStateTracker.isDismissed(type))
        assertTrue(AlertStateTracker.wasLastFiringAcknowledged(type))
        val flag = AlertStateTracker::class.java.getDeclaredField("dismissingFromPeer").apply { isAccessible = true }
        assertFalse("the next local dismissal is sent again", flag.getBoolean(AlertStateTracker))
        assertFalse(
            "a dismissed episode is not dismissed again",
            AlarmSilencePolicy.dismissApplies(t0, AlertStateTracker.episodeStartedAtMs(type), AlertStateTracker.isDismissed(type)),
        )
    }

    // ------------------------------------------------------------ quiet window

    @Test
    fun theQuietWindowFollowsTheLastChangeToo() {
        val window = QuietEntry(t0 - minute, t0 + 59 * minute, AlertDeliveryPolicy.QUIET_VIBRATE_ONLY, 10, AlertDeliveryPolicy.BREAKTHROUGH_ALL)
        val start = AlarmSilencePolicy.reconcile(SilenceState(), SilenceState(quiet = window), false, t0)
        assertEquals(listOf(SilenceAction.StartQuiet(window)), start.actions)

        val ended = window.copy(changedAtMs = t0 - 10 * second, untilMs = 0L)
        val end = AlarmSilencePolicy.reconcile(SilenceState(quiet = window), SilenceState(quiet = ended), false, t0)
        assertEquals(listOf(SilenceAction.EndQuiet(t0 - 10 * second)), end.actions)

        val older = window.copy(changedAtMs = t0 - hour)
        assertTrue(AlarmSilencePolicy.reconcile(SilenceState(quiet = window), SilenceState(quiet = older), false, t0).actions.isEmpty())

        val otherMode = window.copy(changedAtMs = t0, mode = AlertDeliveryPolicy.QUIET_NOTIFICATION_ONLY)
        assertEquals(
            listOf(SilenceAction.StartQuiet(otherMode)),
            AlarmSilencePolicy.reconcile(SilenceState(quiet = window), SilenceState(quiet = otherMode), false, t0).actions,
        )
    }

    @Test
    fun recordsOlderThanAnythingTheyCouldDecideAreNotSent() {
        val old = t0 - AlarmSilencePolicy.RETENTION_MS - 1
        val state = SilenceState(
            snoozes = listOf(SnoozeEntry(low, old, 0L, false), SnoozeEntry(high, t0 - hour, t0 + hour, false)),
            dismissals = listOf(DismissEntry(low, old), DismissEntry(high, t0 - minute)),
            quiet = QuietEntry(old, 0L, AlertDeliveryPolicy.QUIET_VIBRATE_ONLY, 10, AlertDeliveryPolicy.BREAKTHROUGH_ALL),
        )
        val kept = AlarmSilencePolicy.retained(state, t0)
        assertEquals(listOf(high), kept.snoozes.map { it.typeId })
        assertEquals(listOf(high), kept.dismissals.map { it.typeId })
        assertNull(kept.quiet)
    }

    // ------------------------------------------------------------ two devices

    /**
     * A device as the runtime keeps it: its own clock, the records it holds, and the
     * bookkeeping AlarmSilenceSync.applyAction does for each action.
     */
    private class Device(val name: String, val clockOffsetMs: Long) {
        val snoozes = LinkedHashMap<Int, SnoozeEntry>()
        val dismissals = LinkedHashMap<Int, DismissEntry>()
        var quiet: QuietEntry? = null
        val applied = ArrayList<SilenceAction>()

        fun now(trueMs: Long) = trueMs + clockOffsetMs

        fun state(trueMs: Long) =
            AlarmSilencePolicy.retained(SilenceState(snoozes.values.toList(), dismissals.values.toList(), quiet), now(trueMs))

        fun snooze(typeId: Int, trueMs: Long, minutes: Int, preemptive: Boolean = false) {
            snoozes[typeId] = SnoozeEntry(typeId, now(trueMs), now(trueMs) + minutes * 60_000L, preemptive)
        }

        fun cancel(typeId: Int, trueMs: Long) {
            snoozes[typeId] = SnoozeEntry(typeId, now(trueMs), 0L, false)
        }

        fun dismiss(typeId: Int, trueMs: Long) {
            dismissals[typeId] = DismissEntry(typeId, now(trueMs))
        }

        fun startQuiet(trueMs: Long, minutes: Int) {
            quiet = QuietEntry(now(trueMs), now(trueMs) + minutes * 60_000L, AlertDeliveryPolicy.QUIET_VIBRATE_ONLY, 10, AlertDeliveryPolicy.BREAKTHROUGH_ALL)
        }

        fun send(trueMs: Long, reply: Boolean): ByteArray = AlarmSilenceCodec.encode(state(trueMs), now(trueMs), reply)

        /** Applies [bytes]; true when this device answers. */
        fun receive(bytes: ByteArray, trueMs: Long): Boolean {
            val message = AlarmSilenceCodec.decode(bytes, now(trueMs))!!
            val result = AlarmSilencePolicy.reconcile(state(trueMs), message.state, message.reply, now(trueMs))
            for (action in result.actions) {
                applied += action
                when (action) {
                    is SilenceAction.Snooze ->
                        snoozes[action.typeId] = SnoozeEntry(action.typeId, action.changedAtMs, action.untilMs, action.preemptive)
                    is SilenceAction.ClearSnooze -> snoozes[action.typeId] = SnoozeEntry(action.typeId, action.changedAtMs, 0L, false)
                    is SilenceAction.AdoptSnoozeTime -> snoozes[action.typeId] =
                        snoozes[action.typeId]?.copy(changedAtMs = action.changedAtMs)
                            ?: SnoozeEntry(action.typeId, action.changedAtMs, 0L, false)
                    is SilenceAction.Dismiss -> dismissals[action.typeId] = DismissEntry(action.typeId, action.dismissedAtMs)
                    is SilenceAction.StartQuiet -> quiet = action.entry
                    is SilenceAction.EndQuiet -> quiet = quiet!!.copy(changedAtMs = action.changedAtMs, untilMs = 0L)
                    is SilenceAction.AdoptQuietTime -> quiet = quiet!!.copy(changedAtMs = action.changedAtMs)
                }
            }
            return result.reply
        }
    }

    /**
     * Delivers [first] from [from] to [to], then the answers, each [transitMs] in flight;
     * returns how many messages crossed.
     */
    private fun exchange(from: Device, to: Device, trueMs: Long, transitMs: Long, reply: Boolean = false): Int {
        var sender = from
        var receiver = to
        var clock = trueMs
        var asReply = reply
        var messages = 0
        while (true) {
            val bytes = sender.send(clock, asReply)
            clock += transitMs
            messages++
            val answers = receiver.receive(bytes, clock)
            if (!answers) return messages
            assertFalse("an answer is never answered", asReply)
            asReply = true
            sender = receiver.also { receiver = sender }
            assertTrue("answers end", messages < 4)
        }
    }

    private fun assertSameSilence(a: Device, b: Device, trueMs: Long) {
        for (typeId in (a.snoozes.keys + b.snoozes.keys)) {
            val sa = a.snoozes[typeId]
            val sb = b.snoozes[typeId]?.let { it.copy(untilMs = if (it.untilMs > 0) it.untilMs - b.clockOffsetMs + a.clockOffsetMs else 0L) }
            assertTrue("type $typeId: $sa vs $sb", AlarmSilencePolicy.sameSnooze(sa, sb, a.now(trueMs)))
        }
        val qa = a.quiet
        val qb = b.quiet?.let { it.copy(untilMs = if (it.untilMs > 0) it.untilMs - b.clockOffsetMs + a.clockOffsetMs else 0L) }
        assertTrue("quiet: $qa vs $qb", AlarmSilencePolicy.sameQuiet(qa, qb, a.now(trueMs)))
    }

    /** No echo: a change applied from the other device is never sent back. */
    @Test
    fun aChangeAppliedFromTheOtherDeviceIsNotSentBack() {
        for (skew in listOf(0L, 9 * second, -2 * hour)) {
            val phone = Device("phone", 0L)
            val watch = Device("watch", skew)
            phone.startQuiet(t0, 60) // the quick-settings tile
            phone.snooze(low, t0, 30, preemptive = true)
            assertEquals("one message, no answer", 1, exchange(phone, watch, t0, transitMs = 800L))
            assertEquals(2, watch.applied.size)
            // The watch now sends its state for some other reason (it came back into reach):
            // the phone has nothing to apply and nothing to answer.
            assertEquals(1, exchange(watch, phone, t0 + minute, transitMs = 800L))
            assertTrue("nothing applied back on the phone: ${phone.applied}", phone.applied.none { it !is SilenceAction.AdoptSnoozeTime && it !is SilenceAction.AdoptQuietTime })
            assertSameSilence(phone, watch, t0 + minute)
        }
    }

    @Test
    fun aSnoozeOnTheWatchEndsAtTheSameMomentOnThePhoneWhateverTheirClocks() {
        val phone = Device("phone", 0L)
        val watch = Device("watch", -37 * second)
        watch.snooze(high, t0, 20)
        exchange(watch, phone, t0, transitMs = 0L)
        val onPhone = phone.snoozes.getValue(high)
        assertEquals("the phone's clock reads t0 when the watch's ends ...", t0 + 20 * minute, onPhone.untilMs)
        assertEquals(watch.snoozes.getValue(high).untilMs - watch.clockOffsetMs, onPhone.untilMs - phone.clockOffsetMs)
    }

    /** Out of reach: both change, then catch up on reconnect; the later change wins per type. */
    @Test
    fun devicesOutOfReachCatchUpAndTheLaterChangeWinsPerType() {
        for (skew in listOf(0L, 5 * hour, -11 * second)) {
            val phone = Device("phone", 0L)
            val watch = Device("watch", skew)
            // In reach: a two-hour preemptive snooze of high from the phone.
            phone.snooze(high, t0, 120, preemptive = true)
            exchange(phone, watch, t0, transitMs = 500L)
            // Out of reach for half an hour:
            watch.snooze(high, t0 + 5 * minute, 15) // shortened on the watch; runs out at +20
            phone.snooze(low, t0 + 10 * minute, 60) // a low snoozed on the phone
            watch.dismiss(AlertType.VERY_HIGH.id, t0 + 12 * minute)
            phone.startQuiet(t0 + 15 * minute, 60)
            // Back in reach at +30: the watch hears of it first.
            val reconnect = t0 + 30 * minute
            val messages = exchange(watch, phone, reconnect, transitMs = 1_500L)
            assertEquals("the watch's state, and the phone's answer", 2, messages)
            assertSameSilence(phone, watch, reconnect + minute)
            // High: the watch's later 15-minute snooze won and has run out, so neither has one.
            assertFalse(phone.snoozes.getValue(high).activeAt(phone.now(reconnect)))
            assertFalse(watch.snoozes.getValue(high).activeAt(watch.now(reconnect)))
            // Low and the quiet window reached the watch; the dismissal reached the phone.
            assertTrue(watch.snoozes.getValue(low).activeAt(watch.now(reconnect + 3 * second)))
            assertTrue(watch.quiet!!.activeAt(watch.now(reconnect + 3 * second)))
            assertEquals(listOf(AlertType.VERY_HIGH.id), phone.applied.filterIsInstance<SilenceAction.Dismiss>().map { it.typeId })
            // Settled: another round says nothing new either way.
            assertEquals(1, exchange(phone, watch, reconnect + 2 * minute, transitMs = 1_500L))
            assertEquals(1, exchange(watch, phone, reconnect + 3 * minute, transitMs = 1_500L))
        }
    }

    // ------------------------------------------------------------ wiring

    private val moduleRoot = File("").absoluteFile.let { working ->
        generateSequence(working) { it.parentFile }
            .firstOrNull { File(it, "src/main/java/tk/glucodata/alerts/SnoozeManager.kt").exists() }
            ?: File(working, "Common")
    }

    private fun source(relative: String): String =
        File(moduleRoot, relative).readText().replace("\r\n", "\n")

    private fun body(text: String, signature: String): String {
        val start = text.indexOf(signature)
        assertTrue("$signature not found", start >= 0)
        var depth = 0
        var i = text.indexOf('{', start)
        val from = i
        while (i < text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return text.substring(from, i + 1)
            }
            i++
        }
        error("unbalanced $signature")
    }

    /** The argument list of the call whose '(' is at [open]. */
    private fun arguments(text: String, open: Int): String {
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                '(' -> depth++
                ')' -> if (--depth == 0) return text.substring(open, i + 1)
            }
        }
        error("unbalanced call at $open")
    }

    /** Local changes are sent; changes from the other device only suppress the send. */
    @Test
    fun localChangesAreSentAndChangesFromTheOtherDeviceAreNot() {
        val snoozeManager = source("src/main/java/tk/glucodata/alerts/SnoozeManager.kt")
        assertTrue(body(snoozeManager, "fun snooze(").contains("if (!fromPeer) AlarmSilenceSync.onLocalSnoozeChanged(alertType)"))
        assertTrue(body(snoozeManager, "fun clearSnooze(").contains("if (wasSnoozed && !fromPeer) AlarmSilenceSync.onLocalSnoozeChanged(alertType)"))
        val tracker = source("src/main/java/tk/glucodata/alerts/AlertStateTracker.kt")
        // One dismissal path; the other device's goes through it with the flag set.
        assertTrue(body(tracker, "fun onAlertDismissed(type: AlertType): Boolean").contains("if (!dismissingFromPeer) AlarmSilenceSync.onLocalDismiss(type)"))
        val peerPath = body(tracker, "fun onAlertDismissed(type: AlertType, fromPeer: Boolean): Boolean")
        assertTrue(peerPath.contains("dismissingFromPeer = true") && peerPath.contains("return onAlertDismissed(type)"))
        val quiet = source("src/main/java/tk/glucodata/alerts/QuietWindow.kt")
        assertTrue(body(quiet, "fun startUntil(").contains("if (!fromPeer) AlarmSilenceSync.onLocalQuietWindowChanged()"))
        assertTrue(body(quiet, "fun end(").contains("if (!fromPeer) AlarmSilenceSync.onLocalQuietWindowChanged()"))
        assertTrue(body(quiet, "fun setMode(").contains("if (!fromPeer) AlarmSilenceSync.onLocalQuietWindowChanged()"))
        // The expiry ends the window through endInternal, which sends nothing.
        assertFalse(body(quiet, "private fun endInternal(").contains("AlarmSilenceSync"))
    }

    /** Applying goes through the local functions, always flagged, and never sends but to answer. */
    @Test
    fun changesFromTheOtherDeviceGoThroughTheLocalPathsFlagged() {
        val sync = source("src/main/java/tk/glucodata/alerts/AlarmSilenceSync.kt")
        val apply = body(sync, "private fun applyAction(") + body(sync, "private fun dismissFromPeer(")
        for (call in listOf("SnoozeManager.snooze(", "SnoozeManager.clearSnooze(", "AlertStateTracker.onAlertDismissed(", "QuietWindow.startUntil(", "QuietWindow.setMode(", "QuietWindow.end(")) {
            var at = apply.indexOf(call)
            assertTrue("$call is how a change from the other device is applied", at >= 0)
            while (at >= 0) {
                val args = arguments(apply, at + call.length - 1)
                assertTrue("$call without fromPeer = true: $args", args.contains("fromPeer = true"))
                at = apply.indexOf(call, at + 1)
            }
        }
        assertFalse(apply.contains("onLocal"))
        assertFalse(apply.contains("requestSend"))
        assertTrue(body(sync, "private fun apply(").contains("if (result.reply) requestSend(reply = true)"))
    }
}
