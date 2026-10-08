package tk.glucodata.alerts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.WearMessagePath
import tk.glucodata.WearProtocol
import java.nio.ByteBuffer

/**
 * The alert test button follows "Where alarms ring": where a test rings, the message
 * that carries it to the watch, and which test a stop from the other device reaches.
 */
class AlarmTestSyncTests {

    private val unknownable = listOf(true, false, null)
    private val phoneOnly = AlarmTestTargets(phone = true, watch = false)
    private val watchOnly = AlarmTestTargets(phone = false, watch = true)
    private val both = AlarmTestTargets(phone = true, watch = true)

    // ------------------------------------------------------------ where a test rings

    /** The owner-approved table, written out rather than derived. */
    private fun expected(mode: AlarmRoutingMode, reachable: Boolean?, charging: Boolean?): AlarmTestTargets =
        when (mode) {
            AlarmRoutingMode.BOTH -> both
            AlarmRoutingMode.PHONE_ONLY -> phoneOnly
            AlarmRoutingMode.WATCH_WHEN_CONNECTED ->
                if (reachable == true && charging == false) watchOnly else phoneOnly
        }

    @Test
    fun everyModeReachabilityAndChargingCombinationForAGlucoseAlarm() {
        var cases = 0
        for (type in listOf(AlertType.PERSISTENT_HIGH, AlertType.LOW, AlertType.LOSS, AlertType.MISSED_READING)) {
            for (mode in AlarmRoutingMode.entries) for (reachable in unknownable) for (charging in unknownable) {
                assertEquals(
                    "type=$type mode=$mode reachable=$reachable charging=$charging",
                    expected(mode, reachable, charging),
                    AlarmTestRouting.targets(type, mode, reachable, charging),
                )
                cases++
            }
        }
        assertEquals(4 * 3 * 3 * 3, cases)
    }

    @Test
    fun watchWhenConnectedRingsTheOneDeviceTheAlarmWouldUse() {
        val mode = AlarmRoutingMode.WATCH_WHEN_CONNECTED
        val type = AlertType.HIGH
        assertEquals(watchOnly, AlarmTestRouting.targets(type, mode, watchReachable = true, watchCharging = false))
        assertEquals("watch on its charger", phoneOnly, AlarmTestRouting.targets(type, mode, true, true))
        assertEquals("watch out of reach", phoneOnly, AlarmTestRouting.targets(type, mode, false, false))
        assertEquals("phone cannot tell", phoneOnly, AlarmTestRouting.targets(type, mode, null, false))
        assertEquals("charging unknown", phoneOnly, AlarmTestRouting.targets(type, mode, true, null))
    }

    @Test
    fun aTestRingsSomewhereAndOnTheWatchOnlyWhereTheAlarmWould() {
        for (type in AlertType.entries) for (mode in AlarmRoutingMode.entries) {
            for (reachable in unknownable) for (charging in unknownable) {
                val targets = AlarmTestRouting.targets(type, mode, reachable, charging)
                assertTrue("$type $mode rings nowhere", targets.phone || targets.watch)
                if (targets.watch && AlarmRouting.routes(type)) {
                    assertTrue(
                        "$type $mode: the watch would not ring the alarm itself",
                        AlarmRouting.shouldRing(onWatch = true, mode, reachable, charging),
                    )
                }
                if (!targets.phone) {
                    assertFalse(
                        "$type $mode: the phone is silent only where its alarm would be",
                        AlarmRouting.shouldRing(onWatch = false, mode, reachable, charging),
                    )
                }
            }
        }
    }

    @Test
    fun sensorExpiryDoesNotFollowTheSettingSoItsTestRingsOnBoth() {
        for (mode in AlarmRoutingMode.entries) for (reachable in unknownable) for (charging in unknownable) {
            assertEquals(both, AlarmTestRouting.targets(AlertType.SENSOR_EXPIRY, mode, reachable, charging))
        }
    }

    @Test
    fun legacyOnlyTypesStayOnThePhone() {
        for (type in listOf(AlertType.AVAILABLE, AlertType.AMOUNT)) {
            assertEquals(phoneOnly, AlarmTestRouting.targets(type, AlarmRoutingMode.BOTH, true, false))
        }
    }

    // ------------------------------------------------------------ the message

    @Test
    fun startAndStopRoundTrip() {
        for (op in listOf(AlarmTestCodec.OP_START, AlarmTestCodec.OP_STOP)) {
            for (typeId in listOf(0, AlertType.PERSISTENT_HIGH.id, 255)) {
                for (testId in listOf(1L, -1L, Long.MAX_VALUE, Long.MIN_VALUE, 0x0123456789ABCDEFL)) {
                    assertEquals(
                        AlarmTestCodec.Message(op, typeId, testId),
                        AlarmTestCodec.decode(AlarmTestCodec.encode(op, typeId, testId)),
                    )
                }
            }
        }
    }

    @Test
    fun theLayoutIsFixed() {
        val bytes = AlarmTestCodec.encode(AlarmTestCodec.OP_STOP, AlertType.PERSISTENT_HIGH.id, 0x0102030405060708L)
        assertEquals(
            listOf(1, 2, 10, 1, 2, 3, 4, 5, 6, 7, 8),
            bytes.map { it.toInt() and 0xff },
        )
    }

    @Test
    fun unreadableMessagesAreIgnored() {
        assertNull(AlarmTestCodec.decode(null))
        assertNull(AlarmTestCodec.decode(ByteArray(0)))
        val good = AlarmTestCodec.encode(AlarmTestCodec.OP_START, AlertType.LOW.id, 42L)
        assertNull("truncated", AlarmTestCodec.decode(good.copyOf(good.size - 1)))
        assertNull("format 0", AlarmTestCodec.decode(good.copyOf().also { it[0] = 0 }))
        assertNull("unknown op", AlarmTestCodec.decode(good.copyOf().also { it[1] = 3 }))
        assertNull("op 0", AlarmTestCodec.decode(good.copyOf().also { it[1] = 0 }))
    }

    @Test
    fun aLaterFormatWithMoreFieldsIsStillRead() {
        val later = ByteBuffer.allocate(11 + 4)
            .put(2).put(AlarmTestCodec.OP_START.toByte()).put(AlertType.HIGH.id.toByte()).putLong(7L).putInt(99)
            .array()
        assertEquals(AlarmTestCodec.Message(AlarmTestCodec.OP_START, AlertType.HIGH.id, 7L), AlarmTestCodec.decode(later))
    }

    @Test
    fun anOlderBuildDropsTheNewPathAndTheProtocolVersionStays() {
        assertEquals("a bump makes older peers drop every managed message", 1, WearProtocol.VERSION)
        val path = WearMessagePath.SYNC2_ALARM_TEST.wire
        assertEquals("/sync2/alarmtest", path)
        // Both manifests already deliver /sync2 (WearMessagePathManifestTests checks the
        // prefixes); an older receiver resolves the path to null and ignores it.
        assertTrue(path.startsWith("/sync2/"))
        val olderPaths = WearMessagePath.entries.filter { it != WearMessagePath.SYNC2_ALARM_TEST }.map { it.wire }
        assertFalse("not a path an older build acts on", path in olderPaths)
        assertEquals(WearMessagePath.SYNC2_ALARM_TEST, WearMessagePath.fromWire(path))
    }

    // ------------------------------------------------------------ which test a stop reaches

    private val high = AlertType.HIGH.id

    @Test
    fun aStopHereIsSentOnlyForATestTheOtherDeviceRings() {
        val runs = AlarmTestRuns()
        runs.started(high, testId = 5L, shared = true)
        assertEquals(AlarmTestRuns.Run(5L, shared = true), runs.stoppedHere(high))
        assertNull("stopped once", runs.stoppedHere(high))

        runs.started(high, testId = 6L, shared = false)
        assertNull("rang here only", runs.stoppedHere(high))
        assertNull(runs.current(high))
    }

    @Test
    fun aTestThatDidNotReachTheOtherDeviceIsNotStoppedThere() {
        val runs = AlarmTestRuns()
        runs.started(high, testId = 5L, shared = true)
        runs.notShared(high, testId = 4L)
        assertTrue("another test's failure", runs.current(high)!!.shared)
        runs.notShared(high, testId = 5L)
        assertNull(runs.stoppedHere(high))
    }

    @Test
    fun aStopFromTheOtherDeviceReachesOnlyItsOwnTest() {
        val runs = AlarmTestRuns()
        assertFalse("no test here", runs.stoppedThere(high, 5L))

        runs.started(high, testId = 5L, shared = true)
        // A newer test started here since: the late stop of the older one leaves it.
        runs.started(high, testId = 6L, shared = true)
        assertFalse(runs.stoppedThere(high, 5L))
        assertNotNull(runs.current(high))

        assertFalse("another alert type", runs.stoppedThere(AlertType.LOW.id, 6L))
        assertTrue(runs.stoppedThere(high, 6L))
        assertNull(runs.current(high))
        assertFalse("stopped once", runs.stoppedThere(high, 6L))
    }

    @Test
    fun aStopFromTheOtherDeviceIsNotSentBack() {
        val runs = AlarmTestRuns()
        runs.started(high, testId = 5L, shared = true)
        assertTrue(runs.stoppedThere(high, 5L))
        // The local dismissal that follows (AlertStateTracker) finds no run to report.
        assertNull(runs.stoppedHere(high))
    }

    // ------------------------------------------------------------ the tracker side

    @Test
    fun aStopFromTheOtherDeviceEndsOnlyAnActiveTestAndRecordsNothing() {
        val type = AlertType.RISING_FAST
        AlertStateTracker.resetState(type)
        assertFalse("no test on", AlertStateTracker.endManualTestFromPeer(type))

        AlertStateTracker.allowNextTriggerForTest(type)
        assertTrue(AlertStateTracker.shouldTrigger(type, AlertConfig(type = type, enabled = true)))
        assertFalse("a test firing is no production firing", AlertStateTracker.onAlertTriggered(type))
        assertTrue(AlertStateTracker.endManualTestFromPeer(type))
        assertFalse(AlertStateTracker.isDismissed(type))
        assertFalse(AlertStateTracker.isEpisodeActive(type))
        assertFalse("ended once", AlertStateTracker.endManualTestFromPeer(type))
        // Answered there, nothing is left here for a dismissal to take as a test's.
        assertFalse(AlertStateTracker.consumeManualTestAction(type))
        AlertStateTracker.resetState(type)
    }
}
