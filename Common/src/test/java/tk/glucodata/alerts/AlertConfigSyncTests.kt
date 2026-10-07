package tk.glucodata.alerts

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import tk.glucodata.WearProtocol
import tk.glucodata.WearToggleSync
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.Base64

/**
 * The wire the phone's alert configurations reach the watch on.
 *
 * What matters: the watch ends up with the phone's whole configuration for
 * every alert, whatever fields AlertConfig has; a field that belongs to one
 * device stays that device's; and nothing in a line can stop the switches, or
 * the other alerts, from arriving — on this build or on an older watch.
 */
class AlertConfigSyncTests {

    private val mmol = 1
    private val mgdl = 2

    /** A configuration unlike the defaults wherever the store keeps a value. */
    private fun edited(type: AlertType, isMmol: Boolean): AlertConfig {
        val d = AlertDefaults.defaultConfig(type, isMmol)
        return d.copy(
            enabled = !d.enabled,
            threshold = if (isMmol) 4.4f else 79f,
            durationMinutes = 45,
            forecastMinutes = 25,
            rearmMargin = if (isMmol) 0.3f else 6f,
            rearmMinIntervalMinutes = 12,
            iobCoverageFactor = 0.5f,
            fallRateSuppress = 1.5f,
            deltaThreshold = if (isMmol) 0.4f else 7f,
            deltaCount = 4,
            deltaBorder = if (isMmol) 7.2f else 130f,
            deltaIntervalMinutes = 1,
            earlyTriggerEnabled = true,
            deliveryMode = AlertDeliveryMode.BOTH,
            overrideDND = !d.overrideDND,
            soundEnabled = !d.soundEnabled,
            vibrationEnabled = !d.vibrationEnabled,
            hapticProfile = HapticProfile.ESCALATING,
            soundDelayEnabled = true,
            soundDelaySeconds = 20,
            defaultSnoozeMinutes = 25,
            alarmDurationSeconds = 17,
            activeStartHour = 22,
            activeStartMinute = 30,
            activeEndHour = 7,
            activeEndMinute = 15,
            timeRangeEnabled = true,
            retryEnabled = true,
            retryIntervalMinutes = 4,
            retryCount = 6,
            expiryWarningMinutes = if (type == AlertType.SENSOR_EXPIRY) setOf(720, 120) else d.expiryWarningMinutes,
        )
    }

    /** A watch store holding [values], read-only. */
    private fun watchStore(values: Map<String, Any?> = emptyMap()): SharedPreferences =
        OverlayPreferences(null, values)

    /** Phone config -> line -> what the watch reads over [watch]. */
    private fun transfer(config: AlertConfig, unit: Int, watch: SharedPreferences? = null): AlertConfig {
        val received = AlertConfigSync.decodeLine(AlertConfigSync.encodeLine(config, unit))
        assertNotNull("line for ${config.type} did not decode", received)
        assertEquals(unit, received!!.unit)
        assertEquals(config.type, received.type)
        return AlertConfigSync.configFrom(received, watch)
    }

    // ------------------------------------------------------------- round trip

    @Test
    fun everyAlertTheSettingsShowArrivesWhole() {
        for (unit in listOf(mmol, mgdl)) {
            for (type in AlertType.settingsEntries) {
                val phone = edited(type, isMmol = unit == mmol)
                assertEquals("$type in unit $unit", phone, transfer(phone, unit))
            }
        }
    }

    @Test
    fun theDefaultsArriveAsDefaults() {
        for (type in AlertType.settingsEntries) {
            val phone = AlertDefaults.defaultConfig(type, isMmol = true)
            assertEquals("$type", phone, transfer(phone, mmol))
        }
    }

    @Test
    fun hiddenLegacyOnlyAlertsAreNeverApplied() {
        // AVAILABLE and AMOUNT have no settings screen; the repository forces
        // AVAILABLE's native flag off, and a line must not bring it back.
        for (type in AlertType.entries.filterNot { it in AlertType.settingsEntries }) {
            val line = AlertConfigSync.encodeLine(AlertDefaults.defaultConfig(type, true).copy(enabled = true), mmol)
            assertNull("$type", AlertConfigSync.decodeLine(line))
        }
    }

    @Test
    fun nativeBackedAlertsCarryTheirSwitchAndThreshold() {
        // These keep enabled and threshold in the natives; the watch writes
        // them there through saveConfig, so both have to be on the line.
        val nativeBacked = listOf(
            AlertType.LOW, AlertType.HIGH, AlertType.VERY_LOW, AlertType.VERY_HIGH,
            AlertType.PRE_LOW, AlertType.PRE_HIGH, AlertType.LOSS,
        )
        for (type in nativeBacked) {
            val phone = AlertDefaults.defaultConfig(type, isMmol = true)
                .copy(enabled = true, threshold = 5.5f, alarmDurationSeconds = 9)
            val entries = AlertConfigSync.entriesOf(phone)
            assertEquals(true, entries["alert_${type.id}_enabled"])
            assertEquals(5.5f, entries["alert_${type.id}_threshold"])

            val watch = transfer(phone, mmol, watchStore(mapOf("alert_${type.id}_threshold" to 3.0f, "alert_${type.id}_enabled" to false)))
            assertTrue("$type", watch.enabled)
            assertEquals("$type", 5.5f, watch.threshold)
            assertEquals("$type", 9, watch.alarmDurationSeconds)
        }
    }

    @Test
    fun thePersistentHighTheOwnerSetReplacesTheWatchDefaults() {
        // The case seen on the devices: on with the phone at 13.0 for 90 min,
        // nights only; the watch ran 10.0 for 60 min around the clock.
        val type = AlertType.PERSISTENT_HIGH
        val phone = AlertDefaults.defaultConfig(type, isMmol = true).copy(
            enabled = true, threshold = 13.0f, durationMinutes = 90,
            timeRangeEnabled = true, activeStartHour = 22, activeStartMinute = 0, activeEndHour = 7, activeEndMinute = 0,
        )
        val watchDefaults = AlertDefaults.defaultConfig(type, isMmol = true).copy(enabled = true)
        val recorder = RecordingEditor()
        AlertRepository.writeConfigEntries(watchDefaults, recorder)

        val watch = transfer(phone, mmol, watchStore(recorder.entries))

        assertEquals(phone, watch)
        assertFalse("active only at night", watch.isActiveAtMinutes(12 * 60))
        assertTrue(watch.isActiveAtMinutes(23 * 60))
    }

    @Test
    fun theEncodingIsStableSoTheChangeHashOnlyMovesOnAChange() {
        val config = edited(AlertType.HIGH, isMmol = true)
        assertEquals(AlertConfigSync.encodeLine(config, mmol), AlertConfigSync.encodeLine(config.copy(), mmol))
        assertNotEquals(
            AlertConfigSync.encodeLine(config, mmol),
            AlertConfigSync.encodeLine(config.copy(defaultSnoozeMinutes = 26), mmol),
        )
    }

    // ------------------------------------------------- fields, present and future

    /** Kept by each device: see AlertRepository.deviceLocalKeys. */
    private val deviceLocalFields = setOf("customSoundUri", "flashEnabled")

    /** Fields the store sanitises to a set of values, so +1 is not a valid change. */
    private val validChange: Map<String, (Any?) -> Any?> = mapOf(
        "deltaIntervalMinutes" to { value -> if (value == 1) 5 else 1 },
    )

    private fun changed(field: Field, value: Any?): Any? {
        validChange[field.name]?.let { return it(value) }
        return when (field.type) {
            java.lang.Boolean.TYPE -> !(value as Boolean)
            Integer.TYPE -> (value as Int) + 1
            Integer::class.java -> ((value as Int?) ?: 0) + 1
            java.lang.Float.TYPE -> (value as Float) + 1.5f
            java.lang.Float::class.java -> ((value as Float?) ?: 0f) + 1.5f
            String::class.java -> "content://elsewhere/${field.name}"
            Set::class.java -> setOf(360)
            else -> if (field.type.isEnum) {
                val constants = field.type.enumConstants
                constants[(constants.indexOf(value) + 1) % constants.size]
            } else {
                fail("no test change for ${field.name}: ${field.type}"); null
            }
        }
    }

    @Test
    fun everyFieldCrossesWithoutTheSyncNamingIt() {
        // Reads AlertConfig's fields at run time, so a field added later is
        // covered here with no edit: it must arrive, or be device-local.
        val fields = AlertConfig::class.java.declaredFields
            .filter { !Modifier.isStatic(it.modifiers) && !it.isSynthetic }
            .onEach { it.isAccessible = true }
        val constructor = AlertConfig::class.java.declaredConstructors
            .single { it.parameterTypes.toList() == fields.map { field -> field.type } }

        for (type in AlertType.settingsEntries) {
            val base = edited(type, isMmol = true)
            fields.forEachIndexed { index, field ->
                if (field.name == "type") return@forEachIndexed
                // Stored for the sensor-expiry alert only.
                if (field.name == "expiryWarningMinutes" && type != AlertType.SENSOR_EXPIRY) return@forEachIndexed
                val args = fields.map { it.get(base) }.toTypedArray()
                args[index] = changed(field, args[index])
                val phone = constructor.newInstance(*args) as AlertConfig
                assertNotEquals("$type.${field.name} did not change", base, phone)

                val watch = transfer(phone, mmol)

                val expected = if (field.name in deviceLocalFields) field.get(base) else field.get(phone)
                assertEquals("$type.${field.name}", expected, field.get(watch))
            }
        }
    }

    @Test
    fun theWireCarriesAnyEntrySoANewStoreKeyNeedsNoSyncChange() {
        val entries = linkedMapOf<String, Any?>(
            "alert_10_enabled" to true,
            "alert_10_someFutureRate" to 2.5f,
            "alert_10_someFutureCount" to 3,
            "alert_10_someFutureMode" to "FAST",
            "alert_10_someFutureSet" to setOf("a", "b"),
            "alert_10_someFutureFlag" to false,
            "alert_10_someFutureStamp" to 1234567890123L,
            "alert_10_removed" to null,
        )
        val received = AlertConfigSync.decodeLine(AlertConfigSync.encodeLine(10, mmol, entries))!!
        assertEquals(entries, received.entries)

        // And the reader a future build adds sees it through the same view.
        val view = OverlayPreferences(null, received.entries)
        assertEquals(2.5f, view.getFloat("alert_10_someFutureRate", 0f))
        assertEquals(3, view.getInt("alert_10_someFutureCount", 0))
        assertFalse(view.contains("alert_10_removed"))
    }

    @Test
    fun anEntryThisBuildDoesNotKnowIsIgnored() {
        // From a newer phone: the reader here never asks for it.
        val phone = edited(AlertType.PERSISTENT_HIGH, isMmol = true)
        val entries = AlertConfigSync.entriesOf(phone) + ("alert_10_notInThisBuild" to 4.0f)
        val received = AlertConfigSync.decodeLine(AlertConfigSync.encodeLine(10, mmol, entries))!!
        assertEquals(phone, AlertConfigSync.configFrom(received, null))
    }

    // ------------------------------------------------------------- exclusions

    @Test
    fun theWatchKeepsItsOwnSoundAndFlash() {
        val type = AlertType.LOW
        val phone = edited(type, isMmol = true).copy(customSoundUri = "content://media/external/audio/media/42", flashEnabled = true)

        val entries = AlertConfigSync.entriesOf(phone)
        assertFalse("a phone URI is not sent", entries.containsKey("alert_${type.id}_soundUri"))
        assertFalse(entries.containsKey("alert_${type.id}_flash"))

        val watch = transfer(
            phone, mmol,
            watchStore(mapOf("alert_${type.id}_soundUri" to "android.resource://watch/raw/beep", "alert_${type.id}_flash" to false)),
        )
        assertEquals("android.resource://watch/raw/beep", watch.customSoundUri)
        assertFalse(watch.flashEnabled)
        assertEquals(phone.copy(customSoundUri = watch.customSoundUri, flashEnabled = false), watch)
    }

    @Test
    fun deviceLocalEntriesFromASenderThatSentThemAnywayAreIgnored() {
        val type = AlertType.HIGH
        val entries = AlertConfigSync.entriesOf(edited(type, true)) +
            mapOf("alert_${type.id}_soundUri" to "content://phone/only", "alert_${type.id}_flash" to true)
        val received = AlertConfigSync.decodeLine(AlertConfigSync.encodeLine(type.id, mmol, entries))!!
        val watch = AlertConfigSync.configFrom(received, watchStore(mapOf("alert_${type.id}_flash" to false)))
        assertNull(watch.customSoundUri)
        assertFalse(watch.flashEnabled)
    }

    @Test
    fun snoozesAndGivenWarningsAreNotConfiguration() {
        // Both live outside what saveConfig writes, so they never ride along.
        for (type in AlertType.settingsEntries) {
            val keys = AlertConfigSync.entriesOf(edited(type, true)).keys
            assertTrue("$type: $keys", keys.none { it.contains("snoozeUntil") || it.endsWith("_expiryWarned") })
            assertTrue("$type: every key belongs to this alert", keys.all { it.startsWith("alert_${type.id}_") })
        }
    }

    // ------------------------------------------------------------- robustness

    private fun lineFor(type: AlertType) = AlertConfigSync.encodeLine(edited(type, true), mmol)

    private fun body(json: String) = AlertConfigSync.packBody(json)

    @Test
    fun damagedBodiesAreRejected() {
        val packed = Base64.getDecoder().decode(lineFor(AlertType.LOW).substringAfter(','))
        val plain = Base64.getEncoder().encodeToString("""{"unit":1,"entries":{}}""".toByteArray())
        val truncated = Base64.getEncoder().encodeToString(packed.copyOf(packed.size / 2))
        val flipped = Base64.getEncoder().encodeToString(packed.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 0x55).toByte() })
        listOf(plain, truncated, flipped).forEach { damaged ->
            assertNull(AlertConfigSync.decodeLine("c:${AlertType.LOW.id}=v1,$damaged"))
        }
    }

    @Test
    fun unknownAlertIdsAreSkipped() {
        val good = lineFor(AlertType.HIGH)
        val future = good.replaceFirst("c:${AlertType.HIGH.id}=", "c:99=")
        assertNull(AlertConfigSync.decodeLine(future))
        val decoded = AlertConfigSync.decode("v:1\n$future\n$good\n".toByteArray())
        assertEquals(listOf(AlertType.HIGH), decoded.map { it.type })
    }

    @Test
    fun malformedLinesAreSkippedOneByOne() {
        val good = lineFor(AlertType.LOW)
        val bad = listOf(
            "c:1=v1,%%%not base64%%%",
            "c:1=v1,${body("not json")}",
            "c:1=v1,${body("""{"entries":{}}""")}",                          // no unit
            "c:1=v1,${body("""{"unit":1}""")}",                              // no entries
            "c:1=v1,${body("""{"unit":1,"entries":{"alert_1_enabled":"q1"}}""")}", // unknown value type
            "c:1=v1,${body("""{"unit":1,"entries":{"alert_1_enabled":"bmaybe"}}""")}",
            "c:1=v1,${body("""{"unit":1,"entries":{"alert_1_duration":"inope"}}""")}",
            "c:1=v1,${body("""{"unit":1,"entries":{"alert_1_duration":7}}""")}",   // untyped
            "c:1=v2,${good.substringAfter(',')}",                               // a later format
            "c:1=v1",
            "c:x=v1,abc",
            "c:1",
            "c:=v1,abc",
        )
        bad.forEach { assertNull(it, AlertConfigSync.decodeLine(it)) }
        val decoded = AlertConfigSync.decode(("v:1\n" + bad.joinToString("\n") + "\n$good\n").toByteArray())
        assertEquals(listOf(AlertType.LOW), decoded.map { it.type })
    }

    @Test
    fun anEntryOfTheWrongTypeRejectsItsAlertInsteadOfHalfReadingIt() {
        // A float threshold sent as an int: the reader throws, which onState
        // catches for that alert alone, leaving its current configuration.
        val type = AlertType.PERSISTENT_HIGH
        val entries = AlertConfigSync.entriesOf(edited(type, true)) + ("alert_${type.id}_threshold" to 13)
        val received = AlertConfigSync.decodeLine(AlertConfigSync.encodeLine(type.id, mmol, entries))!!
        assertThrows(ClassCastException::class.java) { AlertConfigSync.configFrom(received, null) }
    }

    @Test
    fun aMessageFromANewerProtocolAppliesNoConfiguration() {
        val payload = "v:${WearProtocol.VERSION + 1}\n${lineFor(AlertType.LOW)}\n".toByteArray()
        assertTrue(AlertConfigSync.decode(payload).isEmpty())
    }

    @Test
    fun aMessageFromAnOlderPhoneHasNoConfigurationAndThatIsFine() {
        val payload = WearToggleSync.encode(listOf(WearToggleSync.Toggle(WearToggleSync.SCOPE_ALERT, "1", true)))
        assertTrue(AlertConfigSync.decode(payload).isEmpty())
        assertTrue(AlertConfigSync.decode(null).isEmpty())
        assertTrue(AlertConfigSync.decode(ByteArray(0)).isEmpty())
    }

    // ------------------------------------------------------- the switches' wire

    /**
     * WearToggleSync.decode as an older watch has it (main at 54d423302),
     * frozen here with that build's protocol version, 1.
     */
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
    fun anOlderWatchStillGetsEverySwitchAndDropsTheConfigurationLines() {
        val toggles = AlertType.settingsEntries.map {
            WearToggleSync.Toggle(WearToggleSync.SCOPE_ALERT, it.id.toString(), it.id % 2 == 0)
        } + WearToggleSync.Toggle(WearToggleSync.SCOPE_PREF, "prediction", true)
        val lines = AlertType.settingsEntries.map { lineFor(it) }
        val payload = WearToggleSync.encode(toggles, lines)

        // If this fails after a protocol bump: an older watch drops the whole
        // message then, switches included. Version the line format instead.
        assertEquals(toggles, olderWatchDecode(payload))
        assertEquals(toggles, WearToggleSync.decode(payload))
        assertEquals(AlertType.settingsEntries, AlertConfigSync.decode(payload).map { it.type })
    }

    @Test
    fun aCommandFromTheWatchCarriesNoConfiguration() {
        val command = WearToggleSync.encode(listOf(WearToggleSync.Toggle(WearToggleSync.SCOPE_ALERT, "10", true)))
        assertFalse(command.toString(Charsets.UTF_8).contains("\nc:"))
    }

    // ------------------------------------------------------------------ units

    @Test
    fun theLineCarriesTheUnitItsValuesAreIn() {
        assertEquals(mmol, AlertConfigSync.decodeLine(AlertConfigSync.encodeLine(edited(AlertType.LOW, true), mmol))!!.unit)
        assertEquals(mgdl, AlertConfigSync.decodeLine(AlertConfigSync.encodeLine(edited(AlertType.LOW, false), mgdl))!!.unit)
    }

    @Test
    fun theWatchSwitchesUnitOnlyWhenItWouldReadTheValuesDifferently() {
        assertNull(AlertConfigSync.unitToAdopt(senderUnit = 1, localUnit = 1))
        assertNull(AlertConfigSync.unitToAdopt(senderUnit = 2, localUnit = 2))
        assertEquals(1, AlertConfigSync.unitToAdopt(senderUnit = 1, localUnit = 2))
        assertEquals(1, AlertConfigSync.unitToAdopt(senderUnit = 1, localUnit = 0))
        assertEquals(2, AlertConfigSync.unitToAdopt(senderUnit = 2, localUnit = 1))
        // 0 (never set) reads as mg/dL everywhere, so it only moves a watch off mmol/L.
        assertNull(AlertConfigSync.unitToAdopt(senderUnit = 0, localUnit = 2))
        assertEquals(2, AlertConfigSync.unitToAdopt(senderUnit = 0, localUnit = 1))
    }

    @Test
    fun keysTheSenderLeftOutFallBackToDefaultsInTheSendersUnit() {
        // A removed threshold reads as the default, and the default has to be
        // the one for the unit the line is in, not the watch's.
        val type = AlertType.PERSISTENT_HIGH
        val entries = AlertConfigSync.entriesOf(AlertDefaults.defaultConfig(type, isMmol = false)) +
            ("alert_${type.id}_threshold" to null)
        val received = AlertConfigSync.decodeLine(AlertConfigSync.encodeLine(type.id, mgdl, entries))!!
        assertEquals(AlertDefaults.PERSISTENT_HIGH_THRESHOLD_MGDL, AlertConfigSync.configFrom(received, null).threshold)
    }
}
