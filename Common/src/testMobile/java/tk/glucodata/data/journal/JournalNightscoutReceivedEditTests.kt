package tk.glucodata.data.journal

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.data.journal.JournalTreatmentUploader.ReceivedDocumentRead
import tk.glucodata.data.journal.JournalTreatmentUploader.ReceivedEditAction
import tk.glucodata.data.journal.JournalTreatmentUploader.ReceivedEditHold
import tk.glucodata.data.journal.JournalTreatmentUploader.ReceivedEditPlan
import tk.glucodata.data.journal.JournalTreatmentUploader.answeredByNightscout
import tk.glucodata.data.journal.JournalTreatmentUploader.receivedDocumentRead
import tk.glucodata.data.journal.JournalTreatmentUploader.receivedDocumentUrl
import tk.glucodata.data.journal.JournalTreatmentUploader.receivedEditHold
import tk.glucodata.data.journal.JournalTreatmentUploader.receivedEditFailureAction
import tk.glucodata.data.journal.JournalTreatmentUploader.receivedEditPlan
import tk.glucodata.data.journal.JournalTreatmentUploader.receivedEditWriteAction
import tk.glucodata.data.journal.JournalTreatmentUploader.receivedEditWriteUrl

/**
 * A treatment received from Nightscout and edited in the journal: the edit goes back to the
 * document it came from, and the next receive does not undo it before it has.
 *
 * The field case: 5 U entered in another app reached Nightscout, was received, and edited to 6 U
 * here. Nothing was sent, and the next receive set it back to 5 U.
 */
class JournalNightscoutReceivedEditTests {
    private val base = "https://ns.example.com"
    private val objectId = "65a1b2c3d4e5f60718293a4b"
    private val uuid = "8f2c1e9a-5b7d-4c3e-9a1f-2d6e8b4c7a10"
    private val prefix = NightscoutJournalFollowerImporter.sourcePrefix("NS-0A1B2C3D4E5F")
    private val nightscout = JournalEntrySource.NIGHTSCOUT.storageValue

    private val doseTime = 1_786_794_604_000L
    private val serverWrote = doseTime + 1_000L
    private val receivedAt = doseTime + 60_000L
    private val editedAt = doseTime + 600_000L

    /** 5 U as API v3 serves another app's dose. */
    private fun v3Dose(units: Double = 5.0): JSONObject = JSONObject()
        .put("identifier", uuid)
        .put("date", doseTime)
        .put("utcOffset", 120)
        .put("eventType", "Correction Bolus")
        .put("insulin", units)
        .put("app", "xDrip+")
        .put("enteredBy", "xDrip+")
        .put("srvCreated", serverWrote)
        .put("srvModified", serverWrote)
        .put("isValid", true)

    /** The same dose as API v1 serves one written over v1: an _id, no identifier, no srvModified. */
    private fun v1Dose(units: Double = 5.0): JSONObject = JSONObject()
        .put("_id", objectId)
        .put("created_at", "2026-08-15T11:50:04.000Z")
        .put("utcOffset", 120)
        .put("eventType", "Correction Bolus")
        .put("insulin", units)
        .put("enteredBy", "xDrip+")
        .put("notes", "before lunch")

    /** The journal row [document] was received as, then edited at [editedAt] by [edit]. */
    private fun edited(
        document: JSONObject,
        type: JournalEntryType = JournalEntryType.INSULIN,
        edit: (JournalEntryEntity) -> JournalEntryEntity = { it },
    ): JournalEntryEntity {
        val input = JournalTreatmentTransfer.parseTreatment(
            treatment = document,
            source = JournalEntrySource.NIGHTSCOUT,
            sourcePrefix = prefix,
            insulinPresets = emptyList(),
            stringResource = { "Treatment" },
        )!!.inputs.single { it.type == type }
        val received = JournalEntryEntity(
            id = 0x1a7,
            timestamp = input.timestamp,
            sensorSerial = null,
            entryType = input.type.storageValue,
            title = input.title,
            note = input.note,
            amount = input.amount,
            glucoseValueMgDl = input.glucoseValueMgDl,
            durationMinutes = input.durationMinutes,
            intensity = input.intensity?.storageValue,
            insulinPresetId = null,
            proteinGrams = input.proteinGrams,
            fatGrams = input.fatGrams,
            source = nightscout,
            sourceRecordId = input.sourceRecordId,
            createdAt = receivedAt,
            updatedAt = receivedAt,
            nsUploadedAt = null,
            nsRemoteId = input.nsRemoteId,
            lvUploadedAt = JournalTreatmentTransfer.serverModifiedMillis(document),
        )
        val pendingMark = nightscoutUploadedAtAfterWrite(
            storedSource = nightscout,
            existingSource = received.source,
            existingUpdatedAt = received.updatedAt,
            existingNsUploadedAt = received.nsUploadedAt,
            incomingSource = JournalEntrySource.MANUAL,
            now = editedAt,
        )
        return edit(received).copy(updatedAt = editedAt, nsUploadedAt = pendingMark)
    }

    private fun sent(plan: ReceivedEditPlan): JournalTreatmentTransfer.ReceivedEditChanges =
        (plan as ReceivedEditPlan.Send).changes

    /** The document as v3 holds it once [fields] were PATCHed in. */
    private fun patched(document: JSONObject, fields: JSONObject): JSONObject {
        val result = JSONObject(document.toString())
        for (key in fields.keys()) result.put(key, fields.get(key))
        return result
    }

    private fun reparsed(document: JSONObject): JournalEntryInput =
        JournalTreatmentTransfer.parseTreatment(
            treatment = document,
            source = JournalEntrySource.NIGHTSCOUT,
            sourcePrefix = prefix,
            insulinPresets = emptyList(),
            stringResource = { "Treatment" },
        )!!.inputs.single()

    // -- the row's state --------------------------------------------------------

    @Test
    fun aReceivedRowIsInStepWithTheServer() {
        assertNull(
            nightscoutUploadedAtAfterWrite(nightscout, null, null, null, JournalEntrySource.NIGHTSCOUT, receivedAt)
        )
        assertFalse(hasPendingNightscoutEdit(nightscout, receivedAt, null))
    }

    @Test
    fun theUsersEditOfAReceivedRowIsPendingUntilTheServerConfirmsIt() {
        val mark = nightscoutUploadedAtAfterWrite(
            nightscout, nightscout, receivedAt, null, JournalEntrySource.MANUAL, editedAt
        )
        assertEquals(receivedAt, mark)
        assertTrue(hasPendingNightscoutEdit(nightscout, editedAt, mark))
        // Confirmed: the uploader sets nsUploadedAt at or after the edit.
        assertFalse(hasPendingNightscoutEdit(nightscout, editedAt, editedAt + 5_000L))
    }

    @Test
    fun anEditInTheMillisecondOfTheReceiveIsStillPending() {
        val mark = nightscoutUploadedAtAfterWrite(nightscout, nightscout, editedAt, null, JournalEntrySource.MANUAL, editedAt)
        assertTrue(hasPendingNightscoutEdit(nightscout, editedAt, mark))
    }

    @Test
    fun aSecondEditKeepsTheFirstOnesMark() {
        val later = editedAt + 60_000L
        assertEquals(
            receivedAt,
            nightscoutUploadedAtAfterWrite(nightscout, nightscout, editedAt, receivedAt, JournalEntrySource.MANUAL, later)
        )
    }

    @Test
    fun anEditAfterAConfirmedOneIsPendingAgain() {
        val confirmedAt = editedAt + 5_000L
        val later = editedAt + 60_000L
        val mark = nightscoutUploadedAtAfterWrite(nightscout, nightscout, editedAt, confirmedAt, JournalEntrySource.MANUAL, later)
        assertTrue(hasPendingNightscoutEdit(nightscout, later, mark))
    }

    @Test
    fun theServersCopyWrittenOverTheRowPutsItBackInStep() {
        // After a confirmed edit too: otherwise every receive would make the row pending again.
        for (copyFrom in listOf(JournalEntrySource.NIGHTSCOUT, JournalEntrySource.CLONE, JournalEntrySource.AAPS)) {
            assertNull(nightscoutUploadedAtAfterWrite(nightscout, nightscout, editedAt, receivedAt, copyFrom, editedAt + 1))
            assertNull(nightscoutUploadedAtAfterWrite(nightscout, nightscout, editedAt, editedAt + 5, copyFrom, editedAt + 9))
        }
    }

    @Test
    fun rowsOfEveryOtherSourceKeepTheirUploadState() {
        for (source in listOf(JournalEntrySource.MANUAL, JournalEntrySource.AAPS, JournalEntrySource.API, JournalEntrySource.CLONE)) {
            assertEquals(
                receivedAt,
                nightscoutUploadedAtAfterWrite(source.storageValue, source.storageValue, editedAt, receivedAt, JournalEntrySource.MANUAL, editedAt + 1)
            )
            assertFalse(hasPendingNightscoutEdit(source.storageValue, editedAt, receivedAt))
        }
    }

    @Test
    fun onlyAnEditFromThisPhoneCountsAsTheUsers() {
        assertTrue(isLocalEditOfReceivedTreatment(nightscout, JournalEntrySource.MANUAL))
        assertFalse(isLocalEditOfReceivedTreatment(nightscout, JournalEntrySource.NIGHTSCOUT))
        assertFalse(isLocalEditOfReceivedTreatment(nightscout, JournalEntrySource.CLONE_TURN))
        assertFalse(isLocalEditOfReceivedTreatment(JournalEntrySource.AAPS.storageValue, JournalEntrySource.MANUAL))
        assertFalse(isLocalEditOfReceivedTreatment(JournalEntrySource.MANUAL.storageValue, JournalEntrySource.MANUAL))
        assertFalse(isLocalEditOfReceivedTreatment(null, JournalEntrySource.MANUAL))
    }

    // -- the receive --------------------------------------------------------------

    @Test
    fun theReceiveDoesNotWriteOverAPendingEdit() {
        val mark = receivedAt
        // The field case: the server still says 5 U, the document unchanged since it was received.
        assertFalse(receivedCopyMayReplace(nightscout, editedAt, mark, receivedRevision = serverWrote, serverRevision = serverWrote))
        // A document that says nothing of its revision leaves the edit to be sent.
        assertFalse(receivedCopyMayReplace(nightscout, editedAt, mark, receivedRevision = serverWrote, serverRevision = null))
        assertFalse(receivedCopyMayReplace(nightscout, editedAt, mark, receivedRevision = null, serverRevision = serverWrote))
    }

    @Test
    fun aDocumentChangedSinceItWasReceivedStillWins() {
        assertTrue(receivedCopyMayReplace(nightscout, editedAt, receivedAt, receivedRevision = serverWrote, serverRevision = serverWrote + 1))
    }

    @Test
    fun onceConfirmedOrNeverEditedTheReceiveIsTheServersAgain() {
        assertTrue(receivedCopyMayReplace(nightscout, receivedAt, null, receivedRevision = null, serverRevision = null))
        assertTrue(receivedCopyMayReplace(nightscout, editedAt, editedAt + 5_000L, receivedRevision = serverWrote, serverRevision = serverWrote))
        // Other sources are not looked at here.
        assertTrue(receivedCopyMayReplace(JournalEntrySource.AAPS.storageValue, editedAt, receivedAt, null, null))
    }

    // -- a server clock that is not the phone's -----------------------------------------

    private val hour = 60 * 60_000L

    @Test
    fun aServerClockAheadOfThePhoneDoesNotUndoTheEdit() {
        // The server wrote the document before the receive, by its clock an hour ahead: later
        // than the edit by the phone's. Compared with the edit time, the server won every time.
        val aheadWrote = editedAt + hour
        val row = edited(v3Dose().put("srvModified", aheadWrote)) { it.copy(amount = 6f) }

        assertFalse(receivedCopyMayReplace(nightscout, row.updatedAt, row.nsUploadedAt, row.lvUploadedAt, aheadWrote))
        assertTrue(receivedEditPlan(row, v3Dose().put("srvModified", aheadWrote), useV3 = true) is ReceivedEditPlan.Send)
    }

    @Test
    fun aServerClockBehindThePhoneStillShowsAChangeMadeThere() {
        // Changed on the server after the edit, by a clock an hour behind: earlier than the edit by
        // the phone's, which kept the edit over the newer document.
        val behindWrote = receivedAt - hour
        val behindChanged = behindWrote + 5 * 60_000L
        val row = edited(v3Dose().put("srvModified", behindWrote)) { it.copy(amount = 6f) }
        val changed = v3Dose(units = 7.0).put("srvModified", behindChanged)

        assertTrue(behindChanged < row.updatedAt)
        assertTrue(receivedCopyMayReplace(nightscout, row.updatedAt, row.nsUploadedAt, row.lvUploadedAt, behindChanged))
        assertTrue(receivedEditPlan(row, changed, useV3 = true) is ReceivedEditPlan.ServerWins)
    }

    @Test
    fun theReceivedRevisionIsKeptForTheEditAndRenewedByEachReceive() {
        val manual = JournalEntrySource.MANUAL
        // A receive keeps the revision it brought, known or not.
        assertEquals(serverWrote, receivedRevisionAfterWrite(nightscout, nightscout, 1L, JournalEntrySource.NIGHTSCOUT, serverWrote))
        assertNull(receivedRevisionAfterWrite(nightscout, null, null, JournalEntrySource.NIGHTSCOUT, null))
        // The user's edit keeps the one it was made on.
        assertEquals(serverWrote, receivedRevisionAfterWrite(nightscout, nightscout, serverWrote, manual, null))
        // Another system's copy written over the row says nothing of it.
        assertNull(receivedRevisionAfterWrite(nightscout, nightscout, serverWrote, JournalEntrySource.CLONE, null))
        // Rows of every other source are written without one, as before.
        assertNull(receivedRevisionAfterWrite(manual.storageValue, manual.storageValue, null, manual, null))
        assertNull(receivedRevisionOf(manual.storageValue, serverWrote))
        assertEquals(serverWrote, receivedRevisionOf(nightscout, serverWrote))
    }

    @Test
    fun aRevisionIsOnlyComparedWithAnotherOfItsServer() {
        assertFalse(serverChangedSinceReceived(serverWrote, serverWrote))
        assertTrue(serverChangedSinceReceived(serverWrote, serverWrote + 1))
        // Earlier is a change too: a document restored on the server, say.
        assertTrue(serverChangedSinceReceived(serverWrote, serverWrote - 1))
        assertFalse(serverChangedSinceReceived(null, serverWrote))
        assertFalse(serverChangedSinceReceived(serverWrote, null))
    }

    @Test
    fun whenTheServerLastChangedADocument() {
        assertEquals(serverWrote, JournalTreatmentTransfer.serverModifiedMillis(v3Dose()))
        assertEquals(
            1_786_795_204_000L,
            JournalTreatmentTransfer.serverModifiedMillis(JSONObject().put("updated_at", "2026-08-15T12:00:04.000Z"))
        )
        assertNull(JournalTreatmentTransfer.serverModifiedMillis(v1Dose()))
    }

    // -- what can be sent at all ------------------------------------------------------

    @Test
    fun anEditReceivedFromTheUploadServerCanBeSent() {
        assertNull(receivedEditHold(edited(v3Dose()), prefix))
    }

    @Test
    fun anEditWithNowhereToGoIsHeldWithoutAsking() {
        assertEquals(ReceivedEditHold.NO_DOCUMENT, receivedEditHold(edited(v3Dose()).copy(nsRemoteId = null), prefix))
        // Received by a follower of another server: its ids mean nothing on this one.
        assertEquals(
            ReceivedEditHold.OTHER_SERVER,
            receivedEditHold(edited(v3Dose()), NightscoutJournalFollowerImporter.sourcePrefix("NS-FFFFFFFFFFFF"))
        )
        // A dose edited into carbs: the document cannot become that.
        assertEquals(
            ReceivedEditHold.TYPE_CHANGED,
            receivedEditHold(edited(v3Dose()) { it.copy(entryType = JournalEntryType.CARBS.storageValue) }, prefix)
        )
    }

    // -- where it goes ------------------------------------------------------------------

    @Test
    fun v3ReadsAndPatchesTheDocumentByEitherId() {
        assertEquals("$base/api/v3/treatments/$uuid", receivedDocumentUrl(base, uuid, useV3 = true))
        assertEquals("$base/api/v3/treatments/$objectId", receivedDocumentUrl(base, objectId, useV3 = true))
        assertEquals("$base/api/v3/treatments/$uuid", receivedEditWriteUrl(base, uuid, useV3 = true))
        assertEquals("$base/api/v3/treatments/a%20b%2Fc", receivedEditWriteUrl(base, "a b/c", useV3 = true))
    }

    @Test
    fun v1ReadsTheDocumentByItsIdAndPutsItBackWhole() {
        assertEquals(
            "$base/api/v1/treatments.json?find%5B_id%5D=$objectId&count=2",
            receivedDocumentUrl(base, objectId, useV3 = false)
        )
        // Without a date, v1 only looks at the last four days.
        assertEquals(
            "$base/api/v1/treatments.json?find%5Bidentifier%5D=$uuid&find%5Bcreated_at%5D%5B%24gte%5D=2000-01-01&count=2",
            receivedDocumentUrl(base, uuid, useV3 = false)
        )
        assertEquals("$base/api/v1/treatments/", receivedEditWriteUrl(base, objectId, useV3 = false))
    }

    // -- what the read answered -----------------------------------------------------------

    @Test
    fun aReadFindsTheOneDocument() {
        val v3 = JSONObject().put("status", 200).put("result", v3Dose())
        assertEquals(uuid, (receivedDocumentRead(200, v3.toString()) as ReceivedDocumentRead.Found).document.getString("identifier"))
        val v1 = JSONArray().put(v1Dose())
        assertEquals(objectId, (receivedDocumentRead(200, v1.toString()) as ReceivedDocumentRead.Found).document.getString("_id"))
    }

    @Test
    fun aDocumentNoLongerThereIsGone() {
        assertEquals(ReceivedDocumentRead.Gone, receivedDocumentRead(200, "[]"))
        assertEquals(ReceivedDocumentRead.Gone, receivedDocumentRead(404, """{"status":404}"""))
        assertEquals(ReceivedDocumentRead.Gone, receivedDocumentRead(410, """{"status":410}"""))
    }

    @Test
    fun anIdentifierSeveralDocumentsCarryNamesNone() {
        val two = JSONArray().put(v1Dose()).put(v1Dose().put("_id", "65a1b2c3d4e5f60718293a4c"))
        assertEquals(ReceivedDocumentRead.Ambiguous, receivedDocumentRead(200, two.toString()))
    }

    @Test
    fun aReadNotAnsweredOrRefusedFails() {
        assertTrue(receivedDocumentRead(-1, "") is ReceivedDocumentRead.Failed)
        assertTrue(receivedDocumentRead(403, """{"status":403,"message":"Missing permission api:treatments:read"}""") is ReceivedDocumentRead.Failed)
        // A captive portal answering 200 or 404 is not Nightscout saying anything.
        assertTrue(receivedDocumentRead(200, "<html>Sign in to the Wi-Fi</html>") is ReceivedDocumentRead.Failed)
        assertTrue(receivedDocumentRead(404, "<!DOCTYPE html><title>Router</title>") is ReceivedDocumentRead.Failed)
    }

    // -- what is sent -------------------------------------------------------------------

    @Test
    fun theFieldCaseOverV3PatchesTheAmountAndNothingElse() {
        val row = edited(v3Dose()) { it.copy(amount = 6f) }

        val changes = sent(receivedEditPlan(row, v3Dose(), useV3 = true))

        assertEquals(listOf("insulin"), changes.fields.keys().asSequence().toList())
        assertEquals(6.0, changes.fields.getDouble("insulin"), 0.0)
        // And what the server then serves is read back as the edit, so the receive keeps it.
        assertEquals(6f, reparsed(patched(v3Dose(), changes.fields)).amount!!, 0f)
    }

    @Test
    fun theFieldCaseOverV1PutsBackTheOtherAppsDocumentWithOnlyTheAmountChanged() {
        val row = edited(v1Dose()) { it.copy(amount = 6f) }

        val changes = sent(receivedEditPlan(row, v1Dose(), useV3 = false))
        val put = JournalTreatmentTransfer.receivedEditV1Document(v1Dose(), changes, editedAt + 1_000L)

        assertEquals(objectId, put.getString("_id"))
        assertEquals(6.0, put.getDouble("insulin"), 0.0)
        assertEquals("xDrip+", put.getString("enteredBy"))
        assertEquals("Correction Bolus", put.getString("eventType"))
        assertEquals("before lunch", put.getString("notes"))
        assertFalse(put.has("app"))
        assertFalse(put.has("identifier"))
        // v1 sets utcOffset from created_at's zone: written in the document's offset, it is kept.
        assertEquals("2026-08-15T13:50:04.000+02:00", put.getString("created_at"))
        assertEquals(row.timestamp, reparsed(put).timestamp)
        assertEquals(6f, reparsed(put).amount!!, 0f)
        assertEquals(row.note, reparsed(put).note)
    }

    @Test
    fun aV1PutMovesSrvModifiedOnlyWhereTheDocumentHasOne() {
        val row = edited(v1Dose()) { it.copy(amount = 6f) }
        val changes = sent(receivedEditPlan(row, v1Dose(), useV3 = false))
        val now = editedAt + 1_000L

        assertFalse(JournalTreatmentTransfer.receivedEditV1Document(v1Dose(), changes, now).has("srvModified"))
        val v3Written = v1Dose().put("srvModified", serverWrote)
        assertEquals(now, JournalTreatmentTransfer.receivedEditV1Document(v3Written, changes, now).getLong("srvModified"))
    }

    @Test
    fun anEditedNoteGoesWithoutTheNameOfWhoEnteredIt() {
        // Received, the note reads "before lunch | xDrip+": the receive adds enteredBy.
        val row = edited(v1Dose()) { it.copy(note = "after lunch | xDrip+") }

        val changes = sent(receivedEditPlan(row, v1Dose(), useV3 = false))

        assertEquals(listOf("notes"), changes.fields.keys().asSequence().toList())
        assertEquals("after lunch", changes.fields.getString("notes"))
        assertEquals("after lunch | xDrip+", reparsed(patched(v1Dose(), changes.fields)).note)
        assertEquals("after lunch", JournalTreatmentTransfer.withoutSourceLabel("after lunch", "xDrip+"))
        assertNull(JournalTreatmentTransfer.withoutSourceLabel("xDrip+", "xDrip+"))
    }

    @Test
    fun aSaveThatChangedNothingIsSettledWithoutAWrite() {
        val plan = receivedEditPlan(edited(v3Dose()), v3Dose(), useV3 = true)
        assertTrue(plan is ReceivedEditPlan.Settled && !plan.timeKeptByServer)
    }

    @Test
    fun anEditedTimeIsSentOverV1() {
        val moved = doseTime + 10 * 60_000L
        val row = edited(v1Dose()) { it.copy(timestamp = moved) }

        val changes = sent(receivedEditPlan(row, v1Dose(), useV3 = false))
        val put = JournalTreatmentTransfer.receivedEditV1Document(v1Dose(), changes, editedAt)

        assertEquals(moved, changes.timestampMillis)
        assertEquals("2026-08-15T14:00:04.000+02:00", put.getString("created_at"))
        assertEquals(moved, reparsed(put).timestamp)
        // A document that also carries the time as a number has it moved there as well.
        val withDate = JournalTreatmentTransfer.receivedEditV1Document(v1Dose().put("date", doseTime), changes, editedAt)
        assertEquals(moved, withDate.getLong("date"))
    }

    @Test
    fun v3KeepsTheTimeOfADocumentButTakesTheRest() {
        // v3 answers 400 to a date change; the rest of the edit still goes.
        val moved = doseTime + 10 * 60_000L
        val onlyTime = receivedEditPlan(edited(v3Dose()) { it.copy(timestamp = moved) }, v3Dose(), useV3 = true)
        assertTrue(onlyTime is ReceivedEditPlan.Settled && onlyTime.timeKeptByServer)

        val both = receivedEditPlan(edited(v3Dose()) { it.copy(timestamp = moved, amount = 6f) }, v3Dose(), useV3 = true)
        both as ReceivedEditPlan.Send
        assertTrue(both.timeKeptByServer)
        assertNull(both.changes.timestampMillis)
        assertEquals(listOf("insulin"), both.changes.fields.keys().asSequence().toList())
    }

    @Test
    fun theServerWinsWhereItChangedTheDocumentSinceItWasReceived() {
        val row = edited(v3Dose()) { it.copy(amount = 6f) }
        val changedSince = v3Dose(units = 7.0).put("srvModified", editedAt + 1)

        assertTrue(receivedEditPlan(row, changedSince, useV3 = true) is ReceivedEditPlan.ServerWins)
    }

    @Test
    fun theServerWinsWhereTheDocumentNoLongerHoldsTheRowOrMayNotBeChanged() {
        val row = edited(v3Dose()) { it.copy(amount = 6f) }
        val noDose = v3Dose().apply { remove("insulin") }.put("carbs", 20)
        val deleted = v3Dose().put("isValid", false)
        val readOnly = v3Dose().put("isReadOnly", true)

        for (document in listOf(noDose, deleted, readOnly)) {
            assertTrue(receivedEditPlan(row, document, useV3 = true) is ReceivedEditPlan.ServerWins)
        }
    }

    @Test
    fun v1CannotChangeADocumentWhoseIdIsNotAnObjectId() {
        // Its PUT makes the _id an ObjectId and fails (500) on anything else, every time.
        val stringId = v1Dose().put("_id", "xdrip-treatment-17")
        val row = edited(stringId) { it.copy(amount = 6f) }

        assertTrue(receivedEditPlan(row, stringId, useV3 = false) is ReceivedEditPlan.ServerWins)
    }

    @Test
    fun eachValueGoesUnderTheNameTheDocumentHoldsItBy() {
        val meterReading = JSONObject()
            .put("_id", objectId).put("eventType", "BG Check").put("created_at", "2026-08-15T11:50:04.000Z")
            .put("mbg", 95)
        val reading = sent(
            receivedEditPlan(
                edited(meterReading, JournalEntryType.FINGERSTICK) { it.copy(glucoseValueMgDl = 110f) },
                meterReading,
                useV3 = false
            )
        )
        assertEquals(110.0, reading.fields.getDouble("mbg"), 0.0)
        assertFalse(reading.fields.has("glucose"))

        val inMmol = JSONObject()
            .put("_id", objectId).put("eventType", "BG Check").put("created_at", "2026-08-15T11:50:04.000Z")
            .put("glucose", 5.5).put("units", "mmol")
        val mmol = sent(
            receivedEditPlan(
                edited(inMmol, JournalEntryType.FINGERSTICK) { it.copy(glucoseValueMgDl = 6.0f * 18.0182f) },
                inMmol,
                useV3 = false
            )
        )
        assertEquals(6.0, mmol.fields.getDouble("glucose"), 0.0)
    }

    @Test
    fun anAbsorptionTimeIsNotWrittenIntoAapsExtendedCarbsDuration() {
        // In an AAPS document "duration" is how long extended carbs last; 0 for ordinary ones.
        val meal = JSONObject()
            .put("identifier", uuid).put("date", doseTime).put("eventType", "Carb Correction")
            .put("carbs", 20).put("duration", 0).put("app", "AAPS")
        val row = edited(meal, JournalEntryType.CARBS) { it.copy(durationMinutes = 90) }
        val changes = JournalTreatmentTransfer.receivedEditChanges(row, meal)!!
        assertEquals(90L, changes.fields.getLong("absorptionTime"))
        assertFalse(changes.fields.has("duration"))
        // A loop's document is not written to at all.
        assertTrue(receivedEditPlan(row, meal, useV3 = true) is ReceivedEditPlan.KeepLocal)
    }

    @Test
    fun aDurationKeptInMillisecondsIsWrittenInMilliseconds() {
        val meal = JSONObject()
            .put("identifier", uuid).put("date", doseTime).put("eventType", "Meal Bolus")
            .put("carbs", 40).put("durationInMilliseconds", 120 * 60_000L)
        val changes = sent(
            receivedEditPlan(edited(meal, JournalEntryType.CARBS) { it.copy(durationMinutes = 90) }, meal, useV3 = true)
        )
        assertEquals(90 * 60_000L, changes.fields.getLong("durationInMilliseconds"))
    }

    @Test
    fun aValueTheRowNoLongerHasIsNotClearedOnTheServer() {
        // The editor drops macros when they are switched off, without the user asking.
        val meal = JSONObject()
            .put("identifier", uuid).put("date", doseTime).put("eventType", "Meal Bolus")
            .put("carbs", 40).put("protein", 20).put("fat", 10)
        val plan = receivedEditPlan(
            edited(meal, JournalEntryType.CARBS) { it.copy(proteinGrams = null, fatGrams = null) },
            meal,
            useV3 = true
        )
        assertTrue(plan is ReceivedEditPlan.Settled)
    }

    @Test
    fun onlyThePartOfAMealBolusThatWasEditedChanges() {
        val mealBolus = JSONObject()
            .put("identifier", uuid).put("date", doseTime).put("eventType", "Meal Bolus")
            .put("carbs", 40).put("insulin", 4.0)
        val changes = sent(
            receivedEditPlan(edited(mealBolus, JournalEntryType.INSULIN) { it.copy(amount = 4.5f) }, mealBolus, useV3 = true)
        )
        assertEquals(listOf("insulin"), changes.fields.keys().asSequence().toList())
        assertEquals(4.5, changes.fields.getDouble("insulin"), 0.0)
    }

    // -- what the write answered ------------------------------------------------------------

    @Test
    fun aWriteTheServerTookSettlesTheEdit() {
        assertEquals(ReceivedEditAction.CONFIRM, receivedEditWriteAction(200, answeredByNightscout = true))
        assertEquals(ReceivedEditAction.CONFIRM, receivedEditWriteAction(204, answeredByNightscout = true))
    }

    @Test
    fun aDocumentGoneMeanwhileHasNothingLeftToChange() {
        assertEquals(ReceivedEditAction.CONFIRM, receivedEditWriteAction(404, answeredByNightscout = true))
        assertEquals(ReceivedEditAction.CONFIRM, receivedEditWriteAction(410, answeredByNightscout = true))
    }

    @Test
    fun anUnansweredWriteKeepsTheEditPendingAndThePassWaits() {
        // Offline, busy or not Nightscout at all: the edit is neither lost nor reverted, and goes
        // again once the server is back, like any other upload.
        for (code in listOf(401, 408, 429, 502, 503, 504)) {
            assertEquals(ReceivedEditAction.WAIT, receivedEditWriteAction(code, answeredByNightscout = true))
        }
        assertEquals(ReceivedEditAction.WAIT, receivedEditWriteAction(-1, answeredByNightscout = false))
        assertEquals(ReceivedEditAction.WAIT, receivedEditWriteAction(200, answeredByNightscout = false))
        assertEquals(ReceivedEditAction.WAIT, receivedEditWriteAction(404, answeredByNightscout = false))
        assertEquals(ReceivedEditAction.WAIT, receivedEditWriteAction(403, answeredByNightscout = false))
    }

    @Test
    fun aServerErrorOnTheEditIsRetriedOnItsOwn() {
        assertEquals(ReceivedEditAction.RETRY, receivedEditWriteAction(500, answeredByNightscout = true))
        // A 500 that is not Nightscout's (a proxy's page, no body) says nothing about this edit:
        // the pass waits, and none of the edit's attempts is used up.
        assertEquals(ReceivedEditAction.WAIT, receivedEditWriteAction(500, answeredByNightscout = false))
    }

    @Test
    fun onlyAnAnswerInNightscoutsJsonCountsAgainstTheEdit() {
        fun written(code: Int, body: String) = receivedEditWriteAction(code, answeredByNightscout(code, body))
        fun read(code: Int, body: String) = receivedEditFailureAction(code, answeredByNightscout(code, body))
        val html500 = "<html><body><h1>500 Internal Server Error</h1></body></html>"
        val html403 = "<html><head><title>403 Forbidden</title></head><body>nginx</body></html>"
        val json500 = """{"status":500,"message":"MongoServerError: write failed"}"""
        val json403 = """{"status":403,"message":"Missing permission api:treatments:update"}"""

        for (answer in listOf(::written, ::read)) {
            assertEquals(ReceivedEditAction.WAIT, answer(500, html500))
            assertEquals(ReceivedEditAction.WAIT, answer(500, ""))
            assertEquals(ReceivedEditAction.WAIT, answer(403, html403))
            assertEquals(ReceivedEditAction.RETRY, answer(500, json500))
            assertEquals(ReceivedEditAction.KEEP_LOCAL, answer(403, json403))
        }
    }

    @Test
    fun aRefusedWriteKeepsTheEditHereWithoutAskingAgain() {
        // A token that may create treatments but not change them answers 403 to every attempt;
        // retried, it held every later upload back.
        for (code in listOf(400, 403, 422)) {
            assertEquals(ReceivedEditAction.KEEP_LOCAL, receivedEditWriteAction(code, answeredByNightscout = true))
        }
    }

    @Test
    fun aRefusedReadOfTheDocumentKeepsTheEditHereToo() {
        assertEquals(ReceivedEditAction.KEEP_LOCAL, receivedEditFailureAction(403, answeredByNightscout = true))
        assertEquals(ReceivedEditAction.WAIT, receivedEditFailureAction(403, answeredByNightscout = false))
        assertEquals(ReceivedEditAction.RETRY, receivedEditFailureAction(500, answeredByNightscout = true))
        assertEquals(ReceivedEditAction.WAIT, receivedEditFailureAction(-1, answeredByNightscout = false))
    }

    @Test
    fun anEditKeptHereIsNotMarkedSentAndAFurtherEditIsSentAgain() {
        // Kept, it is still the user's unconfirmed edit: never settled as if the server had it.
        assertTrue(hasPendingNightscoutEdit(nightscout, editedAt, NIGHTSCOUT_EDIT_KEPT_LOCAL))
        val later = editedAt + 60_000L
        val mark = nightscoutUploadedAtAfterWrite(
            nightscout, nightscout, editedAt, NIGHTSCOUT_EDIT_KEPT_LOCAL, JournalEntrySource.MANUAL, later
        )!!
        assertFalse(isNightscoutEditKeptLocal(nightscout, mark))
        assertTrue(hasPendingNightscoutEdit(nightscout, later, mark))
    }

    // -- other sources are unchanged ----------------------------------------------------------

    @Test
    fun rowsFromAapsTheApiAndCloneAreStillNeverSent() {
        for (source in listOf(
            JournalEntrySource.AAPS, JournalEntrySource.API, JournalEntrySource.CLONE,
            JournalEntrySource.CLONE_LOCAL_ICE, JournalEntrySource.CLONE_TURN, JournalEntrySource.NIGHTSCOUT
        )) {
            assertTrue(JournalTreatmentUploader.isExternalMirrorSource(source.storageValue))
            // An edit of one of these is no received edit: only a NIGHTSCOUT row ever is.
            if (source != JournalEntrySource.NIGHTSCOUT) {
                assertFalse(hasPendingNightscoutEdit(source.storageValue, editedAt, receivedAt))
                assertFalse(isLocalEditOfReceivedTreatment(source.storageValue, JournalEntrySource.MANUAL))
            }
        }
    }
}
