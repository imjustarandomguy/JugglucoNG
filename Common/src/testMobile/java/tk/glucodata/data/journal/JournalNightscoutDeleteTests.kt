package tk.glucodata.data.journal

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.data.journal.JournalTreatmentUploader.IdentifierDocument
import tk.glucodata.data.journal.JournalTreatmentUploader.MAX_DELETE_ATTEMPTS
import tk.glucodata.data.journal.JournalTreatmentUploader.ReceivedDocumentRead
import tk.glucodata.data.journal.JournalTreatmentUploader.Settlement
import tk.glucodata.data.journal.JournalTreatmentUploader.TombstoneAction
import tk.glucodata.data.journal.JournalTreatmentUploader.answeredByNightscout
import tk.glucodata.data.journal.JournalTreatmentUploader.documentForIdentifier
import tk.glucodata.data.journal.JournalTreatmentUploader.documentsRead
import tk.glucodata.data.journal.JournalTreatmentUploader.identifierLookupUrl
import tk.glucodata.data.journal.JournalTreatmentUploader.isLookupDue
import tk.glucodata.data.journal.JournalTreatmentUploader.lookupLetsGo
import tk.glucodata.data.journal.JournalTreatmentUploader.receivedDocumentRead
import tk.glucodata.data.journal.JournalTreatmentUploader.servedRemoteIds
import tk.glucodata.data.journal.JournalTreatmentUploader.settlement
import tk.glucodata.data.journal.JournalTreatmentUploader.tombstoneAction
import tk.glucodata.data.journal.JournalTreatmentUploader.tombstoneDeleteUrl

/** A treatment deleted in the journal is deleted on Nightscout, and is not received back. */
class JournalNightscoutDeleteTests {
    private val base = "https://ns.example.com"
    private val objectId = "65a1b2c3d4e5f60718293a4b"
    private val uuid = "8f2c1e9a-5b7d-4c3e-9a1f-2d6e8b4c7a10"

    private fun document(id: String, identifier: String): JSONObject =
        JSONObject().put("_id", id).put("identifier", identifier).put("eventType", "Note")

    // -- which request deletes the document ---------------------------------

    @Test
    fun aV1DocumentIdGoesToTheDocumentRoute() {
        assertEquals(
            "$base/api/v1/treatments/$objectId",
            tombstoneDeleteUrl(base, objectId, useV3 = false)
        )
    }

    @Test
    fun aClientIdentifierIsDeletedByQueryOnV1() {
        // AAPS and other v3 uploaders name treatments by a UUID. v1's document route reads its
        // argument as an ObjectId, so it deleted nothing and the next read brought it back.
        // Without the date, a v1 query only reaches the last four days.
        assertEquals(
            "$base/api/v1/treatments?find%5Bidentifier%5D=$uuid&find%5Bcreated_at%5D%5B%24gte%5D=2000-01-01",
            tombstoneDeleteUrl(base, uuid, useV3 = false)
        )
    }

    @Test
    fun anOwnDatedIdentifierIsDeletedByQueryOnV1() {
        val dated = JournalTreatmentUploader.datedIdentifier(423L, 1_700_000_000_000L)

        assertTrue(tombstoneDeleteUrl(base, dated, useV3 = false).contains("find%5Bidentifier%5D=$dated&"))
    }

    @Test
    fun theUndatedOwnIdentifierIsNeverAQuery() {
        // It is the bare row id, which other installs reuse: a query on it could delete one of
        // theirs. It is resolved to its _id before a delete is sent.
        assertTrue(JournalTreatmentUploader.isUndatedOwnIdentifier("jng-j-1a7"))
        assertFalse(JournalTreatmentUploader.isUndatedOwnIdentifier("jng-j-1a7-18bcfe56800"))
        assertFalse(JournalTreatmentUploader.isUndatedOwnIdentifier(uuid))
        assertEquals("$base/api/v1/treatments/jng-j-1a7", tombstoneDeleteUrl(base, "jng-j-1a7", useV3 = false))
    }

    @Test
    fun v3NamesTheDocumentByEitherId() {
        assertEquals(
            "$base/api/v3/treatments/$uuid?permanent=true",
            tombstoneDeleteUrl(base, uuid, useV3 = true)
        )
        assertEquals(
            "$base/api/v3/treatments/$objectId?permanent=true",
            tombstoneDeleteUrl(base, objectId, useV3 = true)
        )
    }

    @Test
    fun onlyAMongoIdIsAnObjectId() {
        assertTrue(JournalTreatmentUploader.isObjectId(objectId))
        assertFalse(JournalTreatmentUploader.isObjectId(uuid))
        assertFalse(JournalTreatmentUploader.isObjectId("jng-j-1a7"))
        assertFalse(JournalTreatmentUploader.isObjectId(objectId.dropLast(1) + "z"))
    }

    // -- answers that are not Nightscout's ------------------------------------

    @Test
    fun onlyNightscoutsOwnAnswerCountsAsOne() {
        assertTrue(answeredByNightscout(200, """{"n":1,"ok":1}"""))
        assertTrue(answeredByNightscout(200, """{"status":200}"""))
        assertTrue(answeredByNightscout(204, ""))
        assertTrue(answeredByNightscout(403, """{"status":403,"message":"Missing permission api:treatments:delete"}"""))
        // A captive portal, or another device at the same LAN address away from home.
        assertFalse(answeredByNightscout(200, "<html><body>Sign in to the Wi-Fi</body></html>"))
        assertFalse(answeredByNightscout(404, "<!DOCTYPE html><title>Router</title>"))
        assertFalse(answeredByNightscout(-1, ""))
    }

    @Test
    fun anAnswerThatIsNotNightscoutsNeitherClearsNorCounts() {
        assertEquals(TombstoneAction.WAIT, tombstoneAction(200, 0, answeredByNightscout = false))
        assertEquals(TombstoneAction.WAIT, tombstoneAction(404, 0, answeredByNightscout = false))
        assertEquals(
            TombstoneAction.WAIT,
            tombstoneAction(403, MAX_DELETE_ATTEMPTS - 1, answeredByNightscout = false)
        )
    }

    // -- when a tombstone may go ------------------------------------------------

    @Test
    fun aConfirmedDeleteIsKeptUntilAReadNoLongerServesTheDocument() {
        // No read this pass (deferred or failed): sent again, and the next read settles it.
        assertEquals(Settlement.KEEP, settlement(deleteConfirmed = true, readsBack = true, served = null))
        assertEquals(Settlement.DROP, settlement(deleteConfirmed = true, readsBack = true, served = false))
    }

    @Test
    fun aDocumentStillServedAfterItsConfirmedDeleteWasNotDeleted() {
        assertEquals(Settlement.NOT_TAKEN, settlement(deleteConfirmed = true, readsBack = true, served = true))
    }

    @Test
    fun withNothingReadingBackAConfirmedDeleteIsDone() {
        assertEquals(Settlement.DROP, settlement(deleteConfirmed = true, readsBack = false, served = null))
    }

    @Test
    fun aLocalOnlyTombstoneLastsWhileReadsStillServeTheDocument() {
        // Sending off: never sent, it only keeps the treatment from being received again.
        assertEquals(Settlement.KEEP, settlement(deleteConfirmed = false, readsBack = true, served = true))
        assertEquals(Settlement.KEEP, settlement(deleteConfirmed = false, readsBack = true, served = null))
        assertEquals(Settlement.KEEP, settlement(deleteConfirmed = false, readsBack = false, served = null))
    }

    @Test
    fun aLocalOnlyTombstoneOutOfTheNewestTreatmentsTakesAReadOfItsDocument() {
        // The read holds only the newest treatments: an older one deleted here is left out of it
        // while still on the server, and dropping its tombstone let a follower's history bring it
        // back.
        assertEquals(Settlement.LOOK_UP, settlement(deleteConfirmed = false, readsBack = true, served = false))
    }

    @Test
    fun onlyAReadOfTheDocumentItselfLetsItsTombstoneGo() {
        assertTrue(lookupLetsGo(ReceivedDocumentRead.Gone))
        assertTrue(lookupLetsGo(ReceivedDocumentRead.Found(document(objectId, uuid).put("isValid", false))))
        assertFalse(lookupLetsGo(ReceivedDocumentRead.Found(document(objectId, uuid))))
        assertFalse(lookupLetsGo(ReceivedDocumentRead.Ambiguous))
        assertFalse(lookupLetsGo(ReceivedDocumentRead.Failed(-1)))
        assertFalse(lookupLetsGo(ReceivedDocumentRead.Failed(503)))
        // As the server answers: v3 404/410, an empty v1 find.
        assertTrue(lookupLetsGo(receivedDocumentRead(404, """{"status":404}""")))
        assertTrue(lookupLetsGo(receivedDocumentRead(200, "[]")))
        assertFalse(lookupLetsGo(receivedDocumentRead(404, "<html>Router</html>")))
    }

    @Test
    fun aDocumentIsLookedUpAtMostOnceADay() {
        val day = 24 * 60 * 60_000L
        val last = 1_786_794_604_000L
        assertTrue("never looked up", isLookupDue(0L, last))
        assertFalse(isLookupDue(last, last + day - 1))
        assertTrue(isLookupDue(last, last + day))
        assertTrue("the clock went back", isLookupDue(last, last - 1))
    }

    // -- what a read still serves ---------------------------------------------

    @Test
    fun aReadServesADocumentUnderAnyOfItsNames() {
        val body = JSONArray()
            .put(document(objectId, "jng-j-1a7"))
            .put(document("65a1b2c3d4e5f60718293a4c", uuid))
            .toString()

        assertEquals(
            setOf("jng-j-1a7", uuid),
            servedRemoteIds(body, setOf("jng-j-1a7", uuid, "65a1b2c3d4e5f60718293aff"))
        )
        assertEquals(setOf(objectId), servedRemoteIds(body, setOf(objectId)))
    }

    @Test
    fun anEmptyReadServesNothingAndAPageThatIsNotAListSaysNothing() {
        assertEquals(emptySet<String>(), servedRemoteIds("[]", setOf(objectId)))
        assertNull(servedRemoteIds("<html>Sign in to the Wi-Fi</html>", setOf(objectId)))
    }

    // -- an own v1 document known only by its identifier ------------------------

    @Test
    fun anUndatedIdentifierNamesTheOneDocumentThisInstallDoesNotStillHold() {
        val current = "65a1b2c3d4e5f60718293a01"
        val oldCopy = "65a1b2c3d4e5f60718293a02"
        val read = JSONArray()
            .put(document(current, "jng-j-1a7"))
            .put(document(oldCopy, "jng-j-1a7"))

        val named = documentForIdentifier(read, "jng-j-1a7", excludeRemoteIds = setOf(current), limit = 10)
        assertEquals(oldCopy, (named as IdentifierDocument.One).documentId)
    }

    @Test
    fun anIdentifierCarriedBySeveralDocumentsNamesNoneOfThem() {
        // Two other installs reusing the same row id: deleting a guess could take the wrong one.
        val read = JSONArray()
            .put(document("65a1b2c3d4e5f60718293a01", "jng-j-1a7"))
            .put(document("65a1b2c3d4e5f60718293a02", "jng-j-1a7"))

        assertEquals(IdentifierDocument.Several, documentForIdentifier(read, "jng-j-1a7", emptySet(), limit = 10))
        // A read as full as it may be can have left more out.
        val full = JSONArray().put(document("65a1b2c3d4e5f60718293a01", "jng-j-1a7"))
        assertEquals(IdentifierDocument.Several, documentForIdentifier(full, "jng-j-1a7", emptySet(), limit = 1))
    }

    @Test
    fun anIdentifierNoOtherDocumentCarriesIsGone() {
        val current = "65a1b2c3d4e5f60718293a01"
        val onlyOwn = JSONArray().put(document(current, "jng-j-1a7"))

        assertEquals(IdentifierDocument.None, documentForIdentifier(onlyOwn, "jng-j-1a7", setOf(current), limit = 10))
        assertEquals(IdentifierDocument.None, documentForIdentifier(JSONArray(), "jng-j-1a7", emptySet(), limit = 10))
    }

    @Test
    fun anUndatedIdentifierIsLookedUpOverAllTimeWithItsIds() {
        // Not the newest treatments: those leave an older document out, and it was taken as gone.
        assertEquals(
            "$base/api/v1/treatments.json?find%5Bidentifier%5D=jng-j-1a7&find%5Bcreated_at%5D%5B%24gte%5D=2000-01-01&count=10",
            identifierLookupUrl(base, "jng-j-1a7", 10)
        )
        assertEquals(1, documentsRead(200, JSONArray().put(document(objectId, "jng-j-1a7")).toString())!!.length())
        assertNull(documentsRead(200, "<html>Sign in to the Wi-Fi</html>"))
        assertNull(documentsRead(403, """{"status":403}"""))
        assertNull(documentsRead(-1, ""))
    }

    // -- a received treatment, deleted ------------------------------------------

    @Test
    fun theTombstoneOfAReceivedRowNamesTheDocumentItCameFrom() {
        // The importer skips what hasAnyRemoteIdentifier matches against the tombstones, and the
        // settlement looks for the same name in what the server serves.
        val received = JSONObject()
            .put("_id", objectId)
            .put("identifier", uuid)
            .put("eventType", "Meal Bolus")
            .put("insulin", 4.0)
            .put("carbs", 40.0)
            .put("date", 1_786_794_604_000L)
        val parsed = JournalTreatmentTransfer.parseTreatment(
            treatment = received,
            source = JournalEntrySource.NIGHTSCOUT,
            sourcePrefix = "nightscout:NSF-TEST",
            insulinPresets = emptyList(),
            stringResource = { "Treatment" },
        )!!

        assertEquals(2, parsed.inputs.size)
        parsed.inputs.forEach { input ->
            val tombstoneId = nightscoutDeleteRemoteId(JournalEntrySource.NIGHTSCOUT.storageValue, input.nsRemoteId)!!
            assertTrue(JournalTreatmentTransfer.hasAnyRemoteIdentifier(received, setOf(tombstoneId)))
            assertEquals(setOf(tombstoneId), servedRemoteIds(JSONArray().put(received).toString(), setOf(tombstoneId)))
            assertTrue(tombstoneDeleteUrl(base, tombstoneId, useV3 = false).contains("find%5Bidentifier%5D=$uuid"))
        }
    }

    // -- under API v3 -----------------------------------------------------------

    /** As a v3 read serves a document: under its identifier, without the _id. */
    private fun servedByV3(identifier: String): JSONObject =
        JSONObject().put("identifier", identifier).put("eventType", "Note")

    private val ownV1Upload = JournalPendingDeleteEntity(entryId = 0x1a7, nsRemoteId = objectId, deletedAt = 0L)

    @Test
    fun aV3ReadStillServesAnOwnV1UploadWaitingForItsDelete() {
        // The tombstone holds the _id, which v3 leaves out. Read as gone, the tombstone went, and
        // a delete that had failed let the document be received back.
        val names = JournalTreatmentUploader.v3NamesOfV1Documents(listOf(ownV1Upload))
        val body = JSONArray().put(servedByV3("jng-j-1a7")).toString()

        assertEquals(setOf(objectId), servedRemoteIds(body, setOf(objectId), names))
        assertEquals(objectId, JournalTreatmentUploader.v1DocumentServedByV3(servedByV3("jng-j-1a7"), names))
    }

    @Test
    fun aDocumentServedWithItsIdIsNotTakenForAnotherByTheRowId() {
        // Another install's document under the same row id, as v1 serves it: its own _id says so.
        val names = JournalTreatmentUploader.v3NamesOfV1Documents(listOf(ownV1Upload))
        val theirs = document("65a1b2c3d4e5f60718293a4c", "jng-j-1a7")

        assertNull(JournalTreatmentUploader.v1DocumentServedByV3(theirs, names))
        assertEquals(emptySet<String>(), servedRemoteIds(JSONArray().put(theirs).toString(), setOf(objectId), names))
    }

    @Test
    fun aTombstoneHoldingAnIdentifierNeedsNoOtherName() {
        val received = JournalPendingDeleteEntity(entryId = 0x1a7, nsRemoteId = uuid, deletedAt = 0L)

        assertTrue(JournalTreatmentUploader.v3NamesOfV1Documents(listOf(received)).isEmpty())
    }

    /** It is resolved to an _id before any delete, on v3 too, where it would take whichever. */
    @Test
    fun theUndatedIdentifierIsExactlyWhatTheV1PathWrites() {
        for (entryId in listOf(0L, 0x1a7L, Long.MAX_VALUE)) {
            assertTrue(JournalTreatmentUploader.isUndatedOwnIdentifier(JournalTreatmentUploader.v1Identifier(entryId)))
        }
        assertFalse(
            JournalTreatmentUploader.isUndatedOwnIdentifier(
                JournalTreatmentUploader.datedIdentifier(0x1a7L, 1_700_000_000_000L)
            )
        )
        assertFalse(JournalTreatmentUploader.isUndatedOwnIdentifier("jng-j-"))
        assertFalse(JournalTreatmentUploader.isUndatedOwnIdentifier(objectId))
    }
}
