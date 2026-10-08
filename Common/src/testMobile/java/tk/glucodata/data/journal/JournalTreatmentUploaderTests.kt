package tk.glucodata.data.journal

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.data.journal.JournalTreatmentUploader.MAX_DELETE_ATTEMPTS
import tk.glucodata.data.journal.JournalTreatmentUploader.TombstoneAction
import tk.glucodata.data.journal.JournalTreatmentUploader.tombstoneAction

class JournalTreatmentUploaderTests {
    @Test
    fun cloneJournalRowsAreNeverUploadedToNightscout() {
        assertTrue(JournalTreatmentUploader.isExternalMirrorSource(JournalEntrySource.CLONE.storageValue))
        assertTrue(
            JournalTreatmentUploader.isExternalMirrorSource(
                JournalEntrySource.CLONE_LOCAL_ICE.storageValue
            )
        )
        assertTrue(
            JournalTreatmentUploader.isExternalMirrorSource(
                JournalEntrySource.CLONE_TURN.storageValue
            )
        )
    }

    private fun preset(countsTowardIob: Boolean) = JournalInsulinPresetEntity(
        id = 1,
        displayName = "Test insulin",
        onsetMinutes = 30,
        durationMinutes = 720,
        accentColor = 0,
        curveJson = "",
        isBuiltIn = false,
        isArchived = false,
        countsTowardIob = countsTowardIob,
        sortOrder = 0
    )

    @Test
    fun longInsulinCanBeExcludedWithoutSuppressingOtherTreatments() {
        assertFalse(
            JournalTreatmentUploader.shouldUploadTreatment(
                JournalEntryType.INSULIN.storageValue,
                preset(countsTowardIob = false),
                sendLongInsulin = false
            )
        )
        assertTrue(
            JournalTreatmentUploader.shouldUploadTreatment(
                JournalEntryType.INSULIN.storageValue,
                preset(countsTowardIob = true),
                sendLongInsulin = false
            )
        )
        assertTrue(
            JournalTreatmentUploader.shouldUploadTreatment(
                JournalEntryType.CARBS.storageValue,
                null,
                sendLongInsulin = false
            )
        )
    }

    @Test
    fun longInsulinRemainsEnabledByTheUploadPolicyWhenRequested() {
        assertTrue(
            JournalTreatmentUploader.shouldUploadTreatment(
                JournalEntryType.INSULIN.storageValue,
                preset(countsTowardIob = false),
                sendLongInsulin = true
            )
        )
    }

    // -- how often treatments are read back ----------------------------------

    @Test
    fun theFirstReadGoesAtOnce() {
        val floor = JournalTreatmentUploader.ReceiveFloor(intervalMillis = 5 * 60_000L)
        assertEquals(0L, floor.waitMillis("v1 https://ns.example.com", 1_000_000L))
    }

    @Test
    fun aPassInsideTheIntervalWaitsForItsRemainder() {
        // The receive/wake loop ran a pass about every two seconds, each one re-reading the
        // newest 240 documents; this is what bounds that to one read per interval.
        val floor = JournalTreatmentUploader.ReceiveFloor(intervalMillis = 5 * 60_000L)
        val key = "v1 https://ns.example.com"
        floor.recordRead(key, 1_000_000L)
        assertEquals(5 * 60_000L - 2_000L, floor.waitMillis(key, 1_002_000L))
        assertEquals(0L, floor.waitMillis(key, 1_000_000L + 5 * 60_000L))
    }

    @Test
    fun anotherServerOrApiVersionIsReadAtOnce() {
        val floor = JournalTreatmentUploader.ReceiveFloor(intervalMillis = 5 * 60_000L)
        floor.recordRead("v1 https://ns.example.com", 1_000_000L)
        assertEquals(0L, floor.waitMillis("v3 https://ns.example.com", 1_001_000L))
        assertEquals(0L, floor.waitMillis("v1 https://other.example.com", 1_001_000L))
    }

    @Test
    fun aClockThatWentBackwardsDoesNotHoldReadsOff() {
        val floor = JournalTreatmentUploader.ReceiveFloor(intervalMillis = 5 * 60_000L)
        val key = "v1 https://ns.example.com"
        floor.recordRead(key, 1_000_000L)
        assertEquals(0L, floor.waitMillis(key, 900_000L))
    }

    // -- what a refusal reports --------------------------------------------

    @Test
    fun aRefusedWriteReportsThePermissionTheServerNamed() {
        // The field case: a role that may create and delete treatments but not update them.
        // The sentence is the actionable part; "403" alone sends people to the wrong setting.
        val body = """{"status":403,"message":"Missing permission api:treatments:update"}"""
        assertEquals("Missing permission api:treatments:update", JournalTreatmentUploader.serverMessage(body))
        assertEquals(
            "403: Missing permission api:treatments:update",
            JournalTreatmentUploader.failureText(403, JournalTreatmentUploader.serverMessage(body))
        )
    }

    @Test
    fun aBodyWithoutAMessageStillReportsSomething() {
        assertEquals("Unauthorized", JournalTreatmentUploader.serverMessage("Unauthorized"))
        assertEquals("""{"status":401}""", JournalTreatmentUploader.serverMessage("""{"status":401}"""))
        // No answer at all: the code stands alone rather than trailing an empty colon.
        assertEquals("-1", JournalTreatmentUploader.failureText(-1, ""))
    }

    // -- a re-upload never costs the server data -----------------------------

    @Test
    fun theOldCopyIsOnlyDeletedOnceTheServerHoldsADifferentNewDocument() {
        // v3: the re-upload carries the old identifier and updates that document in place.
        assertEquals(
            JournalTreatmentUploader.OldCopyAction.KEEP,
            JournalTreatmentUploader.oldCopyAction(oldRemoteId = "jng-j-fe", acceptedRemoteId = "jng-j-fe")
        )
        // v1 upsert: the server stored the new document under the old _id.
        assertEquals(
            JournalTreatmentUploader.OldCopyAction.KEEP,
            JournalTreatmentUploader.oldCopyAction(oldRemoteId = "65a1", acceptedRemoteId = "65a1")
        )
        // v1 create: a second document now exists, so the first one is surplus.
        assertEquals(
            JournalTreatmentUploader.OldCopyAction.DELETE,
            JournalTreatmentUploader.oldCopyAction(oldRemoteId = "65a1", acceptedRemoteId = "65b2")
        )
    }

    @Test
    fun acceptedDeleteClearsTheTombstone() {
        assertEquals(TombstoneAction.CLEAR, tombstoneAction(code = 200, attemptsSoFar = 0))
        assertEquals(TombstoneAction.CLEAR, tombstoneAction(code = 204, attemptsSoFar = 3))
    }

    @Test
    fun aDocumentTheServerNoLongerHasCountsAsDeleted() {
        // The old boolean path retried these forever: the delete can never succeed,
        // and there is nothing left on the server to delete either.
        assertEquals(TombstoneAction.CLEAR, tombstoneAction(code = 404, attemptsSoFar = 0))
        assertEquals(TombstoneAction.CLEAR, tombstoneAction(code = 410, attemptsSoFar = 0))
    }

    @Test
    fun aRefusedDeleteIsRetriedUntilTheAttemptCapThenKeptHere() {
        // 403 is the reported case: a role with api:treatments:create but not :delete.
        assertEquals(TombstoneAction.RETRY, tombstoneAction(code = 403, attemptsSoFar = 0))
        assertEquals(
            TombstoneAction.RETRY,
            tombstoneAction(code = 403, attemptsSoFar = MAX_DELETE_ATTEMPTS - 2)
        )
        // No longer sent, but the tombstone stays: the treatment stays deleted here.
        assertEquals(
            TombstoneAction.KEEP_LOCAL,
            tombstoneAction(code = 403, attemptsSoFar = MAX_DELETE_ATTEMPTS - 1)
        )
        assertEquals(
            TombstoneAction.KEEP_LOCAL,
            tombstoneAction(code = 403, attemptsSoFar = MAX_DELETE_ATTEMPTS)
        )
        assertFalse(
            JournalTreatmentUploader.sendsDelete(
                JournalPendingDeleteEntity(entryId = 1L, nsRemoteId = "65a1", deletedAt = 0L, attempts = MAX_DELETE_ATTEMPTS)
            )
        )
    }

    @Test
    fun aServerErrorOnOneDeleteIsCountedForThatDeleteAlone() {
        // A document the server fails on every time no longer holds the queue for good; it is
        // retried on its own, and past the cap kept here, never dropped.
        assertEquals(TombstoneAction.RETRY, tombstoneAction(code = 500, attemptsSoFar = 0))
        assertEquals(TombstoneAction.RETRY, tombstoneAction(code = 500, attemptsSoFar = 0, answeredByNightscout = false))
        assertEquals(TombstoneAction.KEEP_LOCAL, tombstoneAction(code = 500, attemptsSoFar = MAX_DELETE_ATTEMPTS - 1))
    }

    @Test
    fun onlyWhatIsAboutTheWholeServerMakesTheQueueWait() {
        val wait = JournalTreatmentUploader.OperationFailure.WAIT
        for (code in listOf(-1, 401, 408, 429, 502, 503, 504)) {
            assertEquals("$code", wait, JournalTreatmentUploader.operationFailure(code, answeredByNightscout = true))
        }
        assertEquals(wait, JournalTreatmentUploader.operationFailure(403, answeredByNightscout = false))
        assertEquals(
            JournalTreatmentUploader.OperationFailure.RETRY,
            JournalTreatmentUploader.operationFailure(500, answeredByNightscout = true)
        )
        for (code in listOf(400, 403, 422)) {
            assertEquals(
                JournalTreatmentUploader.OperationFailure.REFUSED,
                JournalTreatmentUploader.operationFailure(code, answeredByNightscout = true)
            )
        }
    }

    @Test
    fun aFailedOperationWaitsLongerEachTimeUpToItsCap() {
        val retryDelay = JournalTreatmentUploader::retryDelayMillis
        assertEquals(0L, retryDelay(0))
        assertEquals(60_000L, retryDelay(1))
        assertEquals(120_000L, retryDelay(2))
        assertEquals(240_000L, retryDelay(3))
        assertEquals(6 * 60 * 60_000L, retryDelay(12))
        assertEquals(6 * 60 * 60_000L, retryDelay(MAX_DELETE_ATTEMPTS))
        assertEquals(6 * 60 * 60_000L, retryDelay(Int.MAX_VALUE))
    }

    @Test
    fun aFailedOperationGoesAgainOnceItsWaitIsUp() {
        val last = 1_700_000_000_000L
        val isDue = JournalTreatmentUploader::isRetryDue
        assertTrue("never failed", isDue(0, 0L, last))
        assertFalse(isDue(1, last, last + 59_000L))
        assertTrue(isDue(1, last, last + 60_000L))
        assertFalse(isDue(3, last, last + 200_000L))
        assertTrue("the clock went back", isDue(3, last, last - 1_000L))
    }

    @Test
    fun anEditThatKeepsFailingCountsItsAttemptsUntilItIsEditedAgain() {
        val retries = JournalTreatmentUploader.EditRetries()
        val now = 1_700_000_000_000L
        val updatedAt = now - 60_000L

        assertTrue(retries.isDue(7L, updatedAt, now))
        assertNull(retries.dueAt(7L, updatedAt))
        assertEquals(1, retries.recordFailure(7L, updatedAt, now))
        assertFalse(retries.isDue(7L, updatedAt, now + 1_000L))
        assertEquals(now + 60_000L, retries.dueAt(7L, updatedAt))
        assertEquals(2, retries.recordFailure(7L, updatedAt, now + 60_000L))
        // Another entry is not held for it.
        assertTrue(retries.isDue(8L, updatedAt, now + 1_000L))
        // A further edit is a new one: tried at once, counted afresh.
        assertTrue(retries.isDue(7L, updatedAt + 5_000L, now + 61_000L))
        assertEquals(1, retries.recordFailure(7L, updatedAt + 5_000L, now + 61_000L))
        retries.clear(7L)
        assertTrue(retries.isDue(7L, updatedAt + 5_000L, now + 62_000L))
    }

    @Test
    fun aFirstUploadHasNoOldCopyToDelete() {
        assertEquals(
            JournalTreatmentUploader.OldCopyAction.KEEP,
            JournalTreatmentUploader.oldCopyAction(oldRemoteId = null, acceptedRemoteId = "65b2")
        )
    }

    // -- a refused entry is not knocked on every few seconds ------------------

    @Test
    fun aFailedEntryIsHeldAndTheHoldGrowsWhileItKeepsFailing() {
        val backoff = JournalTreatmentUploader.SendBackoff(firstDelayMillis = 60_000L, maxDelayMillis = 30 * 60_000L)
        val start = 1_000_000L
        assertFalse(backoff.shouldHold(254L, start))
        backoff.recordFailure(254L, start)
        // The trace: three identical attempts in twelve seconds.
        assertTrue(backoff.shouldHold(254L, start + 4_000L))
        assertTrue(backoff.shouldHold(254L, start + 12_000L))
        assertFalse(backoff.shouldHold(254L, start + 60_000L))
        backoff.recordFailure(254L, start + 60_000L)
        assertTrue(backoff.shouldHold(254L, start + 60_000L + 90_000L))
        assertFalse(backoff.shouldHold(254L, start + 60_000L + 120_000L))
    }

    @Test
    fun theHoldIsCappedAndNeverOutlastsItsCap() {
        val backoff = JournalTreatmentUploader.SendBackoff(firstDelayMillis = 60_000L, maxDelayMillis = 5 * 60_000L)
        var now = 0L
        repeat(10) {
            backoff.recordFailure(7L, now)
            now += 5 * 60_000L
            assertFalse(backoff.shouldHold(7L, now))
        }
    }

    @Test
    fun anotherEntryIsNotHeldForTheFailingOne() {
        val backoff = JournalTreatmentUploader.SendBackoff(firstDelayMillis = 60_000L, maxDelayMillis = 30 * 60_000L)
        backoff.recordFailure(254L, 1_000_000L)
        assertFalse(backoff.shouldHold(255L, 1_001_000L))
    }

    @Test
    fun anAcceptedWriteEndsTheHold() {
        val backoff = JournalTreatmentUploader.SendBackoff(firstDelayMillis = 60_000L, maxDelayMillis = 30 * 60_000L)
        backoff.recordFailure(254L, 1_000_000L)
        backoff.reset()
        assertFalse(backoff.shouldHold(254L, 1_001_000L))
    }

    @Test
    fun aClockThatMovedBackwardsDoesNotHoldTheEntryForLonger() {
        val backoff = JournalTreatmentUploader.SendBackoff(firstDelayMillis = 60_000L, maxDelayMillis = 30 * 60_000L)
        backoff.recordFailure(254L, 1_000_000L)
        assertFalse(backoff.shouldHold(254L, 1_000_000L - 5 * 60_000L))
    }

    /**
     * v3 refuses to let a client move a document's date: an entry whose time was corrected
     * cannot be an update, it has to be a new document. The name carries the time so the
     * uploader can tell the two apart without keeping a second copy of what it last sent.
     */
    @Test
    fun aCorrectedTimeIsADifferentDocument() {
        val atFirst = JournalTreatmentUploader.datedIdentifier(423L, 1_700_000_000_000L)
        val moved = JournalTreatmentUploader.datedIdentifier(423L, 1_700_000_600_000L)

        assertTrue(atFirst != moved)
        assertEquals(
            JournalTreatmentUploader.TreatmentWrite.UPDATE,
            JournalTreatmentUploader.treatmentWrite(atFirst, atFirst)
        )
        // The stored copy is the one at the old time, so this write creates.
        assertEquals(
            JournalTreatmentUploader.TreatmentWrite.CREATE,
            JournalTreatmentUploader.treatmentWrite(atFirst, moved)
        )
        // And then the old copy is the one to remove, which is already the rule.
        assertEquals(
            JournalTreatmentUploader.OldCopyAction.DELETE,
            JournalTreatmentUploader.oldCopyAction(atFirst, moved)
        )
    }

    @Test
    fun anEntryTheServerDoesNotHoldIsCreated() {
        val identifier = JournalTreatmentUploader.datedIdentifier(7L, 1_700_000_000_000L)

        assertEquals(
            JournalTreatmentUploader.TreatmentWrite.CREATE,
            JournalTreatmentUploader.treatmentWrite(null, identifier)
        )
    }

    @Test
    fun v3DeletesArePermanentSoLegacyTreatmentReadsCannotShowTheStaleCopy() {
        assertEquals(
            "https://ns.example.com/api/v3/treatments/jng-j-1a7-18bcfe56800?permanent=true",
            JournalTreatmentUploader.treatmentDeleteUrl(
                "https://ns.example.com",
                "jng-j-1a7-18bcfe56800",
                useV3 = true
            )
        )
    }

    @Test
    fun v1DeletesKeepTheirExistingEndpoint() {
        assertEquals(
            "https://ns.example.com/api/v1/treatments/65a1",
            JournalTreatmentUploader.treatmentDeleteUrl(
                "https://ns.example.com",
                "65a1",
                useV3 = false
            )
        )
    }

    /** Written before the time was part of the name: created once under the new name. */
    @Test
    fun anEntryStoredUnderTheOlderNameIsCreatedAfresh() {
        val legacy = "jng-j-1a7"
        val identifier = JournalTreatmentUploader.datedIdentifier(423L, 1_700_000_000_000L)

        assertEquals(
            JournalTreatmentUploader.TreatmentWrite.CREATE,
            JournalTreatmentUploader.treatmentWrite(legacy, identifier)
        )
        assertEquals(
            JournalTreatmentUploader.OldCopyAction.DELETE,
            JournalTreatmentUploader.oldCopyAction(legacy, identifier)
        )
    }

    /** The same amount at the same time is a change to what it says, which v3 allows. */
    @Test
    fun anEditedAmountStaysAnUpdate() {
        val identifier = JournalTreatmentUploader.datedIdentifier(423L, 1_700_000_000_000L)

        assertEquals(
            JournalTreatmentUploader.TreatmentWrite.UPDATE,
            JournalTreatmentUploader.treatmentWrite(identifier, identifier)
        )
        assertEquals(
            JournalTreatmentUploader.OldCopyAction.KEEP,
            JournalTreatmentUploader.oldCopyAction(identifier, identifier)
        )
    }

    @Test
    fun v3CreatesUseTheCollectionAndUpdatesUseTheDocumentEndpoint() {
        val baseUrl = "https://ns.example.com"
        val identifier = "jng-j-1a7-18bcfe56800"

        assertEquals(
            "$baseUrl/api/v3/treatments",
            JournalTreatmentUploader.treatmentWriteUrl(
                baseUrl,
                identifier,
                JournalTreatmentUploader.TreatmentWrite.CREATE
            )
        )
        assertEquals(
            "$baseUrl/api/v3/treatments/$identifier",
            JournalTreatmentUploader.treatmentWriteUrl(
                baseUrl,
                identifier,
                JournalTreatmentUploader.TreatmentWrite.UPDATE
            )
        )
    }

    @Test
    fun aDeleteThatNeverGotAnAnswerWaitsWithoutCountingAgainstTheTombstone() {
        // A server reachable only at home answers nothing for days while away; counting that
        // dropped the tombstone, and the next read at home brought the treatment back.
        assertEquals(TombstoneAction.WAIT, tombstoneAction(code = -1, attemptsSoFar = 0))
        assertEquals(TombstoneAction.WAIT, tombstoneAction(code = -1, attemptsSoFar = MAX_DELETE_ATTEMPTS))
        assertEquals(TombstoneAction.WAIT, tombstoneAction(code = 502, attemptsSoFar = MAX_DELETE_ATTEMPTS - 1))
        assertEquals(TombstoneAction.WAIT, tombstoneAction(code = 503, attemptsSoFar = 0))
        assertEquals(TombstoneAction.WAIT, tombstoneAction(code = 408, attemptsSoFar = 0))
        assertEquals(TombstoneAction.WAIT, tombstoneAction(code = 429, attemptsSoFar = 0))
    }

    // -- receive endpoint --------------------------------------------------

    @Test
    fun receivingFollowsTheConfiguredApiVersion() {
        val v1 = JournalTreatmentUploader.treatmentFetchUrl("https://ns.example.com", useV3 = false, count = 240)
        assertEquals("https://ns.example.com/api/v1/treatments.json?count=240", v1)

        val v3 = JournalTreatmentUploader.treatmentFetchUrl("https://ns.example.com", useV3 = true, count = 240)
        assertTrue(v3, v3.startsWith("https://ns.example.com/api/v3/treatments?"))
        // v3 counts with limit and has no .json suffix; sending the v1 form is what answered 401.
        assertTrue(v3, v3.contains("limit=240"))
        assertFalse(v3, v3.contains(".json"))
        assertFalse(v3, v3.contains("count="))
    }

    @Test
    fun v3ResultEnvelopeIsUnwrappedForTheImporter() {
        val body = """{"status":200,"result":[{"identifier":"a"},{"identifier":"b"}]}"""
        val array = JSONArray(JournalTreatmentUploader.treatmentsArrayBody(body, useV3 = true))
        assertEquals(2, array.length())
        assertEquals("a", array.getJSONObject(0).getString("identifier"))
    }

    @Test
    fun v1ArraysAndV3HostsAnsweringArraysBothPassThrough() {
        val array = """[{"identifier":"a"}]"""
        assertEquals(array, JournalTreatmentUploader.treatmentsArrayBody(array, useV3 = false))
        assertEquals(array, JournalTreatmentUploader.treatmentsArrayBody(array, useV3 = true))
    }

    @Test
    fun anEnvelopeWithoutResultYieldsAnEmptyArrayRatherThanAParseFailure() {
        assertEquals("[]", JournalTreatmentUploader.treatmentsArrayBody("""{"status":401}""", useV3 = true))
    }

    // -- what a refusal reports --------------------------------------------

    @Test
    fun aRefusedReadReportsThePermissionTheServerNamed() {
        // An upload-only token answers this; the sentence is the actionable part, and
        // it is what distinguishes a missing permission from wrong credentials.
        val body = """{"status":403,"message":"Missing permission api:treatments:read"}"""
        assertEquals("Missing permission api:treatments:read", JournalTreatmentUploader.serverMessage(body))
    }

    @Test
    fun theFailureNamesTheEndpointPathWithoutRepeatingTheHost() {
        assertEquals(
            "/api/v3/treatments",
            JournalTreatmentUploader.endpointPath("https://ns.example.com/api/v3/treatments?limit=240")
        )
    }

    // -- repeating failure logging -----------------------------------------

    @Test
    fun anUnchangedFailureIsLoggedOncePerInterval() {
        val log = JournalTreatmentUploader.RepeatedErrorLog(intervalMillis = 5 * 60_000L)
        val start = 1_000_000L
        assertEquals(0, log.suppressedSince("HTTP 401", start))
        assertEquals(-1, log.suppressedSince("HTTP 401", start + 1_000))
        assertEquals(-1, log.suppressedSince("HTTP 401", start + 2_000))
        // The line that finally speaks says how many it stood in for.
        assertEquals(2, log.suppressedSince("HTTP 401", start + 5 * 60_000L))
    }

    @Test
    fun aDifferentFailureIsNeverHiddenBehindTheOldOnesInterval() {
        val log = JournalTreatmentUploader.RepeatedErrorLog(intervalMillis = 5 * 60_000L)
        val start = 1_000_000L
        assertEquals(0, log.suppressedSince("HTTP 401", start))
        assertEquals(0, log.suppressedSince("HTTP 500", start + 1_000))
    }

    @Test
    fun aSuccessEndsTheEpisodeSoTheNextFailureReportsAtOnce() {
        val log = JournalTreatmentUploader.RepeatedErrorLog(intervalMillis = 5 * 60_000L)
        val start = 1_000_000L
        assertEquals(0, log.suppressedSince("HTTP 401", start))
        assertEquals(-1, log.suppressedSince("HTTP 401", start + 1_000))
        log.reset()
        assertEquals(0, log.suppressedSince("HTTP 401", start + 2_000))
    }

    @Test
    fun aClockThatMovedBackwardsDoesNotSilenceTheLogForever() {
        val log = JournalTreatmentUploader.RepeatedErrorLog(intervalMillis = 5 * 60_000L)
        val start = 1_000_000L
        assertEquals(0, log.suppressedSince("HTTP 401", start))
        assertEquals(0, log.suppressedSince("HTTP 401", start - 60_000L))
    }

    /** A token without api:treatments:read answers every read with 403. */
    @Test
    fun aRefusedReceiveDoesNotHoldTheSendsBack() {
        for (wrote in listOf(false, true)) {
            assertTrue(
                JournalTreatmentUploader.treatmentPassOk(
                    sendsOk = true,
                    deletesUnanswered = false,
                    receive = JournalTreatmentUploader.ReceiveResult.REFUSED,
                    wroteThisPass = wrote
                )
            )
        }
    }

    @Test
    fun anUnansweredReceiveBacksOffUnlessThisPassReachedTheServer() {
        assertFalse(
            JournalTreatmentUploader.treatmentPassOk(
                sendsOk = true,
                deletesUnanswered = false,
                receive = JournalTreatmentUploader.ReceiveResult.UNANSWERED,
                wroteThisPass = false
            )
        )
        assertTrue(
            JournalTreatmentUploader.treatmentPassOk(
                sendsOk = true,
                deletesUnanswered = false,
                receive = JournalTreatmentUploader.ReceiveResult.UNANSWERED,
                wroteThisPass = true
            )
        )
    }

    @Test
    fun failedSendsAndUnansweredDeletesStillBackOff() {
        assertFalse(
            JournalTreatmentUploader.treatmentPassOk(
                sendsOk = false,
                deletesUnanswered = false,
                receive = JournalTreatmentUploader.ReceiveResult.DONE,
                wroteThisPass = true
            )
        )
        assertFalse(
            JournalTreatmentUploader.treatmentPassOk(
                sendsOk = true,
                deletesUnanswered = true,
                receive = JournalTreatmentUploader.ReceiveResult.DONE,
                wroteThisPass = true
            )
        )
    }
}
