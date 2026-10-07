package tk.glucodata.data.journal

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which insulin preset a received dose is filed under (JournalTreatmentTransfer.chooseInsulinPreset):
 * the preset the treatment names, else the first long-acting or rapid preset by the basal words.
 */
class JournalTreatmentPresetChoiceTests {
    private fun preset(
        id: Long,
        name: String,
        rapid: Boolean,
        sortOrder: Int,
        archived: Boolean = false,
    ) = JournalInsulinPreset(
        id = id,
        displayName = name,
        onsetMinutes = 0,
        durationMinutes = 60,
        accentColor = 0,
        curveJson = "0:0;30:1;60:0",
        isBuiltIn = false,
        isArchived = archived,
        countsTowardIob = rapid,
        sortOrder = sortOrder,
    )

    private val admelog = preset(1, "Admelog", rapid = true, sortOrder = 0)
    private val longGeneric = preset(2, "Long acting (generic)", rapid = false, sortOrder = 1)
    private val tresiba = preset(3, "Tresiba", rapid = false, sortOrder = 14)
    private val owners = listOf(admelog, longGeneric, tresiba)

    /** A dose as another app writes it: nothing says it is long-acting. */
    private fun dose(vararg fields: Pair<String, Any>) = JSONObject()
        .put("date", 1_786_794_604_000L)
        .put("eventType", "Correction Bolus")
        .put("insulin", 25)
        .apply { fields.forEach { (key, value) -> put(key, value) } }

    private fun choose(presets: List<JournalInsulinPreset>, treatment: JSONObject) =
        JournalTreatmentTransfer.chooseInsulinPreset(presets, treatment)

    @Test
    fun aDoseNamedInItsInsulinTypeGoesUnderThatPreset() {
        assertEquals(tresiba, choose(owners, dose("insulinType" to "Tresiba")))
    }

    @Test
    fun aDoseNamedInItsNotesGoesUnderThatPreset() {
        assertEquals(tresiba, choose(owners, dose("notes" to "Tresiba 100, evening")))
        assertEquals(tresiba, choose(owners, dose("note" to "evening tresiba")))
    }

    @Test
    fun aDoseNamedInItsEventTypeGoesUnderThatPreset() {
        assertEquals(tresiba, choose(owners, dose("eventType" to "Tresiba")))
    }

    @Test
    fun theNameWinsOverWhatTheBasalWordsSay() {
        assertEquals(
            "a document flagged rapid that names a long-acting insulin",
            tresiba,
            choose(owners, dose("insulinType" to "Tresiba", "isBasalInsulin" to false, "notes" to "Rapid-Acting")),
        )
        assertEquals(
            "a document flagged basal that names a rapid insulin",
            admelog,
            choose(owners, dose("insulinType" to "Admelog", "isBasalInsulin" to true)),
        )
    }

    @Test
    fun theReceivedDoseCarriesThePresetItNames() {
        val parsed = JournalTreatmentTransfer.parseTreatment(
            treatment = dose("_id" to "other-app-dose", "insulinType" to "Tresiba"),
            source = JournalEntrySource.NIGHTSCOUT,
            sourcePrefix = "nightscout:NSF-TEST",
            insulinPresets = owners,
            stringResource = { "Insulin" },
        )!!.inputs.single()

        assertEquals(tresiba.id, parsed.insulinPresetId)
        assertEquals("Tresiba", parsed.title)
    }

    @Test
    fun aBasalDoseThatNamesNoPresetGoesUnderTheFirstLongActingOne() {
        assertEquals(longGeneric, choose(owners, dose("notes" to "basal")))
        assertEquals(longGeneric, choose(owners, dose("isBasalInsulin" to true)))
    }

    @Test
    fun aDoseNamingAnInsulinWithNoPresetFallsBackToTheBasalWords() {
        assertEquals(admelog, choose(owners, dose("insulinType" to "Basaglar")))
        assertEquals(longGeneric, choose(owners, dose("insulinType" to "Basaglar", "isBasalInsulin" to true)))
        assertEquals(admelog, choose(owners, dose()))
    }

    @Test
    fun aNameCountsOnlyAsWholeWords() {
        assertEquals("not a word of its own", admelog, choose(owners, dose("notes" to "Tresibax")))
        assertEquals("not a word of its own", admelog, choose(owners, dose("notes" to "preTresiba")))
        assertEquals("punctuation is not part of a word", tresiba, choose(owners, dose("notes" to "(Tresiba)")))

        val novo = preset(10, "Novo", rapid = true, sortOrder = 2)
        val novoRapid = preset(11, "NovoRapid", rapid = true, sortOrder = 3)
        assertEquals(novoRapid, choose(owners + novo + novoRapid, dose("insulinType" to "NovoRapid")))
    }

    @Test
    fun ofOverlappingNamesTheLongestWins() {
        val humalog = preset(10, "Humalog", rapid = true, sortOrder = 2)
        val humalogMix = preset(11, "Humalog Mix 75/25", rapid = true, sortOrder = 3)
        val presets = owners + humalog + humalogMix
        assertEquals(humalogMix, choose(presets, dose("insulinType" to "Humalog Mix 75/25")))
        assertEquals(humalog, choose(presets, dose("insulinType" to "Humalog")))

        val novo = preset(12, "Novo", rapid = true, sortOrder = 4)
        val novoRapid = preset(13, "Novo Rapid", rapid = true, sortOrder = 5)
        assertEquals(novoRapid, choose(owners + novo + novoRapid, dose("insulinType" to "novo rapid")))
    }

    @Test
    fun twoPresetsNamedEquallyAreLeftToTheBasalWords() {
        val lantus = preset(10, "Lantus", rapid = false, sortOrder = 11)
        val toujeo = preset(11, "Toujeo", rapid = false, sortOrder = 12)
        val presets = owners + lantus + toujeo
        assertEquals(longGeneric, choose(presets, dose("notes" to "Lantus or Toujeo", "isBasalInsulin" to true)))
        assertEquals(admelog, choose(presets, dose("notes" to "Lantus or Toujeo")))
    }

    @Test
    fun anArchivedPresetIsUsedOnlyWhenNoActiveOneIsNamed() {
        val archivedLantus = preset(10, "Lantus / Basaglar / Semglee", rapid = false, sortOrder = 11, archived = true)
        assertEquals(
            "each brand of a built-in name counts, archived or not",
            archivedLantus,
            choose(owners + archivedLantus, dose("insulinType" to "Basaglar")),
        )

        val activeBasaglar = preset(11, "Basaglar", rapid = false, sortOrder = 20)
        assertEquals(activeBasaglar, choose(owners + archivedLantus + activeBasaglar, dose("insulinType" to "Basaglar")))

        val archivedTresiba = preset(12, "Tresiba", rapid = false, sortOrder = 30, archived = true)
        assertEquals(tresiba, choose(listOf(archivedTresiba) + owners, dose("insulinType" to "Tresiba")))
    }

    @Test
    fun namesAreMatchedWhateverTheirCase() {
        assertEquals(tresiba, choose(owners, dose("insulinType" to "TRESIBA")))
        assertEquals(tresiba, choose(owners, dose("notes" to "tresiba")))
        val mixedCase = preset(10, "NovoRapid", rapid = true, sortOrder = 2)
        assertEquals(mixedCase, choose(owners + mixedCase, dose("insulinType" to "NOVORAPID")))
    }

    @Test
    fun theInsulinTypeIsReadBeforeTheNotesAndTheNotesBeforeTheEventType() {
        val fiasp = preset(10, "Fiasp", rapid = true, sortOrder = 6)
        val presets = owners + fiasp
        assertEquals(fiasp, choose(presets, dose("insulinType" to "Fiasp", "notes" to "Tresiba moved to 22:00")))
        assertEquals(tresiba, choose(presets, dose("notes" to "Tresiba", "eventType" to "Fiasp")))

        val archivedHumalog = preset(11, "Humalog", rapid = true, sortOrder = 4, archived = true)
        assertEquals(
            "the insulin the document says it is, though only an archived preset has its name",
            archivedHumalog,
            choose(presets + archivedHumalog, dose("insulinType" to "Humalog", "notes" to "Tresiba later")),
        )
    }

    @Test
    fun aOneLetterNameIsNotLookedFor() {
        val r = preset(10, "R", rapid = true, sortOrder = 5)
        assertEquals(admelog, choose(owners + r, dose("notes" to "Humulin R", "isBasalInsulin" to false)))
    }

    @Test
    fun noPresetsChooseNothing() {
        assertEquals(null, choose(emptyList(), dose("insulinType" to "Tresiba")))
    }
}
