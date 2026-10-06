package tk.glucodata.data.journal

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.data.journal.JournalTreatmentUploader.MAX_DELETE_ATTEMPTS
import tk.glucodata.data.journal.JournalTreatmentUploader.Settlement
import tk.glucodata.data.journal.JournalTreatmentUploader.TombstoneAction
import tk.glucodata.data.journal.JournalTreatmentUploader.answeredByNightscout
import tk.glucodata.data.journal.JournalTreatmentUploader.servedRemoteIds
import tk.glucodata.data.journal.JournalTreatmentUploader.settlement
import tk.glucodata.data.journal.JournalTreatmentUploader.soleDocumentIdForIdentifier
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
        assertEquals(Settlement.DROP, settlement(deleteConfirmed = false, readsBack = true, served = false))
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
            .put(document("65a1b2c3d4e5f60718293a03", "jng-j-1a8"))

        assertEquals(oldCopy, soleDocumentIdForIdentifier(read, "jng-j-1a7", excludeRemoteIds = setOf(current)))
    }

    @Test
    fun anIdentifierCarriedByNoDocumentOrBySeveralNamesNone() {
        // Two other installs reusing the same row id: deleting a guess could take the wrong one.
        val read = JSONArray()
            .put(document("65a1b2c3d4e5f60718293a01", "jng-j-1a7"))
            .put(document("65a1b2c3d4e5f60718293a02", "jng-j-1a7"))

        assertNull(soleDocumentIdForIdentifier(read, "jng-j-1a7", excludeRemoteIds = emptySet()))
        assertNull(soleDocumentIdForIdentifier(read, "jng-j-999", excludeRemoteIds = emptySet()))
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
}
