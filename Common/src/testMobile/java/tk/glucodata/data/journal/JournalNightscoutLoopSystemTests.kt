package tk.glucodata.data.journal

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.data.journal.JournalTreatmentUploader.DeleteCheck
import tk.glucodata.data.journal.JournalTreatmentUploader.MAX_DELETE_ATTEMPTS
import tk.glucodata.data.journal.JournalTreatmentUploader.ReceivedDocumentRead
import tk.glucodata.data.journal.JournalTreatmentUploader.ReceivedEditPlan
import tk.glucodata.data.journal.JournalTreatmentUploader.TombstoneAction
import tk.glucodata.data.journal.JournalTreatmentUploader.deleteCheck
import tk.glucodata.data.journal.JournalTreatmentUploader.receivedEditPlan
import tk.glucodata.data.journal.JournalTreatmentUploader.sendsDelete
import tk.glucodata.data.journal.JournalTreatmentUploader.tombstoneReadAction

/**
 * A treatment written by a closed loop or a pump is never changed or deleted on Nightscout from
 * here: its edit stays on the row, and its delete only keeps it from being received again.
 */
class JournalNightscoutLoopSystemTests {
    private val uuid = "8f2c1e9a-5b7d-4c3e-9a1f-2d6e8b4c7a10"
    private val objectId = "65a1b2c3d4e5f60718293a4b"
    private val prefix = NightscoutJournalFollowerImporter.sourcePrefix("NS-0A1B2C3D4E5F")
    private val nightscout = JournalEntrySource.NIGHTSCOUT.storageValue

    private val doseTime = 1_786_794_604_000L
    private val receivedAt = doseTime + 60_000L
    private val editedAt = doseTime + 600_000L

    /** A bolus as a loop's API v3 upload carries it. */
    private fun loopBolus(): JSONObject = JSONObject()
        .put("identifier", uuid)
        .put("date", doseTime)
        .put("eventType", "Correction Bolus")
        .put("insulin", 1.2)
        .put("type", "SMB")
        .put("isSMB", true)
        .put("pumpId", 4_711L)
        .put("pumpType", "OMNIPOD_DASH")
        .put("pumpSerial", "P1234")
        .put("app", "AAPS")
        .put("srvModified", doseTime + 1_000L)
        .put("isValid", true)

    private fun dose(enteredBy: String): JSONObject = JSONObject()
        .put("_id", objectId)
        .put("created_at", "2026-08-15T11:50:04.000Z")
        .put("eventType", "Correction Bolus")
        .put("insulin", 5.0)
        .put("enteredBy", enteredBy)

    private fun edited(document: JSONObject, edit: (JournalEntryEntity) -> JournalEntryEntity): JournalEntryEntity {
        val input = JournalTreatmentTransfer.parseTreatment(
            treatment = document,
            source = JournalEntrySource.NIGHTSCOUT,
            sourcePrefix = prefix,
            insulinPresets = emptyList(),
            stringResource = { "Treatment" },
        )!!.inputs.single()
        val received = JournalEntryEntity(
            id = 0x1a7,
            timestamp = input.timestamp,
            sensorSerial = null,
            entryType = input.type.storageValue,
            title = input.title,
            note = input.note,
            amount = input.amount,
            glucoseValueMgDl = null,
            durationMinutes = null,
            intensity = null,
            insulinPresetId = null,
            source = nightscout,
            sourceRecordId = input.sourceRecordId,
            createdAt = receivedAt,
            updatedAt = receivedAt,
            nsRemoteId = input.nsRemoteId,
        )
        val mark = nightscoutUploadedAtAfterWrite(
            nightscout, nightscout, received.updatedAt, received.nsUploadedAt, JournalEntrySource.MANUAL, editedAt
        )
        return edit(received).copy(updatedAt = editedAt, nsUploadedAt = mark)
    }

    // -- which documents are a loop's -------------------------------------------------

    @Test
    fun aPumpFieldMarksALoopSystemsDocument() {
        assertTrue(JournalTreatmentTransfer.isLoopSystemDocument(loopBolus()))
        for (field in listOf("pumpId", "pumpSerial", "pumpType", "isSMB")) {
            val only = dose("Careportal").put(field, if (field == "isSMB") false else "x")
            assertTrue(field, JournalTreatmentTransfer.isLoopSystemDocument(only))
        }
        // A field that is there but empty says nothing.
        assertFalse(JournalTreatmentTransfer.isLoopSystemDocument(dose("Careportal").put("pumpId", JSONObject.NULL)))
    }

    @Test
    fun aLoopNamedAsTheWriterMarksItsDocument() {
        for (writer in listOf(
            "AAPS", "AndroidAPS", "openaps://AndroidAPS", "Loop", "loop://iPhone", "Trio", "iAPS",
            "openaps://edison", "freeaps-x", "xDrip-pump"
        )) {
            assertTrue(writer, JournalTreatmentTransfer.isLoopSystemDocument(dose(writer)))
        }
        assertTrue(JournalTreatmentTransfer.isLoopSystemDocument(dose("Careportal").put("device", "loop://iPhone")))
        assertTrue(JournalTreatmentTransfer.isLoopSystemDocument(dose("Careportal").put("app", "AAPS")))
    }

    @Test
    fun otherWritersAreNotLoops() {
        for (writer in listOf("xDrip+", "JugglucoNG", "Careportal", "LoopFollow", "Nightscout", "Juggluco")) {
            assertFalse(writer, JournalTreatmentTransfer.isLoopSystemDocument(dose(writer)))
        }
        assertFalse(JournalTreatmentTransfer.isLoopSystemDocument(JSONObject().put("eventType", "Note")))
    }

    // -- an edit -------------------------------------------------------------------------

    @Test
    fun anEditOfALoopsDoseStaysHere() {
        val row = edited(loopBolus()) { it.copy(amount = 2f) }

        assertTrue(receivedEditPlan(row, loopBolus(), useV3 = true) is ReceivedEditPlan.KeepLocal)
        assertTrue(receivedEditPlan(row, loopBolus(), useV3 = false) is ReceivedEditPlan.KeepLocal)
        // Whatever else the document says.
        val readOnly = loopBolus().put("isReadOnly", true)
        assertTrue(receivedEditPlan(row, readOnly, useV3 = true) is ReceivedEditPlan.KeepLocal)
        val changedSince = loopBolus().put("srvModified", editedAt + 1)
        assertTrue(receivedEditPlan(row, changedSince, useV3 = true) is ReceivedEditPlan.KeepLocal)
    }

    @Test
    fun anEditOfAnotherAppsDoseIsStillSent() {
        val row = edited(dose("xDrip+")) { it.copy(amount = 6f) }

        assertTrue(receivedEditPlan(row, dose("xDrip+"), useV3 = false) is ReceivedEditPlan.Send)
    }

    @Test
    fun anEditKeptHereIsUnconfirmedSoTheReceiveLeavesItAlone() {
        assertTrue(isNightscoutEditKeptLocal(nightscout, NIGHTSCOUT_EDIT_KEPT_LOCAL))
        assertTrue(hasPendingNightscoutEdit(nightscout, editedAt, NIGHTSCOUT_EDIT_KEPT_LOCAL))
        assertFalse(isNightscoutEditKeptLocal(nightscout, null))
        assertFalse(isNightscoutEditKeptLocal(nightscout, receivedAt))
        assertFalse(isNightscoutEditKeptLocal(JournalEntrySource.MANUAL.storageValue, NIGHTSCOUT_EDIT_KEPT_LOCAL))
    }

    @Test
    fun aFurtherEditOfARowKeptHereIsPendingAgain() {
        val later = editedAt + 60_000L
        val mark = nightscoutUploadedAtAfterWrite(
            nightscout, nightscout, editedAt, NIGHTSCOUT_EDIT_KEPT_LOCAL, JournalEntrySource.MANUAL, later
        )!!

        assertEquals(editedAt, mark)
        assertFalse(isNightscoutEditKeptLocal(nightscout, mark))
        assertTrue(hasPendingNightscoutEdit(nightscout, later, mark))
    }

    // -- a delete ---------------------------------------------------------------------------

    @Test
    fun aLoopsDocumentIsNeverDeletedOnTheServer() {
        assertEquals(DeleteCheck.KEEP_LOCAL, deleteCheck(ReceivedDocumentRead.Found(loopBolus())))
        assertEquals(DeleteCheck.DELETE, deleteCheck(ReceivedDocumentRead.Found(dose("xDrip+"))))
    }

    @Test
    fun aDocumentGoneOrNamedBySeveralIsNotDeletedEither() {
        assertEquals(DeleteCheck.GONE, deleteCheck(ReceivedDocumentRead.Gone))
        assertEquals(DeleteCheck.KEEP_LOCAL, deleteCheck(ReceivedDocumentRead.Ambiguous))
        assertNull(deleteCheck(ReceivedDocumentRead.Failed(503)))
    }

    @Test
    fun aTombstoneKeptHereIsNotSentButStillSuppressesTheDocument() {
        val kept = JournalPendingDeleteEntity(entryId = 0x1a7, nsRemoteId = uuid, deletedAt = editedAt, attempts = MAX_DELETE_ATTEMPTS)

        assertFalse(sendsDelete(kept))
        assertTrue(sendsDelete(kept.copy(attempts = 0)))
        assertTrue(JournalTreatmentTransfer.hasAnyRemoteIdentifier(loopBolus(), setOf(kept.nsRemoteId)))
    }

    @Test
    fun aFailedReadBeforeADeleteNeverClearsTheTombstone() {
        assertEquals(TombstoneAction.WAIT, tombstoneReadAction(-1, 0, answeredByNightscout = false))
        assertEquals(TombstoneAction.WAIT, tombstoneReadAction(200, 0, answeredByNightscout = true))
        assertEquals(TombstoneAction.WAIT, tombstoneReadAction(404, 0, answeredByNightscout = false))
        assertEquals(TombstoneAction.RETRY, tombstoneReadAction(403, 0, answeredByNightscout = true))
    }

    @Test
    fun onlyThisAppsOwnIdentifiersSkipTheRead() {
        assertTrue(JournalTreatmentUploader.isOwnIdentifier(JournalTreatmentUploader.v1Identifier(0x1a7)))
        assertTrue(JournalTreatmentUploader.isOwnIdentifier(JournalTreatmentUploader.datedIdentifier(0x1a7, doseTime)))
        assertFalse(JournalTreatmentUploader.isOwnIdentifier(uuid))
        assertFalse(JournalTreatmentUploader.isOwnIdentifier(objectId))
    }
}
