package tk.glucodata.alerts

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.WearToggleSync
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Inflater

/**
 * "On the watch": what the watch plays over an alarm's own sound and vibration
 * settings, which alarms that covers, and the global line that carries it to the
 * watch, including to a watch from before the setting.
 */
class WatchAlarmStyleTests {

    private val styles = WatchAlarmStyle.entries
    private val bools = listOf(true, false)

    // ------------------------------------------------------------ the decision

    /** The owner-approved table, written out rather than derived: (sound, vibrate). */
    private fun expected(style: WatchAlarmStyle, sound: Boolean, vibration: Boolean): Pair<Boolean, Boolean> =
        when (style) {
            WatchAlarmStyle.SAME_AS_PHONE -> sound to vibration
            WatchAlarmStyle.VIBRATE_ONLY -> false to true
            WatchAlarmStyle.SOUND_AND_VIBRATION -> true to true
            WatchAlarmStyle.SCREEN_ONLY -> false to false
        }

    @Test
    fun everyStyleOverEveryOwnSettingGivesTheApprovedEffects() {
        for (style in styles) for (sound in bools) for (vibration in bools) {
            val effects = WatchAlarmStyle.effective(style, sound, vibration)
            assertEquals(
                "$style over sound=$sound vibration=$vibration",
                expected(style, sound, vibration),
                effects.sound to effects.vibrate,
            )
        }
    }

    @Test
    fun sameAsPhoneKeepsEachAlertsOwnSettings() {
        for (sound in bools) for (vibration in bools) {
            assertEquals(
                WatchAlarmStyle.Effects(sound = sound, vibrate = vibration),
                WatchAlarmStyle.effective(WatchAlarmStyle.SAME_AS_PHONE, sound, vibration),
            )
        }
    }

    @Test
    fun vibrateOnlyVibratesSilentlyWhateverTheAlertSays() {
        // The owner's choice: the alert keeps its sound and vibration for the phone.
        val silentButBuzzing = WatchAlarmStyle.Effects(sound = false, vibrate = true)
        assertEquals(silentButBuzzing, WatchAlarmStyle.effective(WatchAlarmStyle.VIBRATE_ONLY, true, true))
        assertEquals(silentButBuzzing, WatchAlarmStyle.effective(WatchAlarmStyle.VIBRATE_ONLY, true, false))
        assertEquals(silentButBuzzing, WatchAlarmStyle.effective(WatchAlarmStyle.VIBRATE_ONLY, false, false))
    }

    @Test
    fun onlyStylesWithSoundSpeakTheAlarm() {
        assertTrue(WatchAlarmStyle.speaks(WatchAlarmStyle.SAME_AS_PHONE))
        assertTrue(WatchAlarmStyle.speaks(WatchAlarmStyle.SOUND_AND_VIBRATION))
        assertFalse(WatchAlarmStyle.speaks(WatchAlarmStyle.VIBRATE_ONLY))
        assertFalse(WatchAlarmStyle.speaks(WatchAlarmStyle.SCREEN_ONLY))
    }

    @Test
    fun theDefaultIsSameAsPhone() {
        assertEquals(WatchAlarmStyle.SAME_AS_PHONE, GlobalAlertSettings().watchAlarmStyle)
    }

    // ------------------------------------------------------- where it applies

    @Test
    fun itNeverAppliesOnThePhone() {
        for (type in AlertType.entries) {
            assertFalse(type.name, WatchAlarmStyle.appliesTo(onWatch = false, kind = type.id))
        }
    }

    @Test
    fun onTheWatchItAppliesToEveryAlarmWhereAlarmsRingRoutes() {
        for (type in AlertType.entries) {
            assertEquals(type.name, AlarmRouting.routes(type), WatchAlarmStyle.appliesTo(onWatch = true, kind = type.id))
        }
        listOf(
            AlertType.LOW, AlertType.HIGH, AlertType.VERY_LOW, AlertType.VERY_HIGH,
            AlertType.PRE_LOW, AlertType.PRE_HIGH, AlertType.FALLING_FAST, AlertType.RISING_FAST,
            AlertType.PERSISTENT_HIGH, AlertType.MISSED_READING, AlertType.LOSS,
        ).forEach { assertTrue(it.name, WatchAlarmStyle.appliesTo(onWatch = true, kind = it.id)) }
        // Not a glucose alarm, or a hidden legacy one: rings as before.
        listOf(AlertType.SENSOR_EXPIRY, AlertType.AVAILABLE, AlertType.AMOUNT)
            .forEach { assertFalse(it.name, WatchAlarmStyle.appliesTo(onWatch = true, kind = it.id)) }
    }

    @Test
    fun anAlarmThisBuildDoesNotKnowIsLeftAlone() {
        assertFalse(WatchAlarmStyle.appliesTo(onWatch = true, kind = 99))
        assertFalse(WatchAlarmStyle.appliesTo(onWatch = true, kind = -1))
    }

    // --------------------------------------------------------- the global line

    private fun roundTrip(settings: GlobalAlertSettings): GlobalAlertSettings {
        val entries = AlertConfigSync.decodeGlobalLine(AlertConfigSync.encodeGlobalLine(settings))
        assertNotNull("global line did not decode", entries)
        return AlertConfigSync.globalSettingsFrom(entries!!, null)
    }

    @Test
    fun everyStyleArrivesOnTheWatchWithTheOtherSharedSettings() {
        for (style in styles) for (mode in AlarmRoutingMode.entries) for (coverage in bools) {
            val phone = GlobalAlertSettings(mode, 17, coverage, style)
            assertEquals(phone, roundTrip(phone))
        }
    }

    @Test
    fun theStyleTravelsAsItsStoreEntry() {
        val entries = AlertConfigSync.globalEntriesOf(GlobalAlertSettings(watchAlarmStyle = WatchAlarmStyle.VIBRATE_ONLY))
        assertEquals("VIBRATE_ONLY", entries["watch_alarm_style"])
    }

    @Test
    fun theStyleArrivesInsideTheStateMessage() {
        val phone = GlobalAlertSettings(AlarmRoutingMode.BOTH, 5, true, WatchAlarmStyle.VIBRATE_ONLY)
        val toggles = listOf(WearToggleSync.Toggle(WearToggleSync.SCOPE_ALERT, "0", true))
        val message = WearToggleSync.encode(toggles, listOf(AlertConfigSync.encodeGlobalLine(phone)))
        val entries = AlertConfigSync.decodeGlobal(message)
        assertNotNull(entries)
        assertEquals(phone, AlertConfigSync.globalSettingsFrom(entries!!, null))
        assertEquals(toggles, WearToggleSync.decode(message))
    }

    @Test
    fun changingTheStyleChangesTheLineSoItIsSent() {
        val lines = styles.map { AlertConfigSync.encodeGlobalLine(GlobalAlertSettings(watchAlarmStyle = it)) }
        assertEquals(styles.size, lines.toSet().size)
    }

    @Test
    fun aMissingOrUnknownStyleIsSameAsPhone() {
        val fromAnOlderPhone = mapOf<String, Any?>("alarm_routing" to "PHONE_ONLY")
        assertEquals(
            WatchAlarmStyle.SAME_AS_PHONE,
            AlertConfigSync.globalSettingsFrom(fromAnOlderPhone, null).watchAlarmStyle,
        )
        assertEquals(
            WatchAlarmStyle.SAME_AS_PHONE,
            AlertConfigSync.globalSettingsFrom(mapOf("watch_alarm_style" to "BUZZ_TWICE"), null).watchAlarmStyle,
        )
        assertEquals(
            WatchAlarmStyle.SAME_AS_PHONE,
            AlertConfigSync.globalSettingsFrom(mapOf("watch_alarm_style" to null), null).watchAlarmStyle,
        )
    }

    @Test
    fun aLineWithoutTheStyleKeepsWhatTheWatchHas() {
        // The overlay rule every global key follows: a key the line lacks keeps the
        // watch's value. A watch never told anything reads SAME_AS_PHONE (above).
        val watch = OverlayPreferences(null, mapOf("watch_alarm_style" to "SCREEN_ONLY"))
        val fromAnOlderPhone = mapOf<String, Any?>("alarm_routing" to "PHONE_ONLY")
        assertEquals(
            WatchAlarmStyle.SCREEN_ONLY,
            AlertConfigSync.globalSettingsFrom(fromAnOlderPhone, watch).watchAlarmStyle,
        )
    }

    @Test
    fun aStyleOfTheWrongTypeRejectsTheLine() {
        assertThrows(ClassCastException::class.java) {
            AlertConfigSync.globalSettingsFrom(mapOf("watch_alarm_style" to 2), null)
        }
    }

    // ------------------------------------------------------- an older watch

    /** What a watch from before this setting takes from the global line. */
    private data class OlderWatchSettings(val routing: String?, val minutes: Int?, val coverage: Boolean?)

    /**
     * The global line as a watch from before this setting reads it, written out on
     * its own rather than through this build's decoder: base64, zlib, the typed
     * entries ('b', 'i', 'l', 'f', 's' or a string array; anything else rejects the
     * whole line), then the three keys it knows, by name. Null when it rejects the line.
     */
    private fun olderWatchReads(line: String): OlderWatchSettings? {
        val prefix = "g:alerts="
        if (!line.startsWith(prefix)) return null
        val value = line.removePrefix(prefix)
        if (value.substringBefore(',') != "v1") return null
        return runCatching {
            val inflater = Inflater()
            val out = ByteArrayOutputStream()
            try {
                inflater.setInput(Base64.getDecoder().decode(value.substringAfter(',')))
                val buffer = ByteArray(1024)
                while (!inflater.finished()) {
                    val count = inflater.inflate(buffer)
                    check(!(count == 0 && inflater.needsInput())) { "truncated" }
                    out.write(buffer, 0, count)
                }
            } finally {
                inflater.end()
            }
            val json = JSONObject(String(out.toByteArray(), Charsets.UTF_8)).getJSONObject("entries")
            val entries = HashMap<String, Any?>()
            json.keys().forEach { key ->
                entries[key] = if (json.isNull(key)) {
                    null
                } else {
                    when (val raw = json.get(key)) {
                        is JSONArray -> (0 until raw.length()).map { raw.getString(it) }.toSet()
                        is String -> {
                            val body = raw.drop(1)
                            when (raw.first()) {
                                'b' -> body.toBooleanStrict()
                                'i' -> body.toInt()
                                'l' -> body.toLong()
                                'f' -> body.toFloat()
                                's' -> body
                                else -> error("unknown value type in $raw")
                            }
                        }
                        else -> error("unreadable value for $key")
                    }
                }
            }
            OlderWatchSettings(
                entries["alarm_routing"] as String?,
                entries["same_direction_suppression_min"] as Int?,
                entries["acknowledged_high_coverage"] as Boolean?,
            )
        }.getOrNull()
    }

    @Test
    fun anOlderWatchReadsTheLineAndIgnoresTheStyle() {
        for (style in styles) {
            val line = AlertConfigSync.encodeGlobalLine(
                GlobalAlertSettings(AlarmRoutingMode.WATCH_WHEN_CONNECTED, 17, false, style)
            )
            assertEquals(style.name, OlderWatchSettings("WATCH_WHEN_CONNECTED", 17, false), olderWatchReads(line))
        }
    }

    @Test
    fun theOlderReaderIsAFairStandIn() {
        // It rejects what this build's decoder rejects, so the test above means something.
        val good = AlertConfigSync.encodeGlobalLine(GlobalAlertSettings())
        assertEquals(OlderWatchSettings("BOTH", AlertDefaults.SAME_DIRECTION_SUPPRESSION_MINUTES,
            AlertDefaults.ACKNOWLEDGED_HIGH_COVERAGE_ENABLED), olderWatchReads(good))
        val withAnUnknownValueType =
            "g:alerts=v1," + AlertConfigSync.packBody("""{"entries":{"alarm_routing":"xBOTH"}}""")
        assertNull(olderWatchReads(withAnUnknownValueType))
        assertNull(AlertConfigSync.decodeGlobalLine(withAnUnknownValueType))
    }

    @Test
    fun thisBuildIgnoresAKeyItDoesNotKnowTheSameWay() {
        // What an older watch meets in watch_alarm_style, this one meets in a later phone's key.
        val phone = GlobalAlertSettings(AlarmRoutingMode.PHONE_ONLY, 9, true, WatchAlarmStyle.SCREEN_ONLY)
        val entries = AlertConfigSync.globalEntriesOf(phone) + ("a_later_setting" to "ANYTHING")
        val decoded = AlertConfigSync.decodeGlobalLine(AlertConfigSync.encodeGlobalLine(entries))
        assertNotNull(decoded)
        assertTrue(decoded!!.containsKey("a_later_setting"))
        assertEquals(phone, AlertConfigSync.globalSettingsFrom(decoded, null))
    }
}
