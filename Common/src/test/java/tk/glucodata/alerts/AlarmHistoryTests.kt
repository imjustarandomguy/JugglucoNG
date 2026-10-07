package tk.glucodata.alerts

import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.WearProtocol

/**
 * The alarm history's rules: how an alarm ends, what is kept, what the watch holds,
 * and the line that carries an event from the watch to the phone.
 */
class AlarmHistoryTests {

    private val hour = 60L * 60L * 1000L
    private val day = 24L * hour
    private val t0 = 1_780_000_000_000L

    private fun event(
        typeId: Int,
        firedAtMs: Long,
        device: AlarmDevice = AlarmDevice.PHONE,
        outcome: AlarmOutcome = AlarmOutcome.ACTIVE,
        value: Float = 3.4f,
        unit: Int = 1,
        snoozeMinutes: Int = 0,
        endedAtMs: Long = 0L,
        pending: Boolean = false,
    ) = AlarmEvent(
        id = AlarmHistoryPolicy.newId(device, typeId, firedAtMs),
        firedAtMs = firedAtMs,
        alertTypeId = typeId,
        value = value,
        unit = unit,
        device = device,
        outcome = outcome,
        snoozeMinutes = snoozeMinutes,
        endedAtMs = endedAtMs,
        pendingDelivery = pending,
    )

    // ---------------------------------------------------------------- outcomes

    @Test
    fun clearReasonsThatMeanTheConditionWentAwayAreRecovered() {
        listOf(
            "standard-condition-cleared",
            "forecast-falsified",
            "forecast-iob-covered",
            "forecast-low-not-falling",
            "forecast-cob-covered",
            "new-reading-arrived",
            "persistent-high-cleared",
            "persistent-low-cleared",
            "sensor-expiry-not-due",
        ).forEach { reason ->
            assertEquals(reason, AlarmOutcome.RECOVERED, AlarmHistoryPolicy.outcomeForClearReason(reason))
        }
    }

    @Test
    fun clearReasonsThatStopTheAlarmWithoutRecoveryAreEnded() {
        listOf(
            "missed-reading-disabled",
            "missed-reading-time-inactive",
            "persistent-high-disabled",
            "persistent-high-time-inactive",
            "persistent-low-disabled",
            "persistent-low-time-inactive",
            "sensor-expiry-disabled",
            "sensor-expiry-no-endtime",
            "sensor-expiry-time-inactive",
            "delta-alarm-disabled",
            "delta-alarm-time-inactive",
            "sensor-handover-window",
        ).forEach { reason ->
            assertEquals(reason, AlarmOutcome.ENDED, AlarmHistoryPolicy.outcomeForClearReason(reason))
        }
        assertEquals(AlarmOutcome.ENDED, AlarmHistoryPolicy.outcomeForClearReason(null))
    }

    @Test
    fun holdsLeaveTheEventOpen() {
        listOf("persistent-high-falling", "persistent-low-rising", "persistent-low-very-low").forEach { reason ->
            assertNull(reason, AlarmHistoryPolicy.outcomeForClearReason(reason))
        }
    }

    @Test
    fun aDismissClosesTheNewestOpenEventOfThatAlertOnThatDeviceOnly() {
        val older = event(0, t0, outcome = AlarmOutcome.RECOVERED, endedAtMs = t0 + 1)
        val open = event(0, t0 + hour)
        val otherAlert = event(5, t0 + hour + 1)
        val watch = event(0, t0 + hour + 2, device = AlarmDevice.WATCH)
        val (next, closed) = AlarmHistoryPolicy.close(
            listOf(older, open, otherAlert, watch), 0, AlarmDevice.PHONE, AlarmOutcome.DISMISSED, 0, t0 + 2 * hour
        )
        assertEquals(open.id, closed?.id)
        assertEquals(AlarmOutcome.DISMISSED, closed?.outcome)
        assertEquals(t0 + 2 * hour, closed?.endedAtMs)
        assertEquals(listOf(older, closed, otherAlert, watch), next)
    }

    @Test
    fun aSnoozeRecordsItsMinutes() {
        val (_, closed) = AlarmHistoryPolicy.close(
            listOf(event(1, t0)), 1, AlarmDevice.PHONE, AlarmOutcome.SNOOZED, 30, t0 + 1000
        )
        assertEquals(AlarmOutcome.SNOOZED, closed?.outcome)
        assertEquals(30, closed?.snoozeMinutes)
    }

    @Test
    fun anActionWithNothingOpenChangesNothing() {
        val events = listOf(event(1, t0, outcome = AlarmOutcome.DISMISSED, endedAtMs = t0 + 5))
        val (next, closed) = AlarmHistoryPolicy.close(events, 1, AlarmDevice.PHONE, AlarmOutcome.SNOOZED, 15, t0 + 10)
        assertNull(closed)
        assertSame(events, next)
    }

    @Test
    fun aClosedEventNeverChangesOutcomeAgain() {
        val (afterDismiss, _) = AlarmHistoryPolicy.close(
            listOf(event(0, t0)), 0, AlarmDevice.PHONE, AlarmOutcome.DISMISSED, 0, t0 + 1
        )
        val (afterClear, closed) = AlarmHistoryPolicy.close(
            afterDismiss, 0, AlarmDevice.PHONE, AlarmOutcome.RECOVERED, 0, t0 + 2
        )
        assertNull(closed)
        assertEquals(AlarmOutcome.DISMISSED, afterClear.single().outcome)
    }

    @Test
    fun aRefireSupersedesTheOpenEventOfTheSameAlertOnTheSameDevice() {
        val open = event(12, t0)
        val otherAlert = event(13, t0 + 1)
        val watch = event(12, t0 + 2, device = AlarmDevice.WATCH)
        val fired = event(12, t0 + hour)
        val next = AlarmHistoryPolicy.onFired(listOf(open, otherAlert, watch), fired, t0 + hour)
        assertEquals(4, next.size)
        val superseded = next.first { it.id == open.id }
        assertEquals(AlarmOutcome.ENDED, superseded.outcome)
        assertEquals(t0 + hour, superseded.endedAtMs)
        assertTrue(next.first { it.id == otherAlert.id }.isOpen)
        assertTrue(next.first { it.id == watch.id }.isOpen)
        assertTrue(next.last().isOpen)
        assertEquals(fired.id, next.last().id)
    }

    @Test
    fun anyAlertIdIsRecordedIncludingOnesThisBuildDoesNotKnow() {
        // 14 is PERSISTENT_LOW on a build that has it; the history only carries the id.
        val fired = event(14, t0, value = 3.7f)
        val next = AlarmHistoryPolicy.onFired(emptyList(), fired, t0)
        val (closed, _) = AlarmHistoryPolicy.close(next, 14, AlarmDevice.PHONE, AlarmOutcome.DISMISSED, 0, t0 + 1)
        assertEquals(AlarmOutcome.DISMISSED, closed.single().outcome)
        val decoded = AlarmHistoryCodec.decodeMessage(AlarmHistoryCodec.encodeMessage(closed))
        assertEquals(14, decoded.single().alertTypeId)
    }

    // ---------------------------------------------------------------- pruning

    @Test
    fun pruningDropsEverythingOlderThanThirtyDays() {
        val now = t0 + 40 * day
        val tooOld = event(0, now - 30 * day - 1, outcome = AlarmOutcome.DISMISSED, endedAtMs = now - 30 * day)
        val justIn = event(1, now - 30 * day, outcome = AlarmOutcome.DISMISSED, endedAtMs = now - 29 * day)
        val pruned = AlarmHistoryPolicy.prune(listOf(tooOld, justIn), now, AlarmHistoryPolicy.PHONE_MAX_EVENTS)
        assertEquals(listOf(justIn), pruned)
    }

    @Test
    fun anEventOpenPastTheTimeoutIsClosedAsTimedOut() {
        val stale = event(12, t0)
        val fresh = event(13, t0 + 1)
        val now = t0 + AlarmHistoryPolicy.OPEN_TIMEOUT_MS
        val pruned = AlarmHistoryPolicy.prune(listOf(stale, fresh), now, 100)
        assertEquals(AlarmOutcome.ENDED, pruned[0].outcome)
        assertEquals(t0 + AlarmHistoryPolicy.OPEN_TIMEOUT_MS, pruned[0].endedAtMs)
        assertTrue(pruned[1].isOpen)
    }

    @Test
    fun pruningKeepsTheNewestWhenOverTheCap() {
        val events = (0 until 10).map { event(0, t0 + it, outcome = AlarmOutcome.DISMISSED, endedAtMs = t0 + it) }
        val pruned = AlarmHistoryPolicy.prune(events.shuffled(), t0 + 100, 3)
        assertEquals(events.takeLast(3), pruned)
    }

    @Test
    fun phoneMergeKeepsOneEventPerIdAndAClosedStateWins() {
        val open = event(0, t0, device = AlarmDevice.WATCH)
        val closed = open.copy(outcome = AlarmOutcome.SNOOZED, snoozeMinutes = 15, endedAtMs = t0 + 60_000, pendingDelivery = true)
        val merged = AlarmHistoryPolicy.upsert(listOf(open), listOf(closed))
        assertEquals(1, merged.size)
        assertEquals(AlarmOutcome.SNOOZED, merged.single().outcome)
        assertFalse("delivery state is the watch's, never stored on the phone", merged.single().pendingDelivery)
        // A late copy of the open state does not reopen it.
        assertEquals(merged, AlarmHistoryPolicy.upsert(merged, listOf(open)))
    }

    // ---------------------------------------------------------------- watch buffer

    @Test
    fun theWatchKeepsOnlyWhatItStillOwesOrMayStillClose() {
        val delivered = event(0, t0, AlarmDevice.WATCH, AlarmOutcome.DISMISSED, endedAtMs = t0 + 1)
        val pendingClosed = event(1, t0 + 1, AlarmDevice.WATCH, AlarmOutcome.RECOVERED, endedAtMs = t0 + 2, pending = true)
        val openDelivered = event(5, t0 + 2, AlarmDevice.WATCH)
        val bounded = AlarmHistoryPolicy.boundWatchBuffer(listOf(delivered, pendingClosed, openDelivered))
        assertEquals(listOf(pendingClosed, openDelivered), bounded)
    }

    @Test
    fun theWatchBufferIsBoundedAndKeepsTheLatest() {
        val max = AlarmHistoryPolicy.WATCH_MAX_EVENTS
        val events = (0 until max + 25).map {
            event(it % 14, t0 + it * 1000L, AlarmDevice.WATCH, AlarmOutcome.DISMISSED, endedAtMs = t0 + it * 1000L + 1, pending = true)
        }
        val bounded = AlarmHistoryPolicy.boundWatchBuffer(events.reversed())
        assertEquals(max, bounded.size)
        assertEquals(events.takeLast(max), bounded)
        // The whole buffer goes out in one message.
        val decoded = AlarmHistoryCodec.decodeMessage(AlarmHistoryCodec.encodeMessage(bounded))
        assertEquals(max, decoded.size)
    }

    @Test
    fun anAcknowledgedSendClearsOnlyWhatDidNotChangeMeanwhile() {
        val a = event(0, t0, AlarmDevice.WATCH, pending = true)
        val b = event(1, t0 + 1, AlarmDevice.WATCH, AlarmOutcome.DISMISSED, endedAtMs = t0 + 2, pending = true)
        val sent = listOf(a, b)
        // While the send was in flight, a was dismissed on the watch.
        val (changed, _) = AlarmHistoryPolicy.close(sent, 0, AlarmDevice.WATCH, AlarmOutcome.DISMISSED, 0, t0 + 3)
        val acked = AlarmHistoryPolicy.acknowledge(changed, sent)
        assertTrue("a's new outcome still has to go", acked.first { it.id == a.id }.pendingDelivery)
        assertFalse(acked.first { it.id == b.id }.pendingDelivery)
    }

    @Test
    fun closingAWatchEventMarksItForDelivery() {
        val delivered = event(0, t0, AlarmDevice.WATCH, pending = false)
        val (_, closed) = AlarmHistoryPolicy.close(listOf(delivered), 0, AlarmDevice.WATCH, AlarmOutcome.SNOOZED, 20, t0 + 5)
        assertTrue(closed!!.pendingDelivery)
    }

    // ---------------------------------------------------------------- wire format

    @Test
    fun theMessageRoundTripsEveryOutcomeAndDevice() {
        val events = AlarmOutcome.entries.mapIndexed { i, outcome ->
            event(
                typeId = i,
                firedAtMs = t0 + i,
                device = if (i % 2 == 0) AlarmDevice.WATCH else AlarmDevice.PHONE,
                outcome = outcome,
                value = if (i == 2) Float.NaN else 3.4f + i,
                unit = i % 2,
                snoozeMinutes = if (outcome == AlarmOutcome.SNOOZED) 45 else 0,
                endedAtMs = if (outcome == AlarmOutcome.ACTIVE) 0L else t0 + 100 + i,
            )
        }
        assertEquals(events, AlarmHistoryCodec.decodeMessage(AlarmHistoryCodec.encodeMessage(events)))
    }

    @Test
    fun theMessageIsVersionedAndCarriesNoLocalState() {
        val text = AlarmHistoryCodec.encodeMessage(listOf(event(0, t0, AlarmDevice.WATCH, pending = true)))
            .toString(Charsets.UTF_8)
        val lines = text.lines().filter { it.isNotBlank() }
        assertEquals(WearProtocol.versionLine(), lines[0])
        assertEquals("e1|w$t0-0|$t0|0|3.4|1|w|a|0|0", lines[1])
        assertFalse(AlarmHistoryCodec.decodeMessage(text.toByteArray()).single().pendingDelivery)
    }

    @Test
    fun aMessageFromANewerProtocolIsIgnoredWhole() {
        val payload = "v:${WearProtocol.VERSION + 1}\ne1|w1-0|1780000000000|0|3.4|1|w|d|0|1780000000001\n"
        assertTrue(AlarmHistoryCodec.decodeMessage(payload.toByteArray()).isEmpty())
    }

    @Test
    fun aMessageWithoutAVersionLineIsRead() {
        val payload = "e1|w1780000000000-0|1780000000000|0|3.4|1|w|d|0|1780000000001\n"
        assertEquals(1, AlarmHistoryCodec.decodeMessage(payload.toByteArray()).size)
    }

    @Test
    fun laterAdditionsAreSkippedRatherThanBreakingTheMessage() {
        val payload = listOf(
            WearProtocol.versionLine(),
            // A later line format.
            "e2|w1780000000000-0|whatever",
            // A later e1 with extra fields appended.
            "e1|w1780000000001-1|1780000000001|1|10.2|1|w|d|0|1780000000002|extra|more",
            // An outcome this build does not know is still a closed event.
            "e1|w1780000000002-6|1780000000002|6|15.0|1|w|z|0|1780000000003",
            "some other line",
        ).joinToString("\n").toByteArray()
        val decoded = AlarmHistoryCodec.decodeMessage(payload)
        assertEquals(2, decoded.size)
        assertEquals(AlarmOutcome.DISMISSED, decoded[0].outcome)
        assertEquals(10.2f, decoded[0].value)
        assertEquals(AlarmOutcome.ENDED, decoded[1].outcome)
    }

    @Test
    fun unusableLinesAreDropped() {
        listOf(
            "e1|w1-0|1780000000000|0|3.4|1|w|d|0", // a field short
            "e1||1780000000000|0|3.4|1|w|d|0|1", // no id
            "e1|w 1|1780000000000|0|3.4|1|w|d|0|1", // id with a space
            "e1|w1-0|0|0|3.4|1|w|d|0|1", // no fire time
            "e1|w1-0|1780000000000|-1|3.4|1|w|d|0|1", // negative alert id
            "e1|w1-0|1780000000000|0|3.4|x|w|d|0|1", // no unit
            "e1|w1-0|1780000000000|0|3.4|1|q|d|0|1", // unknown device
        ).forEach { line ->
            assertNull(line, AlarmHistoryCodec.decodeLine(line))
        }
        assertTrue(AlarmHistoryCodec.decodeMessage(null).isEmpty())
        assertTrue(AlarmHistoryCodec.decodeMessage(ByteArray(0)).isEmpty())
        assertTrue(AlarmHistoryCodec.decodeMessage("garbage".toByteArray()).isEmpty())
    }

    @Test
    fun anUnreadableValueIsNoValueNotALostEvent() {
        val decoded = AlarmHistoryCodec.decodeLine("e1|w1-0|1780000000000|11|abc|1|w|r|0|1780000000001")
        assertTrue(decoded!!.value.isNaN())
    }

    @Test
    fun theFileKeepsTheDeliveryFlagAndReadsLinesWithoutIt() {
        val events = listOf(
            event(0, t0, AlarmDevice.WATCH, pending = true),
            event(1, t0 + 1, AlarmDevice.WATCH, AlarmOutcome.SNOOZED, snoozeMinutes = 15, endedAtMs = t0 + 2),
        )
        assertEquals(events, AlarmHistoryCodec.decodeStore(AlarmHistoryCodec.encodeStore(events)))
        val withoutFlag = AlarmHistoryCodec.encodeLine(events[0])
        assertFalse(AlarmHistoryCodec.decodeStore(withoutFlag).single().pendingDelivery)
        assertTrue(AlarmHistoryCodec.decodeStore(null).isEmpty())
    }

    // ---------------------------------------------------------------- display

    @Test
    fun theListIsNewestFirstAndGroupedByLocalDay() {
        val zone = TimeZone.getTimeZone("Europe/Paris")
        val calendar = java.util.Calendar.getInstance(zone).apply {
            clear()
            set(2026, java.util.Calendar.OCTOBER, 6, 0, 0, 0)
        }
        val midnight = calendar.timeInMillis
        val lateYesterday = event(0, midnight - 60_000)
        val earlyToday = event(1, midnight + 60_000)
        val laterToday = event(5, midnight + 10 * hour)
        val days = AlarmHistoryPolicy.groupByDay(listOf(lateYesterday, laterToday, earlyToday), zone)
        assertEquals(listOf(midnight, midnight - day), days.map { it.first })
        assertEquals(listOf(laterToday, earlyToday), days[0].second)
        assertEquals(listOf(lateYesterday), days[1].second)
    }

    // ---------------------------------------------------------------- snooze time left

    @Test
    fun timeLeftRoundsUpAndEndsAtZero() {
        val until = t0 + 15 * 60_000L
        assertEquals(15, SnoozeTimeLeft.minutesLeft(until, t0))
        assertEquals(15, SnoozeTimeLeft.minutesLeft(until, t0 + 1))
        assertEquals(12, SnoozeTimeLeft.minutesLeft(until, t0 + 3 * 60_000L))
        assertEquals(1, SnoozeTimeLeft.minutesLeft(until, until - 10_000L))
        assertEquals(0, SnoozeTimeLeft.minutesLeft(until, until))
        assertEquals(0, SnoozeTimeLeft.minutesLeft(until, until + 1))
        assertEquals(0, SnoozeTimeLeft.minutesLeft(0L, t0))
    }

    @Test
    fun timeLeftSplitsIntoHoursAndMinutes() {
        assertEquals(0 to 12, SnoozeTimeLeft.split(12))
        assertEquals(1 to 0, SnoozeTimeLeft.split(60))
        assertEquals(1 to 5, SnoozeTimeLeft.split(65))
        assertEquals(0 to 0, SnoozeTimeLeft.split(-3))
    }

    @Test
    fun theScreenLooksAgainWhenTheShownMinuteChangesAndAtLeastEveryFifteenSeconds() {
        // 12 min 30 s left reads "13 min" until 30 s from now; capped to the idle rate.
        assertEquals(15_000L, SnoozeTimeLeft.refreshDelayMs(listOf(t0 + 12 * 60_000L + 30_000L), t0))
        assertEquals(5_000L, SnoozeTimeLeft.refreshDelayMs(listOf(t0 + 12 * 60_000L + 5_000L), t0))
        // The soonest change wins.
        assertEquals(
            2_000L,
            SnoozeTimeLeft.refreshDelayMs(listOf(t0 + 60 * 60_000L + 9_000L, t0 + 3 * 60_000L + 2_000L), t0)
        )
        // Nothing snoozed: the idle rate, so a new snooze shows up.
        assertEquals(SnoozeTimeLeft.IDLE_REFRESH_MS, SnoozeTimeLeft.refreshDelayMs(emptyList(), t0))
        // Never a busy loop.
        assertEquals(1_000L, SnoozeTimeLeft.refreshDelayMs(listOf(t0 + 60_000L + 10L), t0))
    }
}
