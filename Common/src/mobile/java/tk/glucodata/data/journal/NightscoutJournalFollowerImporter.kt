package tk.glucodata.data.journal

import androidx.annotation.Keep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import tk.glucodata.Applic
import tk.glucodata.Log
import tk.glucodata.NightscoutTreatmentImportBridge
import tk.glucodata.data.HistoryDatabase

@Keep
object NightscoutJournalFollowerImporter : NightscoutTreatmentImportBridge {
    private const val LOG_ID = "NightscoutJournalFollowerImporter"
    private const val SOURCE_RECORD_IDS_PER_STATEMENT = 500

    @Keep
    override fun importTreatments(sensorId: String, treatmentsJson: String): Int = runBlocking {
        withContext(Dispatchers.IO) {
            runCatching { importInternal(sensorId, treatmentsJson) }
                .onFailure { Log.e(LOG_ID, "importTreatments failed: ${Log.stackline(it)}") }
                .getOrDefault(0)
        }
    }

    private suspend fun importInternal(sensorId: String, treatmentsJson: String): Int {
        val trimmed = treatmentsJson.trim()
        if (trimmed.isEmpty()) return 0
        val array = JSONArray(trimmed)
        if (array.length() == 0) return 0

        val repository = JournalRepository()
        repository.ensureDefaultInsulinPresets()
        val presets = repository.getInsulinPresetsSnapshot()
        val journalDao = HistoryDatabase.getInstance(Applic.app).journalDao()
        val pendingDeleteRemoteIds = journalDao
            .getPendingNightscoutDeletes()
            .mapNotNull { it.nsRemoteId.trim().takeIf(String::isNotBlank) }
            .toSet()
        // Remote IDs this device itself uploaded to Nightscout. Re-importing them
        // would duplicate the local rows they came from, so these — and only these
        // — are skipped. Therapy uploaded by other JugglucoNG devices, or fetched by
        // a follow-only install, is still imported.
        val ownUploadedRemoteIds = journalDao
            .getOwnUploadedNightscoutRemoteIds()
            .mapNotNull { it.trim().takeIf(String::isNotBlank) }
            .toSet()
        // The same rows once more, as an API v3 read serves those sent over v1.
        val ownV1Rows = journalDao
            .getOwnUploadedNightscoutRows()
            .associate { JournalTreatmentUploader.v1Identifier(it.id) to it.timestamp }
        val sourcePrefix = "nightscout:${sensorId.trim().ifBlank { "unknown" }}"
        var imported = 0
        var deleted = 0
        val context = Applic.app
        val ownV1Copies = ArrayList<String>()

        for (index in 0 until array.length()) {
            val treatment = array.optJSONObject(index) ?: continue
            if (JournalTreatmentTransfer.hasAnyRemoteIdentifier(treatment, ownUploadedRemoteIds)) continue
            if (JournalTreatmentTransfer.isOwnV1Document(treatment, ownV1Rows)) {
                // Copies received before these documents were recognised are the install's own
                // rows a second time.
                ownV1Copies += JournalTreatmentTransfer.sourceRecordIdsForTreatment(treatment, sourcePrefix)
                continue
            }
            if (JournalTreatmentTransfer.hasAnyRemoteIdentifier(treatment, pendingDeleteRemoteIds)) continue
            val parsed = JournalTreatmentTransfer.parseTreatment(
                context = context,
                treatment = treatment,
                source = JournalEntrySource.NIGHTSCOUT,
                sourcePrefix = sourcePrefix,
                insulinPresets = presets
            ) ?: continue

            if (parsed.deleteOnly) {
                deleted += repository.deleteEntriesBySourceRecordIds(parsed.candidateSourceRecordIds)
                continue
            }

            for (input in parsed.inputs) {
                repository.upsertEntry(input)
                imported++
            }
            val importedIds = parsed.inputs.mapNotNull { it.sourceRecordId }.toSet()
            val staleIds = parsed.candidateSourceRecordIds.filterNot { it in importedIds }
            deleted += repository.deleteEntriesBySourceRecordIds(staleIds)
        }
        // In chunks: a read of 240 documents names five times as many rows, past the 999 bound
        // variables older SQLite takes in one statement.
        deleted += ownV1Copies.chunked(SOURCE_RECORD_IDS_PER_STATEMENT)
            .sumOf { repository.deleteEntriesBySourceRecordIds(it) }

        if (imported > 0 || deleted > 0) {
            Log.i(LOG_ID, "Nightscout journal sync imported=$imported deleted=$deleted")
        }
        return imported
    }
}
