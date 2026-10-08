package tk.glucodata.ui.alerts

import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import tk.glucodata.AlertDeliveryPolicy
import tk.glucodata.R
import tk.glucodata.alerts.AlertConfig
import tk.glucodata.alerts.AlertDefaults
import tk.glucodata.alerts.AlertDeliveryMode
import tk.glucodata.alerts.AlertType
import tk.glucodata.alerts.CustomAlertConfig
import tk.glucodata.alerts.CustomAlertType
import tk.glucodata.alerts.GlobalAlertSettings
import tk.glucodata.alerts.HapticProfile
import tk.glucodata.alerts.resetToDefaults

class AlertSettingsDraftTests {
    private val isMmol = true

    private val quiet = QuietWindowSettings(
        mode = AlertDeliveryPolicy.QUIET_VIBRATE_ONLY,
        breakthroughMinutes = 10,
        breakthroughScope = AlertDeliveryPolicy.BREAKTHROUGH_ALL,
        defaultMinutes = 60,
    )

    private val customHigh = CustomAlertConfig(id = "c1", name = "Custom High 1", type = CustomAlertType.HIGH, threshold = 10f)

    private fun stored(
        configs: Map<AlertType, AlertConfig> = AlertType.settingsEntries.associateWith { AlertDefaults.defaultConfig(it, isMmol) },
        global: GlobalAlertSettings = GlobalAlertSettings(),
        quietWindow: QuietWindowSettings = quiet,
        customAlerts: List<CustomAlertConfig> = listOf(customHigh),
    ) = AlertSettingsValues(configs, global, quietWindow, customAlerts)

    private fun AlertSettingsDraft.config(type: AlertType): AlertConfig = draft.configs.getValue(type)

    /** Records what a Save writes. */
    private class RecordingStore : AlertSettingsStore {
        val configs = mutableListOf<AlertConfig>()
        val globals = mutableListOf<GlobalAlertSettings>()
        val quietWindows = mutableListOf<Pair<QuietWindowSettings, QuietWindowSettings>>()
        val customAlerts = mutableListOf<List<CustomAlertConfig>>()
        var batches = 0
        var openBatch = false

        override fun saveConfig(config: AlertConfig) {
            assertTrue("saved outside together", openBatch)
            configs += config
        }
        override fun saveGlobal(settings: GlobalAlertSettings) {
            assertTrue("saved outside together", openBatch)
            globals += settings
        }
        override fun saveQuietWindow(saved: QuietWindowSettings, settings: QuietWindowSettings) {
            assertTrue("saved outside together", openBatch)
            quietWindows += saved to settings
        }
        override fun saveCustomAlerts(saved: List<CustomAlertConfig>, draft: List<CustomAlertConfig>) {
            assertTrue("saved outside together", openBatch)
            customAlerts += draft
        }
        override fun together(block: () -> Unit) {
            batches++
            openBatch = true
            try {
                block()
            } finally {
                openBatch = false
            }
        }
    }

    private var defaultLocale: Locale? = null

    @Before
    fun pinLocale() {
        defaultLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }

    @After
    fun restoreLocale() {
        defaultLocale?.let { Locale.setDefault(it) }
    }

    // ---- dirty detection ----------------------------------------------------------

    @Test
    fun freshDraftIsClean() {
        val draft = AlertSettingsDraft(stored())
        assertFalse(draft.isDirty)
        assertTrue(draft.changes().isEmpty())
        assertTrue(draft.pendingEdits().isEmpty)
    }

    @Test
    fun anEditIsDirtyAndEditingBackIsClean() {
        val start = AlertSettingsDraft(stored())
        val low = start.config(AlertType.LOW)

        val edited = start.withConfig(low.copy(soundEnabled = false), isMmol)
        assertTrue(edited.isDirty)
        assertTrue(edited.isDirty(AlertType.LOW))
        assertFalse(edited.isDirty(AlertType.HIGH))

        val back = edited.withConfig(edited.config(AlertType.LOW).copy(soundEnabled = true), isMmol)
        assertFalse(back.isDirty)
    }

    @Test
    fun aFieldTheDraftDoesNotKnowStillCounts() {
        // The draft compares whole configs; a field it has no entry for is a change too.
        val start = AlertSettingsDraft(stored())
        val edited = start.withGlobal(start.draft.global.copy(acknowledgedHighCoverage = false))
        assertTrue(edited.isDirty)
        assertEquals(
            listOf(AlertSettingChange(AlertSettingSubject.AllAlerts, AlertSetting.OTHER)),
            edited.changes()
        )
    }

    @Test
    fun aThresholdBelowTheShownStepIsNoEdit() {
        // Native storage hands back whole mg/dl: 3.6 mmol/L comes back as 3.61.
        val configs = stored().configs.toMutableMap()
        configs[AlertType.LOW] = configs.getValue(AlertType.LOW).copy(threshold = 3.61f)
        val start = AlertSettingsDraft(stored(configs = configs))

        val touched = start.withConfig(start.config(AlertType.LOW).copy(threshold = 3.6f), isMmol)
        assertFalse(touched.isDirty)
        assertEquals(3.61f, touched.config(AlertType.LOW).threshold)

        val moved = start.withConfig(start.config(AlertType.LOW).copy(threshold = 3.8f), isMmol)
        assertTrue(moved.isDirty)
    }

    @Test
    fun aFallRuleSwitchedOffAgainIsNoEdit() {
        val start = AlertSettingsDraft(stored())
        assertNull(start.config(AlertType.HIGH).fallRateSuppress)
        val on = start.withConfig(start.config(AlertType.HIGH).copy(fallRateSuppress = 1f), isMmol)
        assertTrue(on.isDirty)
        val off = on.withConfig(on.config(AlertType.HIGH).copy(fallRateSuppress = 0f), isMmol)
        assertFalse(off.isDirty)
    }

    @Test
    fun aSnoozeTakenMeanwhileIsNoEdit() {
        val start = AlertSettingsDraft(stored())
        val edited = start.withCustomAlerts(listOf(customHigh.copy(snoozedUntil = 12345L)))
        assertFalse(edited.isDirty)
    }

    // ---- discard and reset --------------------------------------------------------

    @Test
    fun discardBringsBackWhatIsSaved() {
        val start = AlertSettingsDraft(stored())
        val edited = start
            .withConfig(start.config(AlertType.VERY_LOW).copy(enabled = false), isMmol)
            .withGlobal(GlobalAlertSettings(sameDirectionSuppressionMinutes = 12))
            .withQuietWindow(quiet.copy(breakthroughMinutes = 20))
            .withCustomAlerts(emptyList())
        assertTrue(edited.isDirty)

        val discarded = edited.discard()
        assertFalse(discarded.isDirty)
        assertEquals(start.saved, discarded.draft)
    }

    @Test
    fun resetToDefaultsOnlyEditsTheDraft() {
        val configs = stored().configs.toMutableMap()
        configs[AlertType.HIGH] = configs.getValue(AlertType.HIGH)
            .copy(threshold = 11f, hapticProfile = HapticProfile.SOFT, retryEnabled = true)
        val start = AlertSettingsDraft(stored(configs = configs))
        val store = RecordingStore()

        val reset = start.withConfig(start.config(AlertType.HIGH).resetToDefaults(isMmol), isMmol)
        assertTrue(reset.isDirty(AlertType.HIGH))
        assertEquals(AlertDefaults.defaultConfig(AlertType.HIGH, isMmol), reset.config(AlertType.HIGH))
        assertTrue("nothing written before Save", store.configs.isEmpty())

        // Discard undoes a reset like any other edit.
        assertEquals(configs.getValue(AlertType.HIGH), reset.discard().config(AlertType.HIGH))
    }

    // ---- save ---------------------------------------------------------------------

    @Test
    fun saveWritesEachChangedConfigOnceInOneBatch() {
        val start = AlertSettingsDraft(stored())
        val edited = start
            .withConfig(start.config(AlertType.LOW).copy(threshold = 4.2f), isMmol)
            .let { it.withConfig(it.config(AlertType.LOW).copy(soundEnabled = false), isMmol) }
            .let { it.withConfig(it.config(AlertType.HIGH).copy(enabled = false), isMmol) }
        val store = RecordingStore()

        val saved = edited.saveTo(store)

        assertEquals(1, store.batches)
        assertEquals(listOf(AlertType.LOW, AlertType.HIGH), store.configs.map { it.type }.sortedBy { it.id })
        assertEquals(edited.config(AlertType.LOW), store.configs.first { it.type == AlertType.LOW })
        assertTrue(store.globals.isEmpty())
        assertTrue(store.quietWindows.isEmpty())
        assertTrue(store.customAlerts.isEmpty())
        assertFalse(saved.isDirty)
        assertEquals(edited.draft, saved.saved)
    }

    @Test
    fun saveWritesTheOtherPartsOnlyWhenChanged() {
        val start = AlertSettingsDraft(stored())
        val newAlert = CustomAlertConfig(id = "c2", name = "Custom Low 1", type = CustomAlertType.LOW, threshold = 4f)
        val edited = start
            .withGlobal(GlobalAlertSettings(sameDirectionSuppressionMinutes = 0))
            .withQuietWindow(quiet.copy(mode = AlertDeliveryPolicy.QUIET_NOTIFICATION_ONLY))
            .withCustomAlerts(listOf(customHigh, newAlert))
        val store = RecordingStore()

        edited.saveTo(store)

        assertEquals(1, store.batches)
        assertTrue(store.configs.isEmpty())
        assertEquals(listOf(GlobalAlertSettings(sameDirectionSuppressionMinutes = 0)), store.globals)
        assertEquals(listOf(quiet to quiet.copy(mode = AlertDeliveryPolicy.QUIET_NOTIFICATION_ONLY)), store.quietWindows)
        assertEquals(listOf(listOf(customHigh, newAlert)), store.customAlerts)
    }

    @Test
    fun aCleanDraftSavesNothing() {
        val store = RecordingStore()
        AlertSettingsDraft(stored()).saveTo(store)
        assertEquals(0, store.batches)
        assertTrue(store.configs.isEmpty())
    }

    // ---- surviving the screen -----------------------------------------------------

    @Test
    fun rebaseFollowsWhatIsStoredForUntouchedParts() {
        val start = AlertSettingsDraft(stored())
        val edited = start.withConfig(start.config(AlertType.LOW).copy(threshold = 4.2f), isMmol)

        // Meanwhile the watch switched HIGH off and a quiet window stored another mode.
        val configs = stored().configs.toMutableMap()
        configs[AlertType.HIGH] = configs.getValue(AlertType.HIGH).copy(enabled = false)
        val now = stored(configs = configs, quietWindow = quiet.copy(mode = AlertDeliveryPolicy.QUIET_NOTIFICATION_ONLY))
        val rebased = edited.rebase(now, isMmol)

        assertFalse(rebased.config(AlertType.HIGH).enabled)
        assertFalse(rebased.isDirty(AlertType.HIGH))
        assertEquals(AlertDeliveryPolicy.QUIET_NOTIFICATION_ONLY, rebased.draft.quietWindow.mode)
        assertEquals(4.2f, rebased.config(AlertType.LOW).threshold)
        assertEquals(1, rebased.changes().size)
    }

    @Test
    fun pendingEditsHoldOnlyTheEditedPartsAndRestore() {
        val start = AlertSettingsDraft(stored())
        val edited = start
            .withConfig(start.config(AlertType.PRE_LOW).copy(enabled = true), isMmol)
            .withQuietWindow(quiet.copy(defaultMinutes = 120))
        val pending = edited.pendingEdits()

        assertEquals(setOf(AlertType.PRE_LOW), pending.configs.keys)
        assertNull(pending.global)
        assertEquals(quiet.copy(defaultMinutes = 120), pending.quietWindow)
        assertNull(pending.customAlerts)
        assertEquals(edited, AlertSettingsDraft.restore(stored(), pending, isMmol))
    }

    @Test
    fun pendingEditsSurviveTheirEncoding() {
        val start = AlertSettingsDraft(stored())
        val edited = start
            .withConfig(
                start.config(AlertType.SENSOR_EXPIRY).copy(enabled = true, expiryWarningMinutes = setOf(4320, 60)),
                isMmol
            )
            .withConfig(start.config(AlertType.LOW).copy(customSoundUri = "content://sound/1", deliveryMode = AlertDeliveryMode.BOTH), isMmol)
            .withGlobal(GlobalAlertSettings(sameDirectionSuppressionMinutes = 7, acknowledgedHighCoverage = false))
            .withQuietWindow(quiet.copy(breakthroughScope = AlertDeliveryPolicy.BREAKTHROUGH_VERY_ONLY))
            .withCustomAlerts(listOf(customHigh.copy(name = "Renamed", threshold = 12.5f, soundUri = null)))
        val pending = edited.pendingEdits()

        val decoded = PendingAlertEditsCodec.decode(PendingAlertEditsCodec.encode(pending))

        assertEquals(pending, decoded)
    }

    @Test
    fun anUnreadableEntryIsDroppedAndReported() {
        val start = AlertSettingsDraft(stored())
        val pending = start.withConfig(start.config(AlertType.LOW).copy(enabled = false), isMmol).pendingEdits()
        val entries = PendingAlertEditsCodec.encode(pending) + ("global" to "not base64 at all!")
        val unreadable = mutableListOf<String>()

        val decoded = PendingAlertEditsCodec.decode(entries) { key, _ -> unreadable += key }

        assertEquals(listOf("global"), unreadable)
        assertEquals(pending.configs, decoded.configs)
        assertNull(decoded.global)
    }

    // ---- custom alerts ------------------------------------------------------------

    @Test
    fun mergeKeepsWhatHappenedElsewhere() {
        val other = CustomAlertConfig(id = "c3", name = "Elsewhere", type = CustomAlertType.LOW)
        val untouched = CustomAlertConfig(id = "c4", name = "Untouched", type = CustomAlertType.LOW)
        val saved = listOf(customHigh, untouched)
        val draft = listOf(customHigh.copy(threshold = 13f), untouched)
        val current = listOf(customHigh.copy(snoozedUntil = 99L), untouched.copy(snoozedUntil = 77L), other)

        val merged = mergeCustomAlerts(saved, draft, current)

        assertEquals(
            listOf(customHigh.copy(threshold = 13f, snoozedUntil = 99L), untouched.copy(snoozedUntil = 77L), other),
            merged
        )
    }

    @Test
    fun mergeAppliesAddsAndRemoves() {
        val added = CustomAlertConfig(id = "c5", name = "New", type = CustomAlertType.HIGH)
        val merged = mergeCustomAlerts(saved = listOf(customHigh), draft = listOf(added), current = listOf(customHigh))
        assertEquals(listOf(added), merged)
    }

    // ---- the change summary -------------------------------------------------------

    @Test
    fun changesNameEachSettingWithBeforeAndAfter() {
        val start = AlertSettingsDraft(stored())
        val edited = start
            .withConfig(start.config(AlertType.LOW).copy(threshold = 4.2f, enabled = false), isMmol)
            .withGlobal(GlobalAlertSettings(sameDirectionSuppressionMinutes = 12))

        assertEquals(
            listOf(
                AlertSettingChange(AlertSettingSubject.Alert(AlertType.LOW), AlertSetting.ENABLED, true, false),
                AlertSettingChange(AlertSettingSubject.Alert(AlertType.LOW), AlertSetting.THRESHOLD, 3.6f, 4.2f),
                AlertSettingChange(AlertSettingSubject.AllAlerts, AlertSetting.SAME_DIRECTION_QUIET_PERIOD, 5, 12),
            ),
            edited.changes()
        )
    }

    @Test
    fun changesFollowThePageOrder() {
        val start = AlertSettingsDraft(stored())
        val edited = start
            .withConfig(start.config(AlertType.LOW).copy(enabled = false), isMmol)
            .let { it.withConfig(it.config(AlertType.HIGH).copy(enabled = false), isMmol) }

        val order = listOf(AlertType.HIGH, AlertType.LOW)
        assertEquals(
            order.map { AlertSettingSubject.Alert(it) },
            edited.changes(order).map { it.subject }
        )
    }

    @Test
    fun customAlertChangesSayAddedRemovedAndRenamed() {
        val start = AlertSettingsDraft(stored())
        val added = CustomAlertConfig(id = "c9", name = "Night low", type = CustomAlertType.LOW)
        val renamed = start.withCustomAlerts(listOf(customHigh.copy(name = "Evening high"), added))
        val removed = start.withCustomAlerts(emptyList())

        assertEquals(
            listOf(
                AlertSettingChange(
                    AlertSettingSubject.Custom("Evening high", CustomAlertType.HIGH),
                    AlertSetting.NAME,
                    "Custom High 1",
                    "Evening high"
                ),
                AlertSettingChange(AlertSettingSubject.Custom("Night low", CustomAlertType.LOW), AlertSetting.ADDED),
            ),
            renamed.changes()
        )
        assertEquals(
            listOf(AlertSettingChange(AlertSettingSubject.Custom("Custom High 1", CustomAlertType.HIGH), AlertSetting.REMOVED)),
            removed.changes()
        )
    }

    @Test
    fun theSummaryShowsSixThenHowManyMore() {
        val lines = (1..9).toList()
        assertEquals(listOf(1, 2, 3, 4, 5, 6) to 3, lines.limitTo(6))
        assertEquals(listOf(1, 2, 3) to 0, listOf(1, 2, 3).limitTo(6))
        assertEquals((1..6).toList() to 0, (1..6).toList().limitTo(6))
    }

    @Test
    fun summaryLinesReadAsSubjectSettingBeforeAfter() {
        val strings = mapOf(
            R.string.alert_settings_change_subject to "%1\$s · %2\$s",
            R.string.alert_settings_change_line to "%1\$s: %2\$s → %3\$s",
            AlertType.LOW.nameResId to "Low",
            R.string.threshold_label to "Threshold",
            R.string.enabled_status to "Enabled",
            R.string.disabled_status to "Disabled",
            R.string.alert_settings_other_settings to "Other settings",
            R.string.master_alert_control to "All alerts",
            R.string.same_direction_suppression_title to "Quiet period after a trend alert",
            R.string.minutes_short_format to "%1\$d min",
            R.string.off to "Off",
            R.string.alert_settings_added to "Added",
        )
        val text = AlertChangeText(
            isMmol = true,
            text = { id, args -> String.format(strings[id] ?: "?$id", *args) },
            soundName = { uri, _ -> uri ?: "default" },
            deltaDisplayIntervalMinutes = 5,
        )
        val low = AlertSettingSubject.Alert(AlertType.LOW)

        assertEquals("Low · Threshold: 3.9 → 4.2", text.line(AlertSettingChange(low, AlertSetting.THRESHOLD, 3.9f, 4.2f)))
        assertEquals("Low: Enabled → Disabled", text.line(AlertSettingChange(low, AlertSetting.ENABLED, true, false)))
        assertEquals("Low · Other settings", text.line(AlertSettingChange(low, AlertSetting.OTHER)))
        assertEquals(
            "All alerts · Quiet period after a trend alert: 5 min → Off",
            text.line(AlertSettingChange(AlertSettingSubject.AllAlerts, AlertSetting.SAME_DIRECTION_QUIET_PERIOD, 5, 0))
        )
        assertEquals(
            "Night low · Added",
            text.line(AlertSettingChange(AlertSettingSubject.Custom("Night low", CustomAlertType.LOW), AlertSetting.ADDED))
        )
    }

    // ---- markers ------------------------------------------------------------------

    @Test
    fun advancedIsMarkedOnlyForWhatSitsUnderIt() {
        val saved = AlertDefaults.defaultConfig(AlertType.PRE_HIGH, isMmol)
        assertFalse(saved.copy(threshold = 9f, soundEnabled = false, timeRangeEnabled = true).differsUnderAdvanced(saved))
        assertTrue(saved.copy(retryEnabled = true).differsUnderAdvanced(saved))
        assertTrue(saved.copy(iobCoverageFactor = 0.5f).differsUnderAdvanced(saved))
        assertTrue(saved.copy(defaultSnoozeMinutes = 45).differsUnderAdvanced(saved))
    }
}
