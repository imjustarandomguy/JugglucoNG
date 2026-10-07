package tk.glucodata.alerts

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.WearProtocol
import tk.glucodata.WearToggleSync
import java.util.Base64

/**
 * "Where alarms ring": the decision each device takes when an alarm would
 * sound, which alarms follow it, and the global line that carries it (with the
 * two other shared alert settings) to the watch.
 */
class AlarmRoutingTests {

    private val modes = AlarmRoutingMode.entries
    private val unknownable = listOf(true, false, null)

    // ------------------------------------------------------------ the decision

    /** The owner-approved table, written out rather than derived. */
    private fun expected(onWatch: Boolean, mode: AlarmRoutingMode, reachable: Boolean?, charging: Boolean?): Boolean =
        when (mode) {
            AlarmRoutingMode.BOTH -> true
            AlarmRoutingMode.PHONE_ONLY -> !onWatch
            AlarmRoutingMode.WATCH_WHEN_CONNECTED -> when {
                onWatch -> true
                // The phone is silent in exactly one case.
                reachable == true && charging == false -> false
                else -> true
            }
        }

    @Test
    fun everyModeDeviceReachabilityAndChargingCombination() {
        var cases = 0
        for (mode in modes) for (onWatch in listOf(false, true)) for (reachable in unknownable) for (charging in unknownable) {
            assertEquals(
                "mode=$mode onWatch=$onWatch reachable=$reachable charging=$charging",
                expected(onWatch, mode, reachable, charging),
                AlarmRouting.shouldRing(onWatch, mode, reachable, charging),
            )
            cases++
        }
        assertEquals(3 * 2 * 3 * 3, cases)
    }

    @Test
    fun bothIsTodaysBehaviourWhateverTheLink() {
        for (onWatch in listOf(false, true)) for (reachable in unknownable) for (charging in unknownable) {
            assertTrue(AlarmRouting.shouldRing(onWatch, AlarmRoutingMode.BOTH, reachable, charging))
        }
    }

    @Test
    fun watchWhenConnectedSilencesThePhoneOnlyForAReachableWatchOffItsCharger() {
        val mode = AlarmRoutingMode.WATCH_WHEN_CONNECTED
        assertFalse(AlarmRouting.shouldRing(onWatch = false, mode, watchReachable = true, watchCharging = false))
        // Out of reach, on the charger: the phone takes over.
        assertTrue(AlarmRouting.shouldRing(false, mode, watchReachable = false, watchCharging = false))
        assertTrue(AlarmRouting.shouldRing(false, mode, watchReachable = true, watchCharging = true))
        assertTrue(AlarmRouting.shouldRing(false, mode, watchReachable = false, watchCharging = true))
    }

    @Test
    fun aPhoneThatCannotTellRings() {
        val mode = AlarmRoutingMode.WATCH_WHEN_CONNECTED
        assertTrue("reachability unknown", AlarmRouting.shouldRing(false, mode, watchReachable = null, watchCharging = false))
        assertTrue("charging unknown, e.g. an older watch", AlarmRouting.shouldRing(false, mode, watchReachable = true, watchCharging = null))
        assertTrue("nothing known", AlarmRouting.shouldRing(false, mode, watchReachable = null, watchCharging = null))
    }

    @Test
    fun theWatchRingsInWatchModeEvenOnItsChargerOrAlone() {
        // It is the preferred device: it never stands down for a phone that might not ring.
        for (reachable in unknownable) for (charging in unknownable) {
            assertTrue(AlarmRouting.shouldRing(onWatch = true, AlarmRoutingMode.WATCH_WHEN_CONNECTED, reachable, charging))
        }
    }

    @Test
    fun phoneOnlyNeverRingsTheWatchAndAlwaysRingsThePhone() {
        for (reachable in unknownable) for (charging in unknownable) {
            assertFalse(AlarmRouting.shouldRing(onWatch = true, AlarmRoutingMode.PHONE_ONLY, reachable, charging))
            assertTrue(AlarmRouting.shouldRing(onWatch = false, AlarmRoutingMode.PHONE_ONLY, reachable, charging))
        }
    }

    // ---------------------------------------------------- which alarms follow it

    @Test
    fun everyGlucoseAlarmFollowsTheSettingAndVeryLowIsNoException() {
        val glucose = listOf(
            AlertType.LOW, AlertType.VERY_LOW, AlertType.HIGH, AlertType.VERY_HIGH,
            AlertType.PRE_LOW, AlertType.PRE_HIGH, AlertType.FALLING_FAST, AlertType.RISING_FAST,
            AlertType.PERSISTENT_HIGH, AlertType.MISSED_READING, AlertType.LOSS,
        )
        glucose.forEach { assertTrue("$it", AlarmRouting.routes(it)) }
    }

    @Test
    fun onlySensorExpiryAndTheHiddenLegacyTypesStayAsTheyWere() {
        // Read from the enum, so a type added later (Persistent low, id 14, on
        // personal) is routed without an edit here.
        assertEquals(
            setOf(AlertType.SENSOR_EXPIRY, AlertType.AVAILABLE, AlertType.AMOUNT),
            AlertType.entries.filterNot(AlarmRouting::routes).toSet(),
        )
        AlertType.fromId(14)?.let { persistentLow -> assertTrue(AlarmRouting.routes(persistentLow)) }
    }

    // -------------------------------------------------------- the episode spent

    private val held = AlertType.PRE_HIGH

    @After
    fun forgetHeldEpisode() {
        AlertStateTracker.consumeManualTestAction(held)
        AlertStateTracker.resetState(held)
    }

    @Test
    fun aHeldFiringSpendsTheEpisodeAndItsCooldownLikeADelivery() {
        AlertStateTracker.resetState(held)
        assertFalse(AlertStateTracker.isEpisodeActive(held))

        AlertStateTracker.onAlertHeld(held, AlertDefaults.defaultConfig(held, isMmol = true))

        assertTrue("the episode counts as fired", AlertStateTracker.isEpisodeActive(held))
        assertTrue("and waits out the rearm cooldown", AlertStateTracker.isWaitingForRearmCooldown(held))
    }

    @Test
    fun aHeldFiringRecordsNothingToAcknowledge() {
        // A delivered, dismissed firing stays the acknowledged one: holding
        // shows nothing, so it cannot turn into an unanswered alarm here.
        AlertStateTracker.resetState(held)
        assertTrue(AlertStateTracker.onAlertTriggered(held))
        assertTrue(AlertStateTracker.onAlertDismissed(held))
        AlertStateTracker.resetState(held)

        AlertStateTracker.onAlertHeld(held)

        assertTrue(AlertStateTracker.wasLastFiringAcknowledged(held))
        assertFalse("not dismissed: the new episode was not answered here", AlertStateTracker.isDismissed(held))
    }

    // --------------------------------------------------------- the global line

    private val unusual = GlobalAlertSettings(
        alarmRouting = AlarmRoutingMode.WATCH_WHEN_CONNECTED,
        sameDirectionSuppressionMinutes = 17,
        acknowledgedHighCoverage = false,
    )

    private fun roundTrip(settings: GlobalAlertSettings): GlobalAlertSettings {
        val entries = AlertConfigSync.decodeGlobalLine(AlertConfigSync.encodeGlobalLine(settings))
        assertNotNull("global line did not decode", entries)
        return AlertConfigSync.globalSettingsFrom(entries!!, null)
    }

    @Test
    fun everyModeAndBothSuppressionSettingsArriveOnTheWatch() {
        for (mode in modes) for (coverage in listOf(true, false)) for (minutes in listOf(0, 5, 30)) {
            val phone = GlobalAlertSettings(mode, minutes, coverage)
            assertEquals(phone, roundTrip(phone))
        }
    }

    @Test
    fun theDefaultsArriveAsDefaults() {
        assertEquals(GlobalAlertSettings(), roundTrip(GlobalAlertSettings()))
        // And an empty store reads as the defaults: BOTH, so an unset watch rings.
        assertEquals(GlobalAlertSettings(), AlertConfigSync.globalSettingsFrom(emptyMap(), null))
    }

    @Test
    fun theLineCarriesTheStoreEntriesFromTheStoresOwnWriter() {
        val entries = AlertConfigSync.globalEntriesOf(unusual)
        assertEquals(
            mapOf(
                "alarm_routing" to "WATCH_WHEN_CONNECTED",
                "same_direction_suppression_min" to 17,
                "acknowledged_high_coverage" to false,
            ),
            entries,
        )
    }

    @Test
    fun aKeyTheLineLacksKeepsTheWatchsValue() {
        val watch = OverlayPreferences(
            null,
            mapOf("same_direction_suppression_min" to 9, "acknowledged_high_coverage" to false),
        )
        val fromAPhoneThatOnlySaysWhereAlarmsRing = mapOf<String, Any?>("alarm_routing" to "PHONE_ONLY")
        assertEquals(
            GlobalAlertSettings(AlarmRoutingMode.PHONE_ONLY, 9, false),
            AlertConfigSync.globalSettingsFrom(fromAPhoneThatOnlySaysWhereAlarmsRing, watch),
        )
    }

    @Test
    fun aModeThisBuildDoesNotKnowReadsAsBoth() {
        val entries = mapOf<String, Any?>("alarm_routing" to "SOMEWHERE_NEW")
        assertEquals(AlarmRoutingMode.BOTH, AlertConfigSync.globalSettingsFrom(entries, null).alarmRouting)
    }

    @Test
    fun theWatchStoresTheQuietPeriodThroughTheSameSanitiser() {
        val entries = mapOf<String, Any?>("same_direction_suppression_min" to 999)
        assertEquals(
            AlertDefaults.SAME_DIRECTION_SUPPRESSION_MAX_MINUTES,
            AlertConfigSync.globalSettingsFrom(entries, null).sameDirectionSuppressionMinutes,
        )
    }

    @Test
    fun anEntryOfTheWrongTypeRejectsTheLine() {
        val entries = mapOf<String, Any?>("acknowledged_high_coverage" to "yes")
        assertThrows(ClassCastException::class.java) { AlertConfigSync.globalSettingsFrom(entries, null) }
    }

    @Test
    fun theEncodingIsStableSoTheChangeHashOnlyMovesOnAChange() {
        assertEquals(AlertConfigSync.encodeGlobalLine(unusual), AlertConfigSync.encodeGlobalLine(unusual.copy()))
        assertTrue(
            AlertConfigSync.encodeGlobalLine(unusual) !=
                AlertConfigSync.encodeGlobalLine(unusual.copy(alarmRouting = AlarmRoutingMode.PHONE_ONLY))
        )
    }

    @Test
    fun theLineHasTheAlertLinesShape() {
        val line = AlertConfigSync.encodeGlobalLine(unusual)
        assertTrue(line, line.startsWith("g:alerts=v1,"))
        assertFalse("one line", line.contains('\n'))
    }

    @Test
    fun damagedOrForeignGlobalLinesAreSkipped() {
        val good = AlertConfigSync.encodeGlobalLine(unusual)
        val packed = Base64.getDecoder().decode(good.substringAfter(','))
        val truncated = Base64.getEncoder().encodeToString(packed.copyOf(packed.size / 2))
        listOf(
            "g:alerts=v1,$truncated",
            "g:alerts=v1,%%%",
            "g:alerts=v2,${good.substringAfter(',')}",
            "g:alerts=v1",
            "g:other=v1,${good.substringAfter(',')}",
            "c:1=v1,${good.substringAfter(',')}",
        ).forEach { assertNull(it, AlertConfigSync.decodeGlobalLine(it)) }
    }

    // ------------------------------------------------- inside the state message

    private val toggles = AlertType.settingsEntries.map {
        WearToggleSync.Toggle(WearToggleSync.SCOPE_ALERT, it.id.toString(), it.id % 2 == 1)
    } + WearToggleSync.Toggle(WearToggleSync.SCOPE_PREF, "prediction", true)

    private val alertLines = AlertType.settingsEntries.map {
        AlertConfigSync.encodeLine(AlertDefaults.defaultConfig(it, isMmol = true), 1)
    }

    private fun payload(global: GlobalAlertSettings = unusual): ByteArray =
        WearToggleSync.encode(toggles, alertLines + AlertConfigSync.encodeGlobalLine(global))

    /** WearToggleSync.decode as every watch before this one has it, protocol version 1. */
    private fun olderWatchDecode(data: ByteArray): List<WearToggleSync.Toggle> {
        val text = data.toString(Charsets.UTF_8)
        val declared = WearProtocol.declaredVersion(text)
        if (!(declared == null || declared <= 1)) return emptyList()
        return text.lineSequence().mapNotNull { line ->
            val scopeSplit = line.indexOf(':')
            val valueSplit = line.indexOf('=')
            if (scopeSplit <= 0 || valueSplit <= scopeSplit + 1) return@mapNotNull null
            val enabled = line.substring(valueSplit + 1).toBooleanStrictOrNull() ?: return@mapNotNull null
            WearToggleSync.Toggle(line.substring(0, scopeSplit), line.substring(scopeSplit + 1, valueSplit), enabled)
        }.toList()
    }

    @Test
    fun anOlderWatchDropsTheGlobalLineAndKeepsEverythingElse() {
        val message = payload()
        // Its value is no true/false, so the switch parser of every build skips it.
        assertEquals(toggles, olderWatchDecode(message))
        assertEquals(toggles, WearToggleSync.decode(message))
        // A watch with configuration lines but no global line reads "c:" lines only.
        assertEquals(AlertType.settingsEntries, AlertConfigSync.decode(message).map { it.type })
        assertNull(AlertConfigSync.decodeLine(AlertConfigSync.encodeGlobalLine(unusual)))
    }

    @Test
    fun theProtocolVersionIsNotBumped() {
        // A bump makes an older watch drop the whole message, switches included.
        assertEquals(1, WearProtocol.VERSION)
        assertTrue(payload().toString(Charsets.UTF_8).startsWith("v:1\n"))
    }

    @Test
    fun theWatchFindsTheGlobalLineInTheStateMessage() {
        val entries = AlertConfigSync.decodeGlobal(payload())
        assertNotNull(entries)
        assertEquals(unusual, AlertConfigSync.globalSettingsFrom(entries!!, null))
    }

    @Test
    fun aMessageWithoutAGlobalLineChangesNothing() {
        // From an older phone: the watch keeps what it has.
        assertNull(AlertConfigSync.decodeGlobal(WearToggleSync.encode(toggles, alertLines)))
        assertNull(AlertConfigSync.decodeGlobal(null))
        assertNull(AlertConfigSync.decodeGlobal(ByteArray(0)))
    }

    @Test
    fun aMessageFromANewerProtocolAppliesNoGlobalSettings() {
        val message = "v:${WearProtocol.VERSION + 1}\n${AlertConfigSync.encodeGlobalLine(unusual)}\n".toByteArray()
        assertNull(AlertConfigSync.decodeGlobal(message))
    }

    @Test
    fun aCommandFromTheWatchCarriesNoGlobalLine() {
        val command = WearToggleSync.encode(listOf(WearToggleSync.Toggle(WearToggleSync.SCOPE_ALERT, "1", true)))
        assertNull(AlertConfigSync.decodeGlobal(command))
    }
}
