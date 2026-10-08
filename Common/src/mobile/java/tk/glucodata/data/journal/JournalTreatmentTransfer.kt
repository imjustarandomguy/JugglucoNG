package tk.glucodata.data.journal

import android.content.Context
import org.json.JSONObject
import tk.glucodata.R
import java.security.MessageDigest
import java.text.ParseException
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

object JournalTreatmentTransfer {
    private const val SOURCE_KIND_CARBS = "carbs"
    private const val SOURCE_KIND_INSULIN = "insulin"
    private const val SOURCE_KIND_FINGERSTICK = "fingerstick"
    private const val SOURCE_KIND_ACTIVITY = "activity"
    private const val SOURCE_KIND_NOTE = "note"
    private const val MIN_VALID_EPOCH_MS = 946_684_800_000L
    private const val MGDL_PER_MMOLL = 18.0182f
    /** As the v1 upload's own lookup of a document by identifier allows. */
    private const val OWN_V1_DOCUMENT_TIME_TOLERANCE_MILLIS = 60_000L

    private val allKinds = listOf(
        SOURCE_KIND_CARBS,
        SOURCE_KIND_INSULIN,
        SOURCE_KIND_FINGERSTICK,
        SOURCE_KIND_ACTIVITY,
        SOURCE_KIND_NOTE
    )

    // The names a received document may carry each value under, in the order they are read.
    // An edit of a received treatment is written back under the name it was read from, so the
    // two must not drift apart: both use these.
    private val INSULIN_KEYS = arrayOf("insulin", "enteredInsulin", "enteredinsulin", "bolus")
    private val CARB_KEYS = arrayOf("carbs", "carb", "enteredCarbs", "enteredcarbs", "grams", "carbsGrams")
    private val PROTEIN_KEYS = arrayOf("protein", "proteinGrams")
    private val FAT_KEYS = arrayOf("fat", "fatGrams")
    private val MGDL_KEYS = arrayOf("glucoseValueMgDl", "glucose_mgdl", "mgdl", "mbg")
    private val MMOL_KEYS = arrayOf("glucose_mmol", "mmol")
    private val DURATION_MINUTES_KEYS = arrayOf("durationMinutes", "absorptionTime", "absorptionMinutes")
    private val EPOCH_TIME_KEYS = listOf("date", "mills", "millis", "timestamp", "time", "createdAt", "created_at_millis")
    private val DATE_STRING_KEYS = listOf("created_at", "dateString", "createdAt", "timestamp", "time")
    private const val MAX_UTC_OFFSET_MINUTES = 18 * 60

    data class ParsedTreatment(
        val inputs: List<JournalEntryInput>,
        val candidateSourceRecordIds: List<String>,
        val remoteId: String?,
        val deleteOnly: Boolean = false
    )

    fun buildTreatmentJson(
        entry: JournalEntryEntity,
        remoteId: String,
        preset: JournalInsulinPresetEntity?,
        food: JournalFoodEntity?,
        useV3: Boolean,
        includeRemoteId: Boolean = true
    ): JSONObject? {
        val type = JournalEntryType.fromStorage(entry.entryType)
        val timestamp = entry.timestamp.takeIf { it > 0L } ?: return null
        val json = JSONObject()
            .put("date", timestamp)
            .put("created_at", formatIso8601(timestamp))
            .put("utcOffset", 0)
            .put("isValid", true)
            .put("app", "JugglucoNG")
            .put("enteredBy", "JugglucoNG")
            .put("type", type.storageValue)
            .put("journalTitle", entry.title)
            .put("journalSource", entry.originSource ?: entry.source)
            .put("updated_at", formatIso8601(entry.updatedAt.takeIf { it > 0L } ?: timestamp))

        when (type) {
            JournalEntryType.INSULIN -> {
                val units = positiveAmount(entry.amount) ?: return null
                val isBasal = preset?.let { !it.countsTowardIob } ?: false
                json.put("eventType", "Correction Bolus")
                    .put("insulin", units.toDouble())
                    .put("isBasalInsulin", isBasal)
                    .put("insulinType", preset?.displayName ?: entry.title)
                    .put("notes", mergedNotes(if (isBasal) "Long-Acting" else "Rapid-Acting", entry.note))
                preset?.let {
                    json.put("insulinOnsetMinutes", it.onsetMinutes)
                        .put("insulinDurationMinutes", it.durationMinutes)
                }
            }
            JournalEntryType.CARBS -> {
                val grams = positiveAmount(entry.amount) ?: return null
                val durationMinutes = entry.durationMinutes
                    ?: food?.absorptionMinutes
                    ?: defaultAbsorptionMinutes(grams, entry.proteinGrams, entry.fatGrams)
                json.put("eventType", if (grams < 12f) "Carb Correction" else "Meal Bolus")
                    .put("carbs", grams.toDouble())
                    .put("food", food?.displayName ?: entry.title)
                    .put("duration", durationMinutes * 60_000L)
                    .put("durationInMilliseconds", durationMinutes * 60_000L)
                    .put("absorptionTime", durationMinutes)
                putFinite(json, "protein", entry.proteinGrams)
                putFinite(json, "proteinGrams", entry.proteinGrams)
                putFinite(json, "fat", entry.fatGrams)
                putFinite(json, "fatGrams", entry.fatGrams)
                entry.note?.takeIf { it.isNotBlank() }?.let { json.put("notes", it) }
            }
            JournalEntryType.FINGERSTICK -> {
                val mgdl = positiveAmount(entry.glucoseValueMgDl) ?: return null
                json.put("eventType", "BG Check")
                    .put("glucose", mgdl.toDouble())
                    .put("glucoseValueMgDl", mgdl.toDouble())
                    .put("glucoseType", "Finger")
                    .put("units", "mg/dl")
                entry.note?.takeIf { it.isNotBlank() }?.let { json.put("notes", it) }
            }
            JournalEntryType.ACTIVITY -> {
                json.put("eventType", "Exercise")
                    .put("duration", (entry.durationMinutes ?: 0).coerceAtLeast(0))
                    .put("intensity", entry.intensity.orEmpty())
                    .put("notes", mergedNotes(entry.title, entry.note))
            }
            JournalEntryType.NOTE -> {
                val note = mergedNotes(entry.title, entry.note) ?: return null
                json.put("eventType", "Note")
                    .put("notes", note)
            }
        }

        if (includeRemoteId) {
            json.put("_id", remoteId)
                .put("identifier", remoteId)
        }
        if (!useV3) {
            json.remove("identifier")
        }
        return json
    }

/**
     * The same document with the fields v3 will not let a client change taken out.
     *
     * v3 answers an update that carries them with 400 "Field date cannot be modified by the
     * client", and its collection POST rejects a payload with no date. This partial payload
     * therefore goes to PATCH /treatments/{identifier}; the route identifies the document,
     * while the body contains only fields the client may change.
     */
    fun stripImmutableForUpdate(json: JSONObject): JSONObject = json
        .apply {
            remove("date")
            remove("created_at")
            remove("utcOffset")
            // The PATCH route names the document; neither identity field belongs in its body.
            remove("_id")
            remove("identifier")
        }

    fun parseTreatment(
        context: Context,
        treatment: JSONObject,
        source: JournalEntrySource,
        sourcePrefix: String,
        insulinPresets: List<JournalInsulinPreset>
    ): ParsedTreatment? = parseTreatment(
        treatment = treatment,
        source = source,
        sourcePrefix = sourcePrefix,
        insulinPresets = insulinPresets,
        stringResource = context::getString
    )

    internal fun parseTreatment(
        treatment: JSONObject,
        source: JournalEntrySource,
        sourcePrefix: String,
        insulinPresets: List<JournalInsulinPreset>,
        stringResource: (Int) -> String
    ): ParsedTreatment? {
        val timestamp = treatment.optTreatmentTimestampMillis()
        val baseId = treatment.sourceBaseId(timestamp) ?: return null
        val remoteId = treatment.optRemoteId()
        val nightscoutRemoteId = when (source) {
            JournalEntrySource.NIGHTSCOUT -> remoteId
            JournalEntrySource.CLONE,
            JournalEntrySource.CLONE_LOCAL_ICE,
            JournalEntrySource.CLONE_TURN -> treatment.optNonBlankString("nsRemoteId")
            else -> null
        }
        val recoveryId = if (isCloneJournalSource(source)) {
            treatment.optNonBlankString("recoveryId")?.let { value ->
                CloneJournalIdentity.normalizeRecoveryId(value) ?: return null
            }
        } else {
            null
        }
        val candidateIds = allKinds.map { kind -> sourceRecordId(sourcePrefix, baseId, kind) }
        val isValid = treatment.optBoolean("isValid", true)
        if (!isValid) {
            return ParsedTreatment(
                inputs = emptyList(),
                candidateSourceRecordIds = candidateIds,
                remoteId = remoteId,
                deleteOnly = true
            )
        }

        val eventType = treatment.optNonBlankString("eventType", "eventtype", "event_type")
        val explicitType = treatment.optJournalEntryType()
        val note = buildNote(
            treatment.optNonBlankString("notes", "note"),
            treatment.optNonBlankString("enteredBy", "device", "app")
        )
        val originSource = treatment.optJournalOriginSource()
        val titleSuffix = treatment.optNonBlankString("journalTitle", "title", "food", "foodType")
            ?: eventType?.takeIf { !it.equals("Note", ignoreCase = true) }
        val inputs = ArrayList<JournalEntryInput>(3)
        val eventKey = eventType.orEmpty().lowercase(Locale.US)
        val safeTimestamp = timestamp ?: return null

        val carbs = treatment.optFiniteFloat(*CARB_KEYS)
            ?: treatment.optFiniteFloat("amount").takeIf {
                explicitType == JournalEntryType.CARBS || eventKey.contains("carb")
            }
        if (carbs != null && abs(carbs) >= 0.001f) {
            inputs.add(
                JournalEntryInput(
                    timestamp = safeTimestamp,
                    type = JournalEntryType.CARBS,
                    title = titleSuffix ?: stringResource(R.string.journal_aaps_carbs_title),
                    note = note,
                    amount = carbs,
                    durationMinutes = treatment.optDurationMinutes(),
                    proteinGrams = treatment.optPositiveFloat(*PROTEIN_KEYS),
                    fatGrams = treatment.optPositiveFloat(*FAT_KEYS),
                    source = source,
                    sourceRecordId = sourceRecordId(sourcePrefix, baseId, SOURCE_KIND_CARBS),
                    nsRemoteId = nightscoutRemoteId,
                    originSource = originSource,
                )
            )
        }

        val insulin = treatment.optPositiveFloat(*INSULIN_KEYS)
            ?: treatment.optPositiveFloat("amount").takeIf { explicitType == JournalEntryType.INSULIN }
        if (insulin != null) {
            val preset = chooseInsulinPreset(insulinPresets, treatment)
            inputs.add(
                JournalEntryInput(
                    timestamp = safeTimestamp,
                    type = JournalEntryType.INSULIN,
                    title = treatment.optNonBlankString("insulinType")
                        ?: preset?.displayName
                        ?: titleSuffix
                        ?: stringResource(R.string.journal_aaps_insulin_title),
                    note = note,
                    amount = insulin,
                    insulinPresetId = preset?.id,
                    source = source,
                    sourceRecordId = sourceRecordId(sourcePrefix, baseId, SOURCE_KIND_INSULIN),
                    nsRemoteId = nightscoutRemoteId,
                    originSource = originSource,
                )
            )
        }

        val glucoseMgdl = treatment.optGlucoseMgdl()
        if (glucoseMgdl != null &&
            (explicitType == JournalEntryType.FINGERSTICK ||
                eventKey.contains("bg check") ||
                eventKey.contains("finger") ||
                inputs.isEmpty())
        ) {
            inputs.add(
                JournalEntryInput(
                    timestamp = safeTimestamp,
                    type = JournalEntryType.FINGERSTICK,
                    title = titleSuffix ?: stringResource(R.string.journal_type_fingerstick),
                    note = note,
                    glucoseValueMgDl = glucoseMgdl,
                    source = source,
                    sourceRecordId = sourceRecordId(sourcePrefix, baseId, SOURCE_KIND_FINGERSTICK),
                    nsRemoteId = nightscoutRemoteId,
                    originSource = originSource,
                )
            )
        }

        if (explicitType == JournalEntryType.ACTIVITY || eventKey.contains("exercise")) {
            inputs.add(
                JournalEntryInput(
                    timestamp = safeTimestamp,
                    type = JournalEntryType.ACTIVITY,
                    title = titleSuffix ?: stringResource(R.string.journal_type_activity),
                    note = note,
                    durationMinutes = treatment.optDurationMinutes(),
                    intensity = treatment.optJournalIntensity(),
                    source = source,
                    sourceRecordId = sourceRecordId(sourcePrefix, baseId, SOURCE_KIND_ACTIVITY),
                    nsRemoteId = nightscoutRemoteId,
                    originSource = originSource,
                )
            )
        }

        if (inputs.isEmpty() &&
            (explicitType == JournalEntryType.NOTE || eventKey.contains("note") || eventKey.contains("announcement") || note != null)
        ) {
            inputs.add(
                JournalEntryInput(
                    timestamp = safeTimestamp,
                    type = JournalEntryType.NOTE,
                    title = titleSuffix ?: stringResource(R.string.journal_type_note),
                    note = note ?: eventType,
                    source = source,
                    sourceRecordId = sourceRecordId(sourcePrefix, baseId, SOURCE_KIND_NOTE),
                    nsRemoteId = nightscoutRemoteId,
                    originSource = originSource,
                )
            )
        }

        if (inputs.isEmpty() || (recoveryId != null && inputs.size != 1)) return null
        return ParsedTreatment(
            inputs = inputs.map { input -> input.copy(recoveryId = recoveryId) },
            candidateSourceRecordIds = candidateIds,
            remoteId = remoteId
        )
    }

    fun hasAnyRemoteIdentifier(treatment: JSONObject, remoteIds: Set<String>): Boolean {
        if (remoteIds.isEmpty()) return false
        return treatment.remoteIdentifiers().any { id -> id in remoteIds }
    }

    /** Every name the server knows this treatment by; the first is the one rows store. */
    fun remoteIdentifiersOf(treatment: JSONObject): List<String> = treatment.remoteIdentifiers()

    fun sourceRecordIdsForTreatment(treatment: JSONObject, sourcePrefix: String): List<String> {
        val baseId = treatment.sourceBaseId(treatment.optTreatmentTimestampMillis()) ?: return emptyList()
        return sourceRecordIdsForBaseId(sourcePrefix, baseId)
    }

    fun sourceRecordIdsForBaseId(sourcePrefix: String, baseId: String): List<String> =
        allKinds.map { kind -> sourceRecordId(sourcePrefix, baseId, kind) }

    internal fun sourceRecordIdForBaseId(
        sourcePrefix: String,
        baseId: String,
        type: JournalEntryType,
    ): String = sourceRecordId(sourcePrefix, baseId, type.storageValue)

    /**
     * Whether [treatment] is a document this install sent over API v1, as API v3 serves it.
     *
     * A v1 write is remembered by the _id Nightscout answered with, but v3 serves a document
     * that carries an identifier under that identifier alone, without the _id. So once the
     * uploader is switched to v3, its own v1 documents no longer match what it remembers and
     * were received back as new treatments: every recent dose and meal in the journal twice.
     * The v1 identifier is the bare row id, which another install, or this one after a
     * reinstall, also uses; it names this install's document only together with the row's time.
     *
     * @param ownV1Rows the time of each row this install uploaded, by its v1 identifier
     *        ([JournalTreatmentUploader.v1Identifier])
     */
    fun isOwnV1Document(treatment: JSONObject, ownV1Rows: Map<String, Long>): Boolean {
        if (ownV1Rows.isEmpty()) return false
        val rowTime = treatment.optNonBlankString("identifier")?.let(ownV1Rows::get) ?: return false
        val time = treatment.optTreatmentTimestampMillis() ?: return false
        return abs(time - rowTime) <= OWN_V1_DOCUMENT_TIME_TOLERANCE_MILLIS
    }

    /**
     * When [treatment] was last changed, as far as it says: API v3's srvModified, which v3 sets on
     * every write, else an `updated_at` its writer kept. Null when it says nothing; a document only
     * ever written over v1 usually carries neither.
     */
    fun serverModifiedMillis(treatment: JSONObject): Long? =
        treatment.optEpochMillis("srvModified")
            ?: treatment.optEpochMillis("updated_at")
            ?: treatment.optNonBlankString("updated_at")?.let(::parseDateString)

    /** Whether the app that wrote [treatment] marked it as not to be changed; v3 refuses (422) to. */
    fun isReadOnlyDocument(treatment: JSONObject): Boolean =
        treatment.optBoolean("isReadOnly", false) ||
            treatment.optBoolean("readOnly", false) ||
            treatment.optBoolean("readonly", false)

    /**
     * What an edit of a treatment received from Nightscout changes on the document it came from.
     *
     * @property fields what to set, each under the name the document already reads it from
     * @property timestampMillis the edited time when it is not the document's, else null
     */
    data class ReceivedEditChanges(val fields: JSONObject, val timestampMillis: Long?)

    /**
     * What has to change on [document], the treatment [entry] was received from, for it to say what
     * the edited row says; null when the document no longer holds a part of the row's kind (marked
     * deleted, or changed so that the part is gone), which leaves the server's copy to stand.
     *
     * Only what the journal lets the user edit is compared: the amount, the time, the note and the
     * fields of the row's own kind. Each change goes under the name this parser read the value
     * from, so the next receive reads the edit back and nothing else of the other app's is renamed
     * or rewritten. Titles and the insulin preset are the journal's own reading of a document and
     * are not sent. A value the row no longer has is not cleared on the server either: the editor
     * drops some fields (macros with macros off) without the user having asked to.
     */
    fun receivedEditChanges(entry: JournalEntryEntity, document: JSONObject): ReceivedEditChanges? {
        val kind = JournalEntryType.fromStorage(entry.entryType)
        val parsed = parseTreatment(
            treatment = document,
            source = JournalEntrySource.NIGHTSCOUT,
            sourcePrefix = "edit",
            insulinPresets = emptyList(),
            stringResource = { "" }
        ) ?: return null
        if (parsed.deleteOnly) return null
        val server = parsed.inputs.firstOrNull { it.type == kind } ?: return null
        val fields = JSONObject()
        when (kind) {
            JournalEntryType.INSULIN ->
                fields.putChangedValue(document, entry.amount, server.amount, INSULIN_KEYS)
            JournalEntryType.CARBS -> {
                fields.putChangedValue(document, entry.amount, server.amount, CARB_KEYS)
                // Not "duration": in an AAPS document that is how long extended carbs last.
                fields.putChangedDuration(document, entry.durationMinutes, server.durationMinutes, "absorptionTime")
                fields.putChangedValue(document, entry.proteinGrams, server.proteinGrams, PROTEIN_KEYS)
                fields.putChangedValue(document, entry.fatGrams, server.fatGrams, FAT_KEYS)
            }
            JournalEntryType.FINGERSTICK ->
                fields.putChangedGlucose(document, entry.glucoseValueMgDl, server.glucoseValueMgDl)
            JournalEntryType.ACTIVITY -> {
                fields.putChangedDuration(document, entry.durationMinutes, server.durationMinutes, "duration")
                val intensity = JournalIntensity.fromStorage(entry.intensity)
                if (intensity != null && intensity != server.intensity) fields.put("intensity", intensity.storageValue)
            }
            JournalEntryType.NOTE -> Unit
        }
        val note = entry.note.normalizedNote()
        if (note != server.note.normalizedNote()) {
            // The receive shows who entered a treatment as the note's last part; that part is the
            // document's enteredBy, not its notes, and writing it into them would repeat it.
            val source = document.optNonBlankString("enteredBy", "device", "app")
            fields.put("notes", withoutSourceLabel(note, source).orEmpty())
        }
        return ReceivedEditChanges(fields, entry.timestamp.takeIf { it != server.timestamp })
    }

    /**
     * [document] as API v1 must be sent to change it. v1's PUT replaces the stored document whole
     * (by its _id), so everything the other app wrote goes back as it was, enteredBy, app and
     * identifier included, with only [changes] applied.
     */
    fun receivedEditV1Document(document: JSONObject, changes: ReceivedEditChanges, nowMillis: Long): JSONObject {
        val updated = JSONObject(document.toString())
        for (key in changes.fields.keys()) {
            updated.put(key, changes.fields.get(key))
        }
        changes.timestampMillis?.let { updated.putTimestamp(it) }
        // v1 recomputes utcOffset from the zone created_at is written in on every save, so a time
        // in UTC would reset the other app's offset to zero. Written in that offset, it is kept.
        val offset = updated.optUtcOffsetMinutes()
        val millis = changes.timestampMillis ?: updated.optTreatmentTimestampMillis()
        if (offset != null && offset != 0 && millis != null) {
            updated.put("created_at", formatIso8601(millis, offset))
        }
        // v1 keeps no modification time of its own. A document v3 also serves carries v3's, and a
        // v3 reader (AAPS's sync among them) only sees a change that moves it.
        if (updated.has("srvModified")) updated.put("srvModified", nowMillis)
        return updated
    }

    private fun sourceRecordId(sourcePrefix: String, baseId: String, kind: String): String =
        "$sourcePrefix:$baseId:$kind"

    /**
     * The preset a received dose is filed under: the one it names ([presetNamedBy]), else, as
     * before, the first long-acting preset when it reads as basal and the first rapid one when not.
     */
    internal fun chooseInsulinPreset(
        presets: List<JournalInsulinPreset>,
        treatment: JSONObject
    ): JournalInsulinPreset? {
        presetNamedBy(presets, treatment)?.let { return it }
        val text = listOfNotNull(
            treatment.optNonBlankString("eventType", "eventtype"),
            treatment.optNonBlankString("notes", "note"),
            treatment.optNonBlankString("insulinType", "type"),
            treatment.optNonBlankString("enteredBy", "device", "app")
        ).joinToString(" ").lowercase(Locale.US)
        val isBasal = treatment.optBoolean("isBasalInsulin", false) ||
            text.contains("basal") ||
            text.contains("long") ||
            text.contains("nph")
        val candidates = presets.filter { !it.isArchived }
            .ifEmpty { presets }
        return if (isBasal) {
            candidates.filter { !it.countsTowardIob }.minByOrNull { it.sortOrder }
                ?: candidates.minByOrNull { it.sortOrder }
        } else {
            candidates.filter { it.countsTowardIob }.minByOrNull { it.sortOrder }
                ?: candidates.minByOrNull { it.sortOrder }
        }
    }

    /**
     * The one preset [treatment] names, or null to leave the choice to the basal words.
     *
     * Another app that writes a dose usually names the insulin (insulinType "Tresiba", or the name
     * in its notes) without saying it is long-acting; filed by the basal words alone, such a dose
     * went under the first rapid preset and counted toward IOB.
     *
     * insulinType is read first, then the notes, then eventType; the first of them that names a
     * preset decides. A name counts when its words stand in the field as whole words, in order,
     * case ignored: "Tresiba" in "tresiba 100", not "Novo" in "NovoRapid". A built-in name that
     * lists brands ("Lantus / Basaglar / Semglee") also counts for each brand alone. An active
     * preset named comes before an archived one, then the longest name ("Humalog Mix 75/25" over
     * "Humalog"); two presets named equally are left to the basal words.
     */
    private fun presetNamedBy(
        presets: List<JournalInsulinPreset>,
        treatment: JSONObject
    ): JournalInsulinPreset? {
        if (presets.isEmpty()) return null
        val fields = listOfNotNull(
            treatment.optNonBlankString("insulinType"),
            treatment.optNonBlankString("notes", "note"),
            treatment.optNonBlankString("eventType", "eventtype", "event_type")
        )
        if (fields.isEmpty()) return null
        val namesOfPresets = presets.map { preset -> preset to presetNames(preset) }
        for (field in fields) {
            val fieldWords = words(field)
            for (archived in listOf(false, true)) {
                // Each preset named in this field, with the length of the longest of its names there.
                val named = namesOfPresets
                    .filter { (preset, _) -> preset.isArchived == archived }
                    .mapNotNull { (preset, names) ->
                        names.filter { name -> Collections.indexOfSubList(fieldWords, name) >= 0 }
                            .maxOfOrNull { name -> name.sumOf(String::length) }
                            ?.let { length -> preset to length }
                    }
                if (named.isEmpty()) continue
                val longest = named.maxOf { it.second }
                return named.filter { it.second == longest }.singleOrNull()?.first
            }
        }
        return null
    }

    /**
     * The names [preset] goes by, each as its words: the whole name, and each brand of a built-in
     * name that lists them with " / " ("Humalog Mix 50/50" is one name). A name without a letter,
     * or of one character, is left out: it would be found in too many notes.
     */
    private fun presetNames(preset: JournalInsulinPreset): List<List<String>> =
        (listOf(preset.displayName) + preset.displayName.split(BRAND_SEPARATOR))
            .map(::words)
            .filter { name -> name.sumOf(String::length) >= 2 && name.any { word -> word.any(Char::isLetter) } }
            .distinct()

    private val BRAND_SEPARATOR = Regex("\\s+/\\s+")
    private val WORD = Regex("[\\p{L}\\p{N}]+")

    /** [text]'s words, lowercased: its runs of letters and digits. */
    private fun words(text: String): List<String> =
        WORD.findAll(text.lowercase(Locale.ROOT)).map { it.value }.toList()

    private fun JSONObject.optRemoteId(): String? =
        remoteIdentifiers().firstOrNull()

    private fun JSONObject.remoteIdentifiers(): List<String> =
        listOfNotNull(
            optNonBlankString("identifier"),
            optNonBlankString("_id"),
            optNonBlankString("id"),
            optNonBlankString("NSCLIENT_ID"),
            optNonBlankString("pumpId")
        ).distinct()

    private fun JSONObject.sourceBaseId(timestamp: Long?): String? {
        optRemoteId()?.let { return it }
        if (timestamp == null) return null
        val fingerprint = listOf(
            timestamp,
            optNonBlankString("eventType", "eventtype", "type").orEmpty(),
            optFiniteFloat("carbs", "carb", "enteredCarbs", "enteredcarbs", "grams", "amount")?.toString().orEmpty(),
            optPositiveFloat("insulin", "enteredInsulin", "enteredinsulin", "bolus", "amount")?.toString().orEmpty(),
            optGlucoseMgdl()?.toString().orEmpty(),
            optNonBlankString("notes", "note").orEmpty()
        ).joinToString("|")
        return "hash:${fingerprint.sha256Short()}"
    }

    private fun JSONObject.optTreatmentTimestampMillis(): Long? {
        for (key in EPOCH_TIME_KEYS) {
            val normalized = optEpochMillis(key)
            if (normalized != null) return normalized
        }
        for (key in DATE_STRING_KEYS) {
            val parsed = optNonBlankString(key)?.let(::parseDateString)
            if (parsed != null) return parsed
        }
        return null
    }

    private fun JSONObject.optEpochMillis(key: String): Long? {
        if (!has(key) || isNull(key)) return null
        val value = opt(key)
        val longValue = when (value) {
            is Number -> value.toDouble().takeIf { it.isFinite() }?.toLong()
            is String -> value.trim().toDoubleOrNull()?.toLong()
            else -> null
        } ?: return null
        val millis = when (longValue) {
            in 1L until 10_000_000_000L -> longValue * 1000L
            in 10_000_000_000L until 100_000_000_000_000L -> longValue
            in 100_000_000_000_000L until 100_000_000_000_000_000L -> longValue / 1000L
            else -> longValue
        }
        return millis.takeIf { it >= MIN_VALID_EPOCH_MS }
    }

    private fun parseDateString(text: String): Long? {
        try {
            return Instant.parse(text).toEpochMilli().takeIf { it >= MIN_VALID_EPOCH_MS }
        } catch (ignored: DateTimeParseException) {
        }

        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSX",
            "yyyy-MM-dd'T'HH:mm:ssX",
            "yyyy-MM-dd HH:mm:ss"
        )
        for (pattern in patterns) {
            try {
                val format = SimpleDateFormat(pattern, Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                return format.parse(text)?.time?.takeIf { it >= MIN_VALID_EPOCH_MS }
            } catch (ignored: ParseException) {
            }
        }
        return null
    }

    private fun JSONObject.optDurationMinutes(): Int? {
        optFiniteFloat("durationInMilliseconds")?.let { millis ->
            if (millis > 0f) return (millis / 60_000f).toInt().coerceIn(1, 24 * 60)
        }
        optFiniteFloat(*DURATION_MINUTES_KEYS)?.let { minutes ->
            if (minutes > 0f) return minutes.toInt().coerceIn(1, 24 * 60)
        }
        val duration = optFiniteFloat("duration") ?: return null
        if (duration <= 0f) return null
        val minutes = if (duration > 24f * 60f) duration / 60_000f else duration
        return minutes.toInt().coerceIn(1, 24 * 60)
    }

    private fun JSONObject.optJournalEntryType(): JournalEntryType? {
        for (key in listOf("type", "entryType", "journalType")) {
            val value = optNonBlankString(key)?.lowercase(Locale.US) ?: continue
            JournalEntryType.entries.firstOrNull { it.storageValue == value }?.let { return it }
        }
        return null
    }

    private fun JSONObject.optJournalOriginSource(): JournalEntrySource? {
        val value = optNonBlankString("journalSource", "originSource") ?: return null
        return JournalEntrySource.entries.firstOrNull { it.storageValue == value.lowercase(Locale.US) }
    }

    private fun JSONObject.optJournalIntensity(): JournalIntensity? {
        val value = optNonBlankString("intensity")?.lowercase(Locale.US) ?: return null
        return JournalIntensity.fromStorage(value)
    }

    private fun JSONObject.optGlucoseMgdl(): Float? {
        firstFiniteField(*MGDL_KEYS)?.let { return it }
        firstFiniteField(*MMOL_KEYS)?.let { return it * MGDL_PER_MMOLL }
        val glucose = firstFiniteField("glucose") ?: return null
        val units = optNonBlankString("units", "unit")
            ?.lowercase(Locale.US)
            .orEmpty()
        return if (units.contains("mmol")) glucose * MGDL_PER_MMOLL else glucose
    }

    private fun JSONObject.firstFiniteField(vararg keys: String): Float? =
        keys.asSequence()
            .mapNotNull { key -> optFiniteFloat(key) }
            .firstOrNull { it > 0f }

    private fun JSONObject.optPositiveFloat(vararg keys: String): Float? =
        optFiniteFloat(*keys)?.takeIf { it > 0.0001f }

    private fun JSONObject.optFiniteFloat(vararg keys: String): Float? {
        for (key in keys) {
            if (!has(key) || isNull(key)) continue
            val value = opt(key)
            val parsed = when (value) {
                is Number -> value.toFloat()
                is String -> value.trim().replace(',', '.').toFloatOrNull()
                else -> null
            }
            if (parsed != null && parsed.isFinite()) return parsed
        }
        return null
    }

    private fun JSONObject.optNonBlankString(vararg keys: String): String? {
        for (key in keys) {
            if (!has(key) || isNull(key)) continue
            val text = optString(key, "").trim()
            if (text.isNotBlank() && text != "null") return text
        }
        return null
    }

    private fun positiveAmount(value: Float?): Float? =
        value?.takeIf { it.isFinite() && it > 0f }

    private fun putFinite(json: JSONObject, key: String, value: Float?) {
        if (value != null && value.isFinite()) {
            json.put(key, value.toDouble())
        }
    }

    private fun mergedNotes(vararg parts: String?): String? {
        val unique = LinkedHashSet<String>()
        parts
            .flatMap { it.orEmpty().split('\n') }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .forEach(unique::add)
        return unique.joinToString(" | ").takeIf { it.isNotBlank() }
    }

    private fun buildNote(vararg parts: String?): String? = mergedNotes(*parts)

    /** The first of [keys] holding a number, which is the one [optFiniteFloat] reads. */
    private fun JSONObject.firstFiniteKey(keys: Array<String>): String? =
        keys.firstOrNull { optFiniteFloat(it) != null }

    /**
     * Puts [edited] under the name [document] reads it from (the first of [keys] it holds a number
     * under, else the first of [keys]) when it differs from what the document says.
     */
    private fun JSONObject.putChangedValue(document: JSONObject, edited: Float?, server: Float?, keys: Array<String>) {
        if (edited == null || !edited.isFinite() || edited <= 0f || sameValue(edited, server)) return
        put(document.firstFiniteKey(keys) ?: keys.first(), edited.toCleanDouble())
    }

    private fun JSONObject.putChangedDuration(document: JSONObject, edited: Int?, server: Int?, defaultKey: String) {
        if (edited == null || edited <= 0 || edited == server) return
        val (key, inMillis) = document.durationField() ?: (defaultKey to false)
        put(key, if (inMillis) edited * 60_000L else edited.toLong())
    }

    /** The field [optDurationMinutes] reads, and whether it holds milliseconds. */
    private fun JSONObject.durationField(): Pair<String, Boolean>? {
        if ((optFiniteFloat("durationInMilliseconds") ?: 0f) > 0f) return "durationInMilliseconds" to true
        val minutesKey = firstFiniteKey(DURATION_MINUTES_KEYS)
        if (minutesKey != null && (optFiniteFloat(minutesKey) ?: 0f) > 0f) return minutesKey to false
        val duration = optFiniteFloat("duration") ?: return null
        return if (duration > 0f) "duration" to (duration > 24f * 60f) else null
    }

    /** As [optGlucoseMgdl] reads it: the first positive mg/dL field, else mmol/L, else glucose. */
    private fun JSONObject.putChangedGlucose(document: JSONObject, editedMgdl: Float?, serverMgdl: Float?) {
        if (editedMgdl == null || !editedMgdl.isFinite() || editedMgdl <= 0f || sameValue(editedMgdl, serverMgdl)) return
        fun positive(key: String) = (document.optFiniteFloat(key) ?: 0f) > 0f
        val mmol = (editedMgdl / MGDL_PER_MMOLL * 100f).roundToLong() / 100.0
        MGDL_KEYS.firstOrNull(::positive)?.let { put(it, editedMgdl.toCleanDouble()); return }
        MMOL_KEYS.firstOrNull(::positive)?.let { put(it, mmol); return }
        val inMmol = document.optNonBlankString("units", "unit")?.lowercase(Locale.US)?.contains("mmol") == true
        put("glucose", if (inMmol) mmol else editedMgdl.toCleanDouble())
    }

    private fun sameValue(a: Float, b: Float?): Boolean = b != null && abs(a - b) < 0.001f

    /** 0.1f as 0.1 rather than 0.10000000149011612. */
    private fun Float.toCleanDouble(): Double = toString().toDouble()

    private fun String?.normalizedNote(): String? = this?.trim()?.takeIf { it.isNotBlank() }

    /** [note] without the trailing " | [source]" [buildNote] adds to a received treatment's note. */
    internal fun withoutSourceLabel(note: String?, source: String?): String? {
        if (note == null || source.isNullOrBlank()) return note
        if (note == source) return null
        return note.removeSuffix(" | $source").trim().takeIf { it.isNotBlank() }
    }

    /** Moves every time field [optTreatmentTimestampMillis] could read, and created_at, to [millis]. */
    private fun JSONObject.putTimestamp(millis: Long) {
        for (key in EPOCH_TIME_KEYS) {
            if (optEpochMillis(key) != null) put(key, millis)
        }
        val iso = formatIso8601(millis)
        for (key in DATE_STRING_KEYS) {
            if (optEpochMillis(key) == null && optNonBlankString(key)?.let(::parseDateString) != null) put(key, iso)
        }
        put("created_at", iso)
    }

    /** Nightscout's utcOffset, in minutes; null when absent or out of range. */
    private fun JSONObject.optUtcOffsetMinutes(): Int? {
        val value = optFiniteFloat("utcOffset") ?: return null
        val minutes = value.roundToInt()
        return minutes.takeIf { abs(it) <= MAX_UTC_OFFSET_MINUTES }
    }

    private fun formatIso8601(epochMillis: Long, offsetMinutes: Int): String =
        OffsetDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneOffset.ofTotalSeconds(offsetMinutes * 60))
            .format(ISO_WITH_OFFSET)

    private val ISO_WITH_OFFSET: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)

    private fun defaultAbsorptionMinutes(grams: Float, protein: Float?, fat: Float?): Int {
        val macroExtra = ((protein ?: 0f) * 1.5f) + ((fat ?: 0f) * 2.5f)
        return (60f + grams * 2f + macroExtra).toInt().coerceIn(30, 360)
    }

    private val isoFormatter = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }

    private fun formatIso8601(epochMillis: Long): String =
        isoFormatter.get()!!.format(Date(epochMillis))

    private fun String.sha256Short(): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(toByteArray(Charsets.UTF_8))
        return bytes.take(12).joinToString("") { "%02x".format(it) }
    }
}
