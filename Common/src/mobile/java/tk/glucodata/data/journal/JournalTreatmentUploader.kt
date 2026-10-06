package tk.glucodata.data.journal

import android.os.Handler
import android.os.Looper
import androidx.annotation.Keep
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import tk.glucodata.Applic
import tk.glucodata.JournalTreatmentUploadBridge
import tk.glucodata.Log
import tk.glucodata.Natives
import tk.glucodata.NightPost
import tk.glucodata.UiRefreshBus
import tk.glucodata.data.HistoryDatabase
import tk.glucodata.drivers.nightscout.NightscoutFollowerRegistry
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Sends Kotlin Journal entries to Nightscout as treatments. Replaces the legacy C++
 * uploadtreatments() path that pulled
 * from Numdata. Invoked from the native upload loop via NightPost.
 *
 * Sync state is tracked per-row on JournalEntryEntity (nsUploadedAt, nsRemoteId);
 * deletes are queued in journal_pending_deletes so they survive process death.
 */
@Keep
object JournalTreatmentUploader : JournalTreatmentUploadBridge {
    private const val LOG_ID = "JournalTreatmentUploader"
    private const val ID_PREFIX = "jng-j-"
    private const val LOOKBACK_MILLIS = 30L * 24 * 60 * 60 * 1000  // mirrors C++ nighttimeback (30 days)
    private const val PREFS_NAME = "tk.glucodata_preferences"
    private const val PREF_RECEIVE_TREATMENTS = "nightscout_receive_treatments"
    private const val PREF_SEND_LONG_INSULIN = "nightscout_send_long_insulin"
    private const val TREATMENT_FETCH_COUNT = 240
    private const val ERROR_INVALID_URL = -2
    private const val SEND_BACKOFF_FIRST_MILLIS = 60_000L
    private const val SEND_BACKOFF_MAX_MILLIS = 30L * 60_000L
    private const val RECEIVE_ERROR_LOG_INTERVAL_MILLIS = 5L * 60 * 1000
    private const val RECEIVE_MIN_INTERVAL_MILLIS = 5L * 60 * 1000

    /**
     * Refused deletes the tombstone survives before it is dropped. A document Nightscout
     * will never let us delete (a token without `api:treatments:delete` answers 403 forever)
     * must not stay queued for the rest of the install's life.
     */
    internal const val MAX_DELETE_ATTEMPTS = 20

    /**
     * Lower date bound for a v1 delete by query. Without a date in the query Nightscout only
     * looks at the last four days, so an older treatment would answer "nothing deleted" and stay.
     */
    private const val V1_QUERY_ALL_TIME = "2000-01-01"
    private const val HTTP_TOO_MANY_REQUESTS = 429

    internal enum class TombstoneAction { CLEAR, RETRY, WAIT, GIVE_UP }

    /** What a pass does with a tombstone once it knows whether the server still serves it. */
    internal enum class Settlement { DROP, KEEP, NOT_TAKEN }

    private data class UploadResult(
        val code: Int,
        val remoteId: String? = null,
        /** What the server said when it refused, so a 403 can name the missing permission. */
        val message: String = ""
    )

    /**
     * The server's own words when it has any. A refusal says which permission is missing
     * ("Missing permission api:treatments:update" on a role that may create but not change,
     * "...:read" on an upload-only token), and that sentence is the whole difference between
     * an actionable failure and a bare status code.
     */
    internal fun serverMessage(body: String): String {
        val trimmed = body.trim()
        if (!trimmed.startsWith("{")) return trimmed.take(160)
        val message = runCatching {
            JSONObject(trimmed).let { it.optNonBlank("message") ?: it.optNonBlank("description") }
        }.getOrNull()
        return message ?: trimmed.take(160)
    }

    private fun JSONObject.optNonBlank(key: String): String? =
        optString(key).trim().takeIf { it.isNotEmpty() }

    /** "code" alone, or "code: what the server said" when it said anything. */
    internal fun failureText(code: Int, message: String): String =
        if (message.isBlank()) code.toString() else "$code: $message"

    /**
     * The rule of the re-upload path: a failed write must never leave the server with less
     * data than it had. An edited entry used to go out as delete-then-POST; when the POST was
     * refused (a token without api:treatments:update answers 403) the delete had already gone
     * through, and the entry was not stale on Nightscout but gone. So the new document is
     * written first, and only once it has been accepted is the old copy surplus -- and only
     * when it is a different document. On v3 the re-upload carries the old identifier and is
     * an update of that document, so there is nothing old left to delete; on v1 the server
     * answers with the _id it stored under, which is the old one when it upserted.
     */
    internal enum class OldCopyAction { KEEP, DELETE }

    /** Whether a v3 write creates a document or changes one the server already holds. */
    internal enum class TreatmentWrite { CREATE, UPDATE }

    /**
     * What a v3 document is named. The time is part of the name because v3 will not let a
     * client move a document's date: an entry whose time was corrected is a different
     * document, and saying so in the identifier is what turns that write into a create the
     * server accepts, after which the old copy is deleted as any other stale copy is.
     */
    internal fun datedIdentifier(entryId: Long, timestampMillis: Long): String =
        ID_PREFIX + entryId.toString(16) + "-" + timestampMillis.toString(16)

    /**
     * The identifier a v1 write gives its document: the row id alone. v1 remembers the document
     * by the _id it answers with, but v3 serves it under this identifier only (see
     * [JournalTreatmentTransfer.isOwnV1Document]).
     */
    internal fun v1Identifier(entryId: Long): String = ID_PREFIX + entryId.toString(16)

    /**
     * Whether [remoteId] is a [v1Identifier]: this app's undated identifier, the bare row id.
     * Another install, or this one after a reinstall, reuses it, so it may only name a document
     * together with its _id. Not a [datedIdentifier], whose time follows a dash.
     */
    internal fun isUndatedOwnIdentifier(remoteId: String): Boolean =
        remoteId.length > ID_PREFIX.length &&
            remoteId.startsWith(ID_PREFIX, ignoreCase = true) &&
            remoteId.substring(ID_PREFIX.length).all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

    /**
     * An update only where the server already holds this exact document; anything else is a
     * create. Entries written before the time was part of the name land here too, once each:
     * they are created afresh under the new name and their old copy is then removed.
     */
    internal fun treatmentWrite(nsRemoteId: String?, identifier: String): TreatmentWrite =
        if (nsRemoteId != null && nsRemoteId == identifier) TreatmentWrite.UPDATE else TreatmentWrite.CREATE

    internal fun oldCopyAction(oldRemoteId: String?, acceptedRemoteId: String?): OldCopyAction =
        if (oldRemoteId == null || oldRemoteId == acceptedRemoteId) OldCopyAction.KEEP else OldCopyAction.DELETE

    /**
     * Holds off a re-attempt of an entry that just failed. The uploader is woken by every
     * journal change and the trace showed the same refused entry knocking three times in
     * twelve seconds; a refusing server does not change its mind that fast. The hold doubles
     * from [firstDelayMillis] up to [maxDelayMillis] while the same entry keeps failing, and
     * ends with the next accepted write. Per entry, so a new entry behind it is not held for
     * an old one's sins -- though the loop still stops at the first failure, as it always has.
     */
    internal class SendBackoff(private val firstDelayMillis: Long, private val maxDelayMillis: Long) {
        private var entryId: Long? = null
        private var delayMillis = 0L
        private var notBeforeMillis = 0L

        /** True while the entry's last failure is still cooling off. */
        fun shouldHold(entryId: Long, nowMillis: Long): Boolean {
            if (entryId != this.entryId) return false
            // A clock that jumped back must not hold the entry for longer than one delay.
            return nowMillis < notBeforeMillis && nowMillis >= notBeforeMillis - delayMillis
        }

        fun recordFailure(entryId: Long, nowMillis: Long) {
            delayMillis = if (entryId == this.entryId && delayMillis > 0L) {
                minOf(delayMillis * 2, maxDelayMillis)
            } else {
                firstDelayMillis
            }
            this.entryId = entryId
            notBeforeMillis = nowMillis + delayMillis
        }

        /** An accepted write ends the episode. */
        fun reset() {
            entryId = null
            delayMillis = 0L
            notBeforeMillis = 0L
        }
    }

    private val sendBackoff = SendBackoff(SEND_BACKOFF_FIRST_MILLIS, SEND_BACKOFF_MAX_MILLIS)
    /** True only when the server actually removed the document for us. */
    internal fun isDeleteAccepted(code: Int): Boolean =
        code == HttpURLConnection.HTTP_OK || code == HttpURLConnection.HTTP_NO_CONTENT

    /**
     * What to do with a tombstone after a delete answered [code]. 404/410 count as done:
     * the document we wanted gone is gone, and the old code kept retrying those forever.
     *
     * Only a refusal counts toward [MAX_DELETE_ATTEMPTS]. No answer at all (a server reachable
     * only at home, seen from elsewhere), a gateway or server error, or a page that is not
     * Nightscout's says nothing about the document: counting those dropped the tombstone after
     * a few days away, and the next read brought the deleted treatment back.
     */
    internal fun tombstoneAction(
        code: Int,
        attemptsSoFar: Int,
        answeredByNightscout: Boolean = true
    ): TombstoneAction = when {
        !answeredByNightscout || isTransientDeleteFailure(code) -> TombstoneAction.WAIT
        code == HttpURLConnection.HTTP_OK ||
            code == HttpURLConnection.HTTP_NO_CONTENT ||
            code == HttpURLConnection.HTTP_NOT_FOUND ||
            code == HttpURLConnection.HTTP_GONE -> TombstoneAction.CLEAR
        else -> refusedDeleteAction(attemptsSoFar)
    }

    internal fun refusedDeleteAction(attemptsSoFar: Int): TombstoneAction =
        if (attemptsSoFar + 1 >= MAX_DELETE_ATTEMPTS) TombstoneAction.GIVE_UP else TombstoneAction.RETRY

    private fun isTransientDeleteFailure(code: Int): Boolean =
        code < 0 ||
            code >= HttpURLConnection.HTTP_INTERNAL_ERROR ||
            code == HttpURLConnection.HTTP_CLIENT_TIMEOUT ||
            code == HTTP_TOO_MANY_REQUESTS

    /**
     * Whether a delete was answered by Nightscout at all. Nightscout answers in JSON, refusals
     * included, and a 204 has no body. Anything else, such as a captive portal's page or another
     * device at the same LAN address away from home, must neither clear a tombstone nor count
     * against it.
     */
    internal fun answeredByNightscout(code: Int, body: String): Boolean {
        if (code < 0) return false
        if (code == HttpURLConnection.HTTP_NO_CONTENT) return true
        val trimmed = body.trimStart()
        return trimmed.startsWith("{") || trimmed.startsWith("[")
    }

    /**
     * What becomes of a tombstone after a pass that may have read treatments back.
     *
     * A tombstone outlives the delete: until a read made after it no longer serves the document,
     * a read already under way, or a server that said "deleted" without deleting, would bring
     * the treatment straight back. A tombstone that was never sent (sending is off) only stops
     * the treatment from being received again, so it goes once reads no longer serve it.
     *
     * @param deleteConfirmed the server confirmed the delete in this pass
     * @param readsBack whether this uploader reads treatments back at all
     * @param served whether this pass's read, made after the deletes, still served the document;
     *   null when no usable read was made
     */
    internal fun settlement(deleteConfirmed: Boolean, readsBack: Boolean, served: Boolean?): Settlement = when {
        deleteConfirmed && !readsBack -> Settlement.DROP
        // Sent again next pass, whose read settles it; the server answers "gone" meanwhile.
        served == null -> Settlement.KEEP
        !served -> Settlement.DROP
        deleteConfirmed -> Settlement.NOT_TAKEN
        else -> Settlement.KEEP
    }

    /**
     * Which of [wanted] a treatments read still serves; null when the body is not a list of
     * treatments, which says nothing either way. [v3Names] are the other names a v3 read serves
     * some of them under ([v3NamesOfV1Documents]).
     */
    internal fun servedRemoteIds(
        treatmentsBody: String,
        wanted: Set<String>,
        v3Names: Map<String, String> = emptyMap()
    ): Set<String>? {
        val array = runCatching { JSONArray(treatmentsBody.trim()) }.getOrNull() ?: return null
        val served = HashSet<String>()
        for (index in 0 until array.length()) {
            val treatment = array.optJSONObject(index) ?: continue
            JournalTreatmentTransfer.remoteIdentifiersOf(treatment).filterTo(served) { it in wanted }
            v1DocumentServedByV3(treatment, v3Names)?.takeIf { it in wanted }?.let(served::add)
        }
        return served
    }

    /** A Nightscout v1 document id (a Mongo ObjectId), as opposed to a client identifier. */
    internal fun isObjectId(remoteId: String): Boolean =
        remoteId.length == 24 && remoteId.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

    /**
     * The names a v3 read serves the documents of queued deletes under, where those are not the
     * names the tombstones hold: identifier → the ObjectId it stands for.
     *
     * This app remembers a document it sent over v1 by the _id Nightscout answered with. A v3
     * read leaves the _id out and serves such a document under the identifier the v1 path gave
     * it ([v1Identifier]), made from the row id the tombstone keeps. Only these tombstones hold an
     * ObjectId for a document that has an identifier: a received row holds the identifier when
     * the document has one, and v3 serves a document without one under its _id.
     */
    internal fun v3NamesOfV1Documents(tombstones: Collection<JournalPendingDeleteEntity>): Map<String, String> =
        tombstones.filter { isObjectId(it.nsRemoteId) }.associate { v1Identifier(it.entryId) to it.nsRemoteId }

    /**
     * The ObjectId [treatment] is, when a v3 read served it under one of [v3Names]; null otherwise.
     * A document served with its _id is matched by that instead: only a v3 read leaves it out.
     */
    internal fun v1DocumentServedByV3(treatment: JSONObject, v3Names: Map<String, String>): String? {
        if (v3Names.isEmpty() || treatment.optString("_id").isNotBlank()) return null
        return v3Names[treatment.optString("identifier")]
    }

    /**
     * Keeps an unchanged, repeating failure to one line per interval. The sync retries far
     * faster than a stuck server recovers, and the identical line every few seconds is what
     * made the trace unreadable while a Nightscout receive failure was being diagnosed.
     */
    internal class RepeatedErrorLog(private val intervalMillis: Long) {
        private var lastMessage: String? = null
        private var lastLoggedAt = 0L
        private var suppressed = 0

        /**
         * @return how many repeats were swallowed since the last line, or -1 to stay silent.
         *         A changed message always speaks, so a new failure is never hidden behind an
         *         old one's interval.
         */
        fun suppressedSince(message: String, nowMillis: Long): Int {
            val elapsed = nowMillis - lastLoggedAt
            val due = message != lastMessage || lastLoggedAt == 0L ||
                elapsed >= intervalMillis || elapsed < 0
            if (!due) {
                suppressed++
                return -1
            }
            val repeats = if (message == lastMessage) suppressed else 0
            lastMessage = message
            lastLoggedAt = nowMillis
            suppressed = 0
            return repeats
        }

        /** A success ends the episode, so the next failure reports immediately. */
        fun reset() {
            lastMessage = null
            lastLoggedAt = 0L
            suppressed = 0
        }
    }

    private val receiveErrorLog = RepeatedErrorLog(RECEIVE_ERROR_LOG_INTERVAL_MILLIS)

    /**
     * Lower bound on how often treatments are read back. The read is the newest 240 documents
     * every time (a few hundred KB on a busy site), and it runs on every treatment pass, so
     * anything that makes passes frequent multiplies it directly: the receive/wake loop did
     * about one pass every two seconds, tens of MB in ten minutes. Nightscout gives no way to
     * ask only for what changed that covers v1-written documents (they carry no srvModified,
     * so v3 history misses them, and a created_at window misses back-dated entries), so the
     * rate is what gets bounded. A pass inside the interval is not dropped: one wake is
     * booked for when the interval ends, so a remote change still arrives without waiting
     * for the next local edit.
     *
     * Keyed by server and API version, so pointing the app at a different site reads it at once.
     */
    internal class ReceiveFloor(private val intervalMillis: Long) {
        private var lastKey: String? = null
        private var lastReadAt = 0L

        /** 0 when a read may go now, otherwise how long until it may. */
        fun waitMillis(key: String, nowMillis: Long): Long {
            if (key != lastKey || lastReadAt == 0L) return 0L
            val elapsed = nowMillis - lastReadAt
            if (elapsed < 0 || elapsed >= intervalMillis) return 0L
            return intervalMillis - elapsed
        }

        /** Only a read that succeeded starts the interval; a failure is the native backoff's. */
        fun recordRead(key: String, nowMillis: Long) {
            lastKey = key
            lastReadAt = nowMillis
        }
    }

    private val receiveFloor = ReceiveFloor(RECEIVE_MIN_INTERVAL_MILLIS)
    private val deferredReceiveBooked = AtomicBoolean(false)

    private fun bookDeferredReceive(delayMillis: Long) {
        if (!deferredReceiveBooked.compareAndSet(false, true)) return
        Handler(Looper.getMainLooper()).postDelayed({
            deferredReceiveBooked.set(false)
            runCatching { Natives.waketreatments() }
                .onFailure { Log.e(LOG_ID, "deferred receive wake failed: ${Log.stackline(it)}") }
        }, delayMillis)
    }

    /** Path only: the host is already known and repeating it just crowds the line. */
    internal fun endpointPath(endpoint: String): String =
        runCatching { URL(endpoint).path }.getOrNull()?.takeIf { it.isNotBlank() } ?: endpoint

    // Mirrors writetreatment(V3) acceptance: 200/201 always; 409 only on V3 (POST conflict).
    private fun isUploadOk(code: Int, useV3: Boolean): Boolean {
        if (code == HttpURLConnection.HTTP_OK || code == HttpURLConnection.HTTP_CREATED) return true
        if (useV3 && code == HttpURLConnection.HTTP_CONFLICT) return true
        return false
    }

    @Keep
    override fun uploadAll(useV3: Boolean): Boolean = runBlocking {
        try {
            uploadInternal(useV3)
        } catch (th: Throwable) {
            Log.e(LOG_ID, "uploadAll failed: ${Log.stackline(th)}")
            false
        }
    }

    @Keep
    override fun getReceiveTreatments(): Boolean =
        Applic.app.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
            .getBoolean(PREF_RECEIVE_TREATMENTS, false)

    @JvmStatic
    @Keep
    fun setReceiveTreatments(enabled: Boolean) {
        Applic.app.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PREF_RECEIVE_TREATMENTS, enabled)
            .apply()
    }

    @JvmStatic
    @Keep
    fun getSendLongInsulin(): Boolean =
        Applic.app.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
            .getBoolean(PREF_SEND_LONG_INSULIN, true)

    @JvmStatic
    @Keep
    fun setSendLongInsulin(enabled: Boolean) {
        Applic.app.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PREF_SEND_LONG_INSULIN, enabled)
            .apply()
    }

    private suspend fun uploadInternal(useV3: Boolean): Boolean {
        val sendEnabled = Natives.getpostTreatments()
        val receiveEnabled = getReceiveTreatments()
        val sendLongInsulin = getSendLongInsulin()
        if (!sendEnabled && !receiveEnabled) return true
        val baseUrl = Natives.getnightuploadurl()?.takeIf { it.isNotBlank() } ?: return true
        val secretHashed = if (useV3) null else hashedSecret(Natives.getnightuploadsecret())
        val rawSecret = Natives.getnightuploadsecret().orEmpty()
        val dao = HistoryDatabase.getInstance(Applic.app).journalDao()
        val presetCache = HashMap<Long, JournalInsulinPresetEntity?>()
        val foodCache = HashMap<Long, JournalFoodEntity?>()
        var uploadOk = true
        var acceptedDocument = false
        var uploadFailureCode: Int? = null

        // Deletes and creates are independent, so a refused delete must not cost us the
        // pending entries: it used to break out of the whole run and, since the tombstone
        // was never cleared, wedge every later cycle at the same document (issue #191).
        val deletes = if (sendEnabled) {
            sendPendingDeletes(dao, baseUrl, rawSecret, secretHashed, useV3)
        } else {
            null
        }
        if (deletes?.acceptedDocument == true) acceptedDocument = true

        val sinceMillis = System.currentTimeMillis() - LOOKBACK_MILLIS
        if (sendEnabled) {
            val pending = dao.getEntriesNeedingNightscoutUpload(sinceMillis)
            for (entry in pending) {
                if (!isSendableType(entry.entryType)) continue
                if (isExternalMirrorSource(entry.source)) continue

                val preset = entry.insulinPresetId?.let { id ->
                    presetCache.getOrPut(id) { dao.getInsulinPresetById(id) }
                }
                if (!shouldUploadTreatment(entry.entryType, preset, sendLongInsulin)) continue

                if (sendBackoff.shouldHold(entry.id, System.currentTimeMillis())) {
                    // Same entry, same refusal a moment ago: reported then, not again now.
                    uploadOk = false
                    break
                }

                val localIdentifier = v1Identifier(entry.id)
                val remoteId = if (useV3) {
                    datedIdentifier(entry.id, entry.timestamp)
                } else {
                    localIdentifier
                }
                // A re-upload writes first; the old copy is dealt with after the server has
                // accepted the new document (see oldCopyAction). It used to be deleted here,
                // before the POST, and a refused POST then left the entry gone from Nightscout.

                val food = entry.foodId?.let { id ->
                    foodCache.getOrPut(id) { dao.getFoodById(id) }
                }
                val json = JournalTreatmentTransfer.buildTreatmentJson(
                    entry = entry,
                    remoteId = remoteId,
                    preset = preset,
                    food = food,
                    useV3 = useV3,
                    includeRemoteId = useV3
                )
                    ?: continue
                val result = if (useV3) {
                    // A partial update belongs at the concrete document endpoint. Collection
                    // POST is only for a create and requires date, while PATCH deliberately
                    // omits the immutable time and identity fields.
                    val write = treatmentWrite(entry.nsRemoteId, remoteId)
                    if (write == TreatmentWrite.UPDATE) {
                        JournalTreatmentTransfer.stripImmutableForUpdate(json)
                    }
                    uploadViaNightPost(baseUrl, json, secretHashed, useV3, remoteId, write)
                } else {
                    json.remove("_id")
                    json.put("identifier", localIdentifier)
                    postV1Treatment(baseUrl, rawSecret, json, localIdentifier, entry.timestamp, entry.nsRemoteId)
                }
                val now = System.currentTimeMillis()
                if (!isUploadOk(result.code, useV3)) {
                    Log.e(LOG_ID, "upload failed entry id=${entry.id} code=${failureText(result.code, result.message)}")
                    sendBackoff.recordFailure(entry.id, now)
                    uploadFailureCode = result.code
                    uploadOk = false
                    break
                }
                sendBackoff.reset()
                val acceptedRemoteId = result.remoteId ?: remoteId
                dao.markEntryUploadedToNightscout(entry.id, acceptedRemoteId, now)
                acceptedDocument = true
                if (oldCopyAction(entry.nsRemoteId, acceptedRemoteId) == OldCopyAction.DELETE) {
                    val oldRemoteId = entry.nsRemoteId!!
                    // An undated identifier is the queue's to name first: deleted as it is, v1
                    // fails on it and v3 takes whichever document carries it.
                    if (isUndatedOwnIdentifier(oldRemoteId)) {
                        dao.enqueuePendingNightscoutDelete(
                            JournalPendingDeleteEntity(entryId = entry.id, nsRemoteId = oldRemoteId, deletedAt = now)
                        )
                    } else if (!NightPost.deleteUrl(tombstoneDeleteUrl(baseUrl, oldRemoteId, useV3), secretHashed)) {
                        // The new document is on the server; the stale one is a duplicate, not a
                        // loss. It is queued with the deletes so the next cycle tries again.
                        Log.e(
                            LOG_ID,
                            "old copy of entry id=${entry.id} remoteId=$oldRemoteId not deleted: " +
                                serverMessage(NightPost.getLastPrimaryResponseBody()) + "; queued"
                        )
                        dao.enqueuePendingNightscoutDelete(
                            JournalPendingDeleteEntity(entryId = entry.id, nsRemoteId = oldRemoteId, deletedAt = now)
                        )
                    }
                }
            }
        }

        if (sendEnabled) {
            recordSendStatus(uploadFailureCode, deletes?.failureCode, acceptedDocument)
        }

        val receive = if (receiveEnabled) {
            receiveRemoteTreatments(baseUrl, rawSecret, useV3)
        } else {
            ReceiveOutcome(ok = true)
        }
        settleTombstones(dao, deletes, receiveEnabled, receive.readBody)
        // A refused delete is deliberately not folded in here: returning false backs off the
        // whole treatment path, and a token that may not delete would then hold every new entry
        // back too. A delete nobody answered is folded in, so the native backoff tries again
        // once the server may be reachable rather than waiting for the next journal change.
        return uploadOk && receive.ok && deletes?.unanswered != true
    }

    /** A delete the server confirmed this pass, and the document id it was sent for. */
    private class ConfirmedDelete(val tombstone: JournalPendingDeleteEntity, val documentId: String)

    /** What one pass made of the queued deletes. */
    private class DeletePass {
        val confirmed = ArrayList<ConfirmedDelete>()
        var acceptedDocument = false
        var failureCode: Int? = null
        /** Nightscout did not answer; the rest of the queue waits for the uploader's backoff. */
        var unanswered = false
    }

    private suspend fun sendPendingDeletes(
        dao: JournalDao,
        baseUrl: String,
        rawSecret: String,
        secretHashed: String?,
        useV3: Boolean
    ): DeletePass {
        val pass = DeletePass()
        // One v1 read per pass names every document known only by an undated identifier. On v3
        // too: a v3 read leaves the _id out, and a v3 delete by that identifier would take
        // whichever document carries it, this install's own included.
        var recentTreatments: JSONArray? = null
        var ownRemoteIds: Set<String>? = null
        for (tomb in dao.getPendingNightscoutDeletes()) {
            // A Nightscout treatment can be several journal rows (a meal bolus is carbs and
            // insulin). Deleting the document for one of them would take the others off the
            // server too, so the delete waits for the last of them; until then the tombstone
            // only keeps the deleted part from being received again.
            if (dao.countOtherEntriesWithNightscoutRemoteId(tomb.nsRemoteId, tomb.entryId) > 0) continue

            var documentId = tomb.nsRemoteId
            if (isUndatedOwnIdentifier(documentId)) {
                val read = recentTreatments ?: fetchTreatmentsWithIds(baseUrl, rawSecret, useV3)
                if (read == null) {
                    Log.e(LOG_ID, "tombstone entryId=${tomb.entryId}: treatments could not be read to name it; waiting")
                    pass.failureCode = NightPost.ERROR_NO_RESPONSE
                    pass.unanswered = true
                    break
                }
                recentTreatments = read
                // The documents this install still has rows for carry the same identifiers:
                // an entry whose old copy is being removed, or this install's own entry that
                // happens to share a row id with another install's.
                val ownIds = ownRemoteIds
                    ?: dao.getOwnUploadedNightscoutRemoteIds().mapTo(HashSet()) { it.trim() }
                ownRemoteIds = ownIds
                val found = soleDocumentIdForIdentifier(read, documentId, excludeRemoteIds = ownIds)
                if (found == null) {
                    // None served, so nothing read back can return it; or several, and deleting
                    // a guess could take someone else's treatment.
                    Log.i(LOG_ID, "tombstone entryId=${tomb.entryId} remoteId=$documentId names no single document; dropping it")
                    dao.clearPendingNightscoutDelete(tomb.entryId)
                    continue
                }
                documentId = found
            }

            val code = NightPost.deleteUrlCode(tombstoneDeleteUrl(baseUrl, documentId, useV3), secretHashed)
            val answered = answeredByNightscout(code, NightPost.getLastPrimaryResponseBody())
            val attempts = tomb.attempts + 1
            when (tombstoneAction(code, tomb.attempts, answered)) {
                TombstoneAction.CLEAR -> {
                    pass.confirmed += ConfirmedDelete(tomb, documentId)
                    // A 404 also clears the tombstone, but it is not proof that the
                    // server took anything from us, so it must not move "last sent".
                    if (isDeleteAccepted(code)) pass.acceptedDocument = true
                }
                TombstoneAction.WAIT -> {
                    // The rest of the queue would only wait out the same timeouts.
                    Log.e(
                        LOG_ID,
                        "tombstone delete for entryId=${tomb.entryId} remoteId=$documentId " +
                            "not answered by Nightscout (code=$code); waiting"
                    )
                    pass.failureCode = code
                    pass.unanswered = true
                    break
                }
                TombstoneAction.RETRY -> {
                    Log.e(
                        LOG_ID,
                        "tombstone delete failed for entryId=${tomb.entryId} " +
                            "remoteId=$documentId code=$code attempt=$attempts"
                    )
                    dao.recordFailedNightscoutDelete(tomb.entryId, attempts, System.currentTimeMillis())
                    pass.failureCode = code
                }
                TombstoneAction.GIVE_UP -> {
                    Log.e(
                        LOG_ID,
                        "giving up on tombstone entryId=${tomb.entryId} remoteId=$documentId " +
                            "after $attempts refused deletes (last code=$code); dropping it"
                    )
                    dao.clearPendingNightscoutDelete(tomb.entryId)
                    pass.failureCode = code
                }
            }
        }
        return pass
    }

    /**
     * Lets go of tombstones the server no longer serves (see [settlement]). With sending on, only
     * the deletes confirmed this pass are looked at: an unsent tombstone still has a document to
     * delete, whether or not this read reached it. With sending off, every tombstone is local
     * only and goes as soon as a read no longer serves its document.
     */
    private suspend fun settleTombstones(
        dao: JournalDao,
        deletes: DeletePass?,
        readsBack: Boolean,
        readBody: String?
    ) {
        val candidates = deletes?.confirmed?.map { it.tombstone to it.documentId }
            ?: dao.getPendingNightscoutDeletes().map { it to it.nsRemoteId }
        if (candidates.isEmpty()) return
        // A v3 read serves an own v1 upload without the _id its tombstone holds; were it not
        // looked for under its identifier as well, it would read as gone and its tombstone go.
        val v3Names = v3NamesOfV1Documents(candidates.map { it.first })
        val served = readBody?.let { body ->
            servedRemoteIds(body, candidates.mapTo(HashSet()) { it.second }, v3Names)
        }
        for ((tomb, documentId) in candidates) {
            val action = settlement(
                deleteConfirmed = deletes != null,
                readsBack = readsBack,
                served = served?.contains(documentId)
            )
            when (action) {
                Settlement.DROP -> dao.clearPendingNightscoutDelete(tomb.entryId)
                Settlement.KEEP -> Unit
                Settlement.NOT_TAKEN -> {
                    // Deleted by the server's own account, yet served again: the delete did not
                    // take. Counted like a refusal, so a document that will not go is let go of.
                    val attempts = tomb.attempts + 1
                    Log.e(
                        LOG_ID,
                        "tombstone entryId=${tomb.entryId} remoteId=$documentId still served after its delete " +
                            "(attempt=$attempts)"
                    )
                    if (refusedDeleteAction(tomb.attempts) == TombstoneAction.GIVE_UP) {
                        dao.clearPendingNightscoutDelete(tomb.entryId)
                    } else {
                        dao.recordFailedNightscoutDelete(tomb.entryId, attempts, System.currentTimeMillis())
                    }
                }
            }
        }
    }

    /**
     * Publishes the outcome of one send cycle so the settings screen can show a treatment
     * path that has been failing while the native entries uploader kept reporting HTTP 200.
     */
    private fun recordSendStatus(
        uploadFailureCode: Int?,
        deleteFailureCode: Int?,
        acceptedDocument: Boolean
    ) {
        val now = System.currentTimeMillis()
        when {
            uploadFailureCode != null ->
                JournalSyncStatus.recordFailure(now, uploadFailureCode, JournalSyncFailure.UPLOAD)
            deleteFailureCode != null ->
                JournalSyncStatus.recordFailure(now, deleteFailureCode, JournalSyncFailure.DELETE)
            else -> JournalSyncStatus.recordSuccess(now, acceptedDocument)
        }
    }

    private fun isSendableType(entryType: String): Boolean {
        val type = JournalEntryType.fromStorage(entryType)
        return type == JournalEntryType.INSULIN ||
            type == JournalEntryType.CARBS ||
            type == JournalEntryType.FINGERSTICK ||
            type == JournalEntryType.ACTIVITY ||
            type == JournalEntryType.NOTE
    }

    internal fun shouldUploadTreatment(
        entryType: String,
        preset: JournalInsulinPresetEntity?,
        sendLongInsulin: Boolean
    ): Boolean {
        if (sendLongInsulin || JournalEntryType.fromStorage(entryType) != JournalEntryType.INSULIN) {
            return true
        }
        // Basal presets deliberately do not count toward IOB. Unknown/deleted
        // presets remain sendable so this preference never silently drops an
        // insulin entry whose classification cannot be recovered.
        return preset?.countsTowardIob != false
    }

    internal fun isExternalMirrorSource(source: String): Boolean {
        return source == JournalEntrySource.AAPS.storageValue ||
            source == JournalEntrySource.NIGHTSCOUT.storageValue ||
            source == JournalEntrySource.API.storageValue ||
            source == JournalEntrySource.CLONE.storageValue ||
            source == JournalEntrySource.CLONE_LOCAL_ICE.storageValue ||
            source == JournalEntrySource.CLONE_TURN.storageValue
    }

    private fun treatmentPostUrl(baseUrl: String, useV3: Boolean): String =
        baseUrl + if (useV3) "/api/v3/treatments" else "/api/v1/treatments"

    /**
     * Read URL for treatments. The v1 form is a bare array under a .json suffix counted by
     * `count`; v3 has neither, and limits with `limit` while sorting newest first, so the two
     * cannot share one string. Sending the v1 form to a v3 setup answers 401, which reads as a
     * server permission problem rather than a client using the wrong API.
     */
    internal fun treatmentFetchUrl(baseUrl: String, useV3: Boolean, count: Int = TREATMENT_FETCH_COUNT): String =
        if (useV3) {
            "$baseUrl/api/v3/treatments?sort%24desc=date&limit=$count&fields=_all"
        } else {
            "$baseUrl/api/v1/treatments.json?count=$count"
        }

    /**
     * v1 answers with the document array itself, v3 wraps it in {status, result}. Everything
     * downstream parses an array, so the envelope is peeled here rather than in the importer.
     * A body that is already an array is passed through, so a v3 host that answers the v1 shape
     * still imports.
     */
    internal fun treatmentsArrayBody(body: String, useV3: Boolean): String {
        val trimmed = body.trim()
        if (!useV3 || trimmed.startsWith("[")) return trimmed
        if (!trimmed.startsWith("{")) return trimmed
        return runCatching { JSONObject(trimmed).optJSONArray("result")?.toString() }
            .getOrNull()
            ?: "[]"
    }

    /**
     * API v3 deletes are soft by default. That is invisible to v3 searches, but Nightscout's
     * legacy v1 treatment reads can still return the invalid document and show the stale copy
     * in the web UI. These deletes represent an explicit local deletion or a copy superseded by
     * a successful write, so remove the v3 document permanently after the safety checks above.
     */
    internal fun treatmentDeleteUrl(baseUrl: String, remoteId: String, useV3: Boolean): String =
        baseUrl + if (useV3) {
            "/api/v3/treatments/$remoteId?permanent=true"
        } else {
            "/api/v1/treatments/$remoteId"
        }

    /**
     * Where a queued delete goes. v3 finds a document by its identifier or its legacy _id alike.
     * v1's document route takes an ObjectId only: given a client identifier (AAPS's, any v3
     * uploader's, this app's dated one) it matches nothing, or fails, while the document stays
     * and the next read brings it back. Those are deleted by query on the identifier instead.
     * The undated own identifier is never sent as it is, on either version (see
     * [isUndatedOwnIdentifier]): it is resolved to its _id before it gets here.
     */
    internal fun tombstoneDeleteUrl(baseUrl: String, documentId: String, useV3: Boolean): String {
        if (useV3 || isObjectId(documentId) || isUndatedOwnIdentifier(documentId)) {
            return treatmentDeleteUrl(baseUrl, documentId, useV3)
        }
        return "$baseUrl/api/v1/treatments?" +
            urlEncoded("find[identifier]") + "=" + urlEncoded(documentId) + "&" +
            urlEncoded("find[created_at][\$gte]") + "=" + V1_QUERY_ALL_TIME
    }

    private fun urlEncoded(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    private fun uploadViaNightPost(
        baseUrl: String,
        json: JSONObject,
        secretHashed: String?,
        useV3: Boolean,
        remoteId: String,
        write: TreatmentWrite = TreatmentWrite.CREATE
    ): UploadResult {
        val payload = json.toString().toByteArray(Charsets.UTF_8)
        val code = if (useV3 && write == TreatmentWrite.UPDATE) {
            NightPost.uploadPatch(treatmentWriteUrl(baseUrl, remoteId, write), payload, secretHashed)
        } else {
            NightPost.upload(treatmentWriteUrl(baseUrl, remoteId, write), payload, secretHashed, false)
        }
        val message = if (isUploadOk(code, useV3)) "" else serverMessage(NightPost.getLastPrimaryResponseBody())
        return UploadResult(code = code, remoteId = remoteId, message = message)
    }

    internal fun treatmentWriteUrl(baseUrl: String, remoteId: String, write: TreatmentWrite): String =
        if (write == TreatmentWrite.UPDATE) "$baseUrl/api/v3/treatments/$remoteId" else treatmentPostUrl(baseUrl, true)

    private fun postV1Treatment(
        baseUrl: String,
        secret: String,
        json: JSONObject,
        localIdentifier: String,
        timestamp: Long,
        previousRemoteId: String?
    ): UploadResult {
        val normalized = NightscoutFollowerRegistry.normalizeUrl(baseUrl)
        if (normalized.isBlank()) return UploadResult(ERROR_INVALID_URL)
        val endpoint = "$normalized/api/v1/treatments"
        val postData = json.toString().toByteArray(Charsets.UTF_8)
        Log.i(LOG_ID, "postV1Treatment($endpoint,#${postData.size})")
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Content-Length", postData.size.toString())
            setRequestProperty("User-Agent", "JugglucoNG Nightscout journal sync")
            NightscoutFollowerRegistry.applyAuth(this, secret)
        }
        try {
            connection.outputStream.use { output ->
                output.write(postData)
                output.flush()
            }
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()
                ?.use { it.readText() }
                .orEmpty()
            val responseId = extractCreatedRemoteId(body)
                ?: if (code in 200..299) findRemoteIdByIdentifier(baseUrl, secret, localIdentifier, timestamp, previousRemoteId) else null
            if (code !in 200..299) {
                Log.e(LOG_ID, "postV1Treatment ResponseCode=$code\n${body.take(512)}")
                return UploadResult(code = code, message = serverMessage(body))
            } else if (responseId == null) {
                Log.w(LOG_ID, "postV1Treatment success without returned Nightscout _id; using local identifier")
            } else {
                Log.i(LOG_ID, "postV1Treatment ResponseCode=$code remoteId=$responseId")
            }
            return UploadResult(code = code, remoteId = responseId ?: localIdentifier)
        } catch (th: Throwable) {
            Log.e(LOG_ID, "postV1Treatment failure:\n${Log.stackline(th)}")
            return UploadResult(-1)
        } finally {
            connection.disconnect()
        }
    }

    private fun extractCreatedRemoteId(body: String): String? {
        val trimmed = body.trim()
        if (trimmed.isBlank()) return null
        return runCatching {
            when {
                trimmed.startsWith("[") -> {
                    val array = JSONArray(trimmed)
                    for (index in 0 until array.length()) {
                        array.optJSONObject(index)?.optNightscoutDocumentId()?.let { return@runCatching it }
                    }
                    null
                }
                trimmed.startsWith("{") -> JSONObject(trimmed).optNightscoutDocumentId()
                else -> null
            }
        }.getOrNull()
    }

    /**
     * The _id of the one document [treatments] carries under [identifier], leaving out
     * [excludeRemoteIds]; null when there is none, or more than one and so no telling which.
     */
    internal fun soleDocumentIdForIdentifier(
        treatments: JSONArray,
        identifier: String,
        excludeRemoteIds: Set<String>
    ): String? {
        var match: String? = null
        for (index in 0 until treatments.length()) {
            val treatment = treatments.optJSONObject(index) ?: continue
            if (treatment.optString("identifier") != identifier) continue
            val remoteId = treatment.optNightscoutDocumentId() ?: continue
            if (remoteId in excludeRemoteIds) continue
            if (match != null && match != remoteId) return null
            match = remoteId
        }
        return match
    }

    /**
     * The treatments as v1 serves them, each with its _id; null when they could not be had. A v3
     * setup reads them the same way, with its token: v3 leaves the _id out.
     */
    private fun fetchTreatmentsWithIds(baseUrl: String, secret: String, useV3: Boolean): JSONArray? =
        runCatching {
            fetchTreatmentsRead(baseUrl, secret, useV3 = false, tokenAuth = useV3)?.let(::JSONArray)
        }.getOrNull()

    private fun findRemoteIdByIdentifier(
        baseUrl: String,
        secret: String,
        localIdentifier: String,
        timestamp: Long?,
        excludeRemoteId: String? = null
    ): String? =
        runCatching {
            // Reached only from the v1 branch: postV1Treatment runs in the else of `if (useV3)`.
            val array = JSONArray(fetchTreatmentsJson(baseUrl, secret, useV3 = false))
            for (index in 0 until array.length()) {
                val treatment = array.optJSONObject(index) ?: continue
                if (!treatment.optString("identifier").equals(localIdentifier, ignoreCase = false)) continue
                val remoteId = treatment.optNightscoutDocumentId() ?: continue
                // A re-upload shares its identifier with the copy it replaces; that one is
                // not the document just written.
                if (remoteId == excludeRemoteId) continue
                val date = treatment.optLong("date", 0L)
                if (timestamp == null || date == 0L || kotlin.math.abs(date - timestamp) <= 60_000L) {
                    return@runCatching remoteId
                }
            }
            null
        }.getOrNull()

    private fun JSONObject.optNightscoutDocumentId(): String? =
        optString("_id").trim().takeIf { it.isNotBlank() }
            ?: optString("id").trim().takeIf { it.isNotBlank() }

    /**
     * How a receive went. [readBody] is the treatment list the server answered with, null when
     * nothing was read (deferred, failed, or no such endpoint).
     */
    private class ReceiveOutcome(val ok: Boolean, val readBody: String? = null)

    private fun receiveRemoteTreatments(baseUrl: String, secret: String, useV3: Boolean): ReceiveOutcome {
        val floorKey = "${if (useV3) "v3" else "v1"} ${NightscoutFollowerRegistry.normalizeUrl(baseUrl)}"
        val waitMillis = receiveFloor.waitMillis(floorKey, System.currentTimeMillis())
        if (waitMillis > 0L) {
            bookDeferredReceive(waitMillis)
            return ReceiveOutcome(ok = true)
        }
        var readBody: String? = null
        val ok = runCatching {
            val read = fetchTreatmentsRead(baseUrl, secret, useV3)
            receiveFloor.recordRead(floorKey, System.currentTimeMillis())
            readBody = read
            val body = read ?: return@runCatching true
            if (body.isBlank() || body == "[]") return@runCatching true
            val sensorId = NightscoutFollowerRegistry.deriveSensorId(baseUrl)
            val imported = NightscoutJournalFollowerImporter.importTreatments(sensorId, body)
            if (imported > 0) {
                UiRefreshBus.requestDataRefresh()
                Log.i(LOG_ID, "received $imported Nightscout treatment journal items")
            }
            receiveErrorLog.reset()
            true
        }.onFailure { error ->
            // The uploader retries on its own cadence, so an unreachable or refusing server
            // used to write the same line every few seconds and bury the rest of the trace.
            val message = "receive treatments failed: ${error.message}"
            val repeats = receiveErrorLog.suppressedSince(message, System.currentTimeMillis())
            if (repeats >= 0) {
                Log.e(LOG_ID, if (repeats == 0) message else "$message (repeated ${repeats + 1}x)")
            }
        }.getOrDefault(false)
        return ReceiveOutcome(ok, readBody)
    }

    private fun fetchTreatmentsJson(baseUrl: String, secret: String, useV3: Boolean): String =
        fetchTreatmentsRead(baseUrl, secret, useV3) ?: "[]"

    /**
     * The treatment list the server answered with; null when there is nothing to read from
     * (no URL, or no such endpoint). Throws when the read failed.
     *
     * @param tokenAuth authenticate with the v3 token, also for a v1 read made under a v3 setup
     */
    private fun fetchTreatmentsRead(
        baseUrl: String,
        secret: String,
        useV3: Boolean,
        tokenAuth: Boolean = useV3
    ): String? {
        val normalized = NightscoutFollowerRegistry.normalizeUrl(baseUrl)
        if (normalized.isBlank()) return null
        val endpoint = treatmentFetchUrl(normalized, useV3)
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "JugglucoNG Nightscout journal sync")
            // v3 reads want the same bearer token the v3 upload path already obtains and
            // caches; the configured secret is an access token there, and hashing it into an
            // api-secret header is what the 401 was. So does a v1 read made under a v3 setup.
            val v3Auth = if (tokenAuth) NightPost.getV3AuthorizationHeader() else ""
            if (v3Auth.isNotEmpty()) {
                setRequestProperty("Authorization", v3Auth)
            } else {
                NightscoutFollowerRegistry.applyAuth(this, secret)
            }
        }
        try {
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()
                ?.use { it.readText() }
                .orEmpty()
            if (code == HttpURLConnection.HTTP_NOT_FOUND) return null
            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code ${endpointPath(endpoint)}: ${serverMessage(body)}")
            }
            return treatmentsArrayBody(body, useV3)
        } finally {
            connection.disconnect()
        }
    }

    private fun hashedSecret(raw: String?): String? {
        val s = raw?.takeIf { it.isNotEmpty() } ?: return null
        val digest = MessageDigest.getInstance("SHA-1").digest(s.toByteArray(Charsets.UTF_8))
        val hex = StringBuilder(digest.size * 2)
        for (b in digest) {
            hex.append(String.format(Locale.US, "%02x", b.toInt() and 0xff))
        }
        return hex.toString()
    }
}
