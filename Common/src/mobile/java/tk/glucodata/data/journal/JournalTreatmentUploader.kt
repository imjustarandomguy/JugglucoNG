package tk.glucodata.data.journal

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * Sends Kotlin Journal entries to Nightscout as treatments. Replaces the legacy C++
 * uploadtreatments() path that pulled
 * from Numdata. Invoked from the native upload loop via NightPost.
 *
 * Sync state is tracked per-row on JournalEntryEntity (nsUploadedAt, nsRemoteId);
 * deletes are queued in journal_pending_deletes so they survive process death.
 *
 * Rows mirrored from elsewhere are not sent, with one exception: the user's edit of a treatment
 * received from Nightscout goes back to the document it came from (see sendReceivedEdit).
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
     * Failed attempts a delete, or an edit of a received treatment, gets before it is no longer
     * sent on its own. A document Nightscout will never let us delete (a token without
     * `api:treatments:delete` answers 403 forever), or one it fails on every time, must not be
     * tried for the rest of the install's life. What the user did stays here: the row stays
     * deleted (its tombstone is kept) or edited.
     */
    internal const val MAX_DELETE_ATTEMPTS = 20

    /** The wait after an operation's first failure, doubled after each further one up to the max. */
    private const val RETRY_FIRST_MILLIS = 60_000L
    private const val RETRY_MAX_MILLIS = 6L * 60 * 60_000L

    /**
     * Lower date bound for a v1 delete by query. Without a date in the query Nightscout only
     * looks at the last four days, so an older treatment would answer "nothing deleted" and stay.
     */
    private const val V1_QUERY_ALL_TIME = "2000-01-01"
    private const val HTTP_TOO_MANY_REQUESTS = 429

    internal enum class TombstoneAction {
        CLEAR,
        /** Failed and counted; sent again once its own wait is up ([isRetryDue]). */
        RETRY,
        /** The server is out of reach or busy: not counted, and the rest of the queue waits too. */
        WAIT,
        /** Failed too often: no longer sent, the tombstone kept so the document does not come back. */
        KEEP_LOCAL
    }

    /** How a failed request for one delete or edit is taken. */
    internal enum class OperationFailure {
        /**
         * No answer, a busy or unreachable server (408, 429, 502-504), credentials it does not
         * take (401), or a page not Nightscout's.
         */
        WAIT,
        /** The server failed on this request (another 5xx). */
        RETRY,
        /** Nightscout refused this request. */
        REFUSED
    }

    /**
     * What a failed request for one operation says. Only what is about the whole server makes
     * the rest of the queue wait; a server error on one document is that document's, so it is
     * counted and retried on its own, and the others go on.
     */
    internal fun operationFailure(code: Int, answeredByNightscout: Boolean): OperationFailure = when {
        code < 0 -> OperationFailure.WAIT
        code == HttpURLConnection.HTTP_UNAUTHORIZED ||
            code == HttpURLConnection.HTTP_CLIENT_TIMEOUT ||
            code == HTTP_TOO_MANY_REQUESTS ||
            code in HttpURLConnection.HTTP_BAD_GATEWAY..HttpURLConnection.HTTP_GATEWAY_TIMEOUT -> OperationFailure.WAIT
        code >= HttpURLConnection.HTTP_INTERNAL_ERROR -> OperationFailure.RETRY
        !answeredByNightscout -> OperationFailure.WAIT
        else -> OperationFailure.REFUSED
    }

    /** How long an operation that failed [attempts] times waits before it is tried again. */
    internal fun retryDelayMillis(attempts: Int): Long =
        if (attempts <= 0) 0L else minOf(RETRY_FIRST_MILLIS shl minOf(attempts - 1, 20), RETRY_MAX_MILLIS)

    /** Whether an operation that failed [attempts] times, last at [lastAttemptAt], may go again. */
    internal fun isRetryDue(attempts: Int, lastAttemptAt: Long, nowMillis: Long): Boolean =
        // A clock that went back must not hold it for longer than one wait.
        nowMillis >= lastAttemptAt + retryDelayMillis(attempts) || nowMillis < lastAttemptAt

    /**
     * The failed attempts of each edit of a received treatment, while it keeps failing. Kept in
     * memory: a restart only tries an edit once more, and an edit made since starts afresh.
     */
    internal class EditRetries {
        private class State(val updatedAt: Long, val attempts: Int, val lastAttemptAt: Long)

        private val states = HashMap<Long, State>()

        private fun current(entryId: Long, updatedAt: Long): State? =
            states[entryId]?.takeIf { it.updatedAt == updatedAt }

        @Synchronized
        fun isDue(entryId: Long, updatedAt: Long, nowMillis: Long): Boolean {
            val state = current(entryId, updatedAt) ?: return true
            return isRetryDue(state.attempts, state.lastAttemptAt, nowMillis)
        }

        /** When the edit may go again; null when it has not failed. */
        @Synchronized
        fun dueAt(entryId: Long, updatedAt: Long): Long? =
            current(entryId, updatedAt)?.let { it.lastAttemptAt + retryDelayMillis(it.attempts) }

        /** @return the failed attempts so far, this one included */
        @Synchronized
        fun recordFailure(entryId: Long, updatedAt: Long, nowMillis: Long): Int {
            val attempts = (current(entryId, updatedAt)?.attempts ?: 0) + 1
            states[entryId] = State(updatedAt, attempts, nowMillis)
            return attempts
        }

        @Synchronized
        fun clear(entryId: Long) {
            states.remove(entryId)
        }
    }

    private val editRetries = EditRetries()

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
     * A refusal or a server error counts toward [MAX_DELETE_ATTEMPTS]. No answer at all (a server
     * reachable only at home, seen from elsewhere), a busy or unreachable server, or a page that
     * is not Nightscout's says nothing about the document and does not count. Past the cap the
     * delete is no longer sent, but the tombstone stays: dropped, the next read brought the
     * deleted treatment back.
     */
    internal fun tombstoneAction(
        code: Int,
        attemptsSoFar: Int,
        answeredByNightscout: Boolean = true
    ): TombstoneAction = when {
        answeredByNightscout && (
            code == HttpURLConnection.HTTP_OK ||
                code == HttpURLConnection.HTTP_NO_CONTENT ||
                code == HttpURLConnection.HTTP_NOT_FOUND ||
                code == HttpURLConnection.HTTP_GONE
            ) -> TombstoneAction.CLEAR
        operationFailure(code, answeredByNightscout) == OperationFailure.WAIT -> TombstoneAction.WAIT
        else -> failedDeleteAction(attemptsSoFar)
    }

    internal fun failedDeleteAction(attemptsSoFar: Int): TombstoneAction =
        if (attemptsSoFar + 1 >= MAX_DELETE_ATTEMPTS) TombstoneAction.KEEP_LOCAL else TombstoneAction.RETRY

    /**
     * What a failed read of the document to delete does to its tombstone: a refusal counts as a
     * refused delete would, and anything else waits. A read never clears one: an answer that
     * could not be read as a document says nothing of whether it is gone.
     */
    internal fun tombstoneReadAction(code: Int, attemptsSoFar: Int, answeredByNightscout: Boolean): TombstoneAction =
        tombstoneAction(code, attemptsSoFar, answeredByNightscout)
            .takeIf { it != TombstoneAction.CLEAR } ?: TombstoneAction.WAIT

    /** Whether the delete of [tombstone] is still to be sent (see [JournalPendingDeleteEntity]). */
    internal fun sendsDelete(tombstone: JournalPendingDeleteEntity): Boolean = tombstone.attempts < MAX_DELETE_ATTEMPTS

    /** Whether [remoteId] is an identifier this app gave a document of its own, dated or not. */
    internal fun isOwnIdentifier(remoteId: String): Boolean = remoteId.startsWith(ID_PREFIX, ignoreCase = true)

    internal enum class DeleteCheck {
        DELETE,
        /** Not on the server any more: nothing to delete. */
        GONE,
        /** A loop system's document, or several under one id: deleted here only. */
        KEEP_LOCAL
    }

    /** What the read of a document about to be deleted allows; null when the read failed. */
    internal fun deleteCheck(read: ReceivedDocumentRead): DeleteCheck? = when (read) {
        is ReceivedDocumentRead.Found ->
            if (JournalTreatmentTransfer.isLoopSystemDocument(read.document)) DeleteCheck.KEEP_LOCAL else DeleteCheck.DELETE
        ReceivedDocumentRead.Gone -> DeleteCheck.GONE
        ReceivedDocumentRead.Ambiguous -> DeleteCheck.KEEP_LOCAL
        is ReceivedDocumentRead.Failed -> null
    }

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

        /**
         * A read that was answered starts the interval, a refusal included; one that got no
         * answer is the native backoff's.
         */
        fun recordRead(key: String, nowMillis: Long) {
            lastKey = key
            lastReadAt = nowMillis
        }
    }

    private val receiveFloor = ReceiveFloor(RECEIVE_MIN_INTERVAL_MILLIS)

    /** When the booked treatment wake is due (uptime); Long.MAX_VALUE while none is. */
    private val bookedWakeAt = AtomicLong(Long.MAX_VALUE)

    /**
     * Wakes the treatment pass after [delayMillis]: a receive held back by the interval, or an
     * operation waiting out its retry. A pass that went through says nothing more to the native
     * uploader, which then has no reason to come back for them. One wake at a time, the
     * earliest; the pass it brings books the next.
     */
    private fun bookTreatmentWake(delayMillis: Long) {
        val at = SystemClock.uptimeMillis() + delayMillis.coerceAtLeast(0L)
        while (true) {
            val booked = bookedWakeAt.get()
            if (booked <= at) return
            if (bookedWakeAt.compareAndSet(booked, at)) break
        }
        Handler(Looper.getMainLooper()).postAtTime({
            bookedWakeAt.compareAndSet(at, Long.MAX_VALUE)
            runCatching { Natives.waketreatments() }
                .onFailure { Log.e(LOG_ID, "treatment wake failed: ${Log.stackline(it)}") }
        }, at)
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
        // The earliest time an operation that failed on its own may go again (wall clock).
        var retryAt: Long? = deletes?.retryAt

        val sinceMillis = System.currentTimeMillis() - LOOKBACK_MILLIS
        if (sendEnabled) {
            val pending = dao.getEntriesNeedingNightscoutUpload(sinceMillis)
            val receivedPrefix = NightscoutJournalFollowerImporter.sourcePrefix(
                NightscoutFollowerRegistry.deriveSensorId(baseUrl)
            )
            for (entry in pending) {
                if (!isSendableType(entry.entryType)) continue
                if (hasPendingNightscoutEdit(entry.source, entry.updatedAt, entry.nsUploadedAt)) {
                    // The user edited a treatment received from Nightscout: the edit goes back to
                    // that document. Not subject to "send long insulin", which keeps this app from
                    // putting long-acting doses on the server; this one is there already.
                    if (isNightscoutEditKeptLocal(entry.source, entry.nsUploadedAt)) continue
                    if (receivedEditHold(entry, receivedPrefix) != null) continue
                    if (!editRetries.isDue(entry.id, entry.updatedAt, System.currentTimeMillis())) {
                        editRetries.dueAt(entry.id, entry.updatedAt)?.let { retryAt = minOf(retryAt ?: it, it) }
                        continue
                    }
                    val result = sendReceivedEdit(entry, baseUrl, rawSecret, secretHashed, useV3)
                    val now = System.currentTimeMillis()
                    if (result.action == ReceivedEditAction.WAIT) {
                        Log.e(
                            LOG_ID,
                            "edit of received entry id=${entry.id} remoteId=${entry.nsRemoteId} not answered " +
                                "code=${failureText(result.code, result.message)}; kept pending"
                        )
                        uploadFailureCode = result.code
                        uploadOk = false
                        break
                    }
                    var keepLocal = result.action == ReceivedEditAction.KEEP_LOCAL
                    if (result.action == ReceivedEditAction.RETRY) {
                        // The server failed on this edit: it waits on its own, the queue goes on.
                        val attempts = editRetries.recordFailure(entry.id, entry.updatedAt, now)
                        uploadFailureCode = result.code
                        if (attempts < MAX_DELETE_ATTEMPTS) {
                            Log.e(
                                LOG_ID,
                                "edit of received entry id=${entry.id} remoteId=${entry.nsRemoteId} failed " +
                                    "code=${failureText(result.code, result.message)} attempt=$attempts; kept pending"
                            )
                            val dueAt = now + retryDelayMillis(attempts)
                            retryAt = minOf(retryAt ?: dueAt, dueAt)
                            continue
                        }
                        Log.e(
                            LOG_ID,
                            "edit of received entry id=${entry.id} remoteId=${entry.nsRemoteId} failed $attempts times " +
                                "(last code=${failureText(result.code, result.message)}); kept here, not sent again"
                        )
                        keepLocal = true
                    }
                    editRetries.clear(entry.id)
                    if (keepLocal) {
                        // Refused, failed too often, or not the server's to change: the rest of the
                        // queue goes on, and the edit stays here, unconfirmed, until the row is
                        // edited again.
                        if (result.action == ReceivedEditAction.KEEP_LOCAL && result.code != 0) {
                            Log.e(
                                LOG_ID,
                                "edit of received entry id=${entry.id} remoteId=${entry.nsRemoteId} refused " +
                                    "code=${failureText(result.code, result.message)}; kept here, not sent again"
                            )
                            uploadFailureCode = result.code
                        }
                        dao.settleReceivedNightscoutEdit(entry.id, entry.updatedAt, NIGHTSCOUT_EDIT_KEPT_LOCAL)
                        continue
                    }
                    sendBackoff.reset()
                    if (result.wrote) acceptedDocument = true
                    dao.settleReceivedNightscoutEdit(
                        id = entry.id,
                        updatedAt = entry.updatedAt,
                        nsUploadedAt = if (result.action == ReceivedEditAction.CONFIRM) maxOf(now, entry.updatedAt) else null
                    )
                    continue
                }
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
            ReceiveOutcome(ReceiveResult.DONE)
        }
        settleTombstones(dao, deletes, receiveEnabled, receive.readBody)
        retryAt?.let { bookTreatmentWake(it - System.currentTimeMillis()) }
        // A delete or an edit that failed on its own is deliberately not folded in here:
        // returning false backs off the whole treatment path, and a token that may not delete
        // would then hold every new entry back too; it has its own wait, booked above. A delete
        // nobody answered is folded in, so the native backoff tries again once the server may be
        // reachable rather than waiting for the next journal change. The receive follows the same
        // rule (see treatmentPassOk).
        return treatmentPassOk(
            sendsOk = uploadOk,
            deletesUnanswered = deletes?.unanswered == true,
            receive = receive.result,
            wroteThisPass = acceptedDocument
        )
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
        /** The earliest time a delete that failed on its own may be sent again. */
        var retryAt: Long? = null

        fun retryAt(at: Long) {
            retryAt = minOf(retryAt ?: at, at)
        }
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
            if (!sendsDelete(tomb)) continue
            // A Nightscout treatment can be several journal rows (a meal bolus is carbs and
            // insulin). Deleting the document for one of them would take the others off the
            // server too, so the delete waits for the last of them; until then the tombstone
            // only keeps the deleted part from being received again.
            if (dao.countOtherEntriesWithNightscoutRemoteId(tomb.nsRemoteId, tomb.entryId) > 0) continue
            // One that failed waits on its own; the rest of the queue does not.
            if (!isRetryDue(tomb.attempts, tomb.lastAttemptAt, System.currentTimeMillis())) {
                pass.retryAt(tomb.lastAttemptAt + retryDelayMillis(tomb.attempts))
                continue
            }

            var documentId = tomb.nsRemoteId
            val ownDocument = isOwnIdentifier(documentId)
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

            // Read before deleting: a loop system's document is never deleted from here, and one
            // already gone needs no delete. This app's own documents need no such look.
            val failedRead = if (ownDocument) null else {
                val read = httpRequest("GET", receivedDocumentUrl(baseUrl, documentId, useV3), rawSecret, tokenAuth = useV3)
                when (deleteCheck(receivedDocumentRead(read.code, read.body))) {
                    DeleteCheck.KEEP_LOCAL -> {
                        Log.i(
                            LOG_ID,
                            "tombstone entryId=${tomb.entryId} remoteId=$documentId is a loop system's document " +
                                "or names several; deleted here only, never on the server"
                        )
                        dao.recordFailedNightscoutDelete(tomb.entryId, MAX_DELETE_ATTEMPTS, System.currentTimeMillis())
                        continue
                    }
                    DeleteCheck.GONE -> {
                        pass.confirmed += ConfirmedDelete(tomb, documentId)
                        continue
                    }
                    DeleteCheck.DELETE -> null
                    null -> read
                }
            }
            val code: Int
            val action: TombstoneAction
            if (failedRead != null) {
                code = failedRead.code
                action = tombstoneReadAction(code, tomb.attempts, answeredByNightscout(code, failedRead.body))
            } else {
                code = NightPost.deleteUrlCode(tombstoneDeleteUrl(baseUrl, documentId, useV3), secretHashed)
                action = tombstoneAction(code, tomb.attempts, answeredByNightscout(code, NightPost.getLastPrimaryResponseBody()))
            }
            val attempts = tomb.attempts + 1
            when (action) {
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
                    val now = System.currentTimeMillis()
                    dao.recordFailedNightscoutDelete(tomb.entryId, attempts, now)
                    pass.retryAt(now + retryDelayMillis(attempts))
                    pass.failureCode = code
                }
                TombstoneAction.KEEP_LOCAL -> {
                    Log.e(
                        LOG_ID,
                        "tombstone entryId=${tomb.entryId} remoteId=$documentId failed $attempts times " +
                            "(last code=$code); no longer sent, kept so the treatment stays deleted here"
                    )
                    dao.recordFailedNightscoutDelete(tomb.entryId, attempts, System.currentTimeMillis())
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
                    // take. Counted like a refusal, so a document that will not go is no longer
                    // sent; the tombstone stays and keeps it from coming back.
                    val attempts = tomb.attempts + 1
                    Log.e(
                        LOG_ID,
                        "tombstone entryId=${tomb.entryId} remoteId=$documentId still served after its delete " +
                            "(attempt=$attempts)"
                    )
                    dao.recordFailedNightscoutDelete(tomb.entryId, attempts, System.currentTimeMillis())
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

    // -- the user's edit of a treatment received from Nightscout ------------------------------
    //
    // Such a row stands for another app's document. Its edit is written back to that document,
    // never as a new one: a PATCH of the fields that changed on v3, the whole document with them
    // changed on v1 (whose PUT replaces what it stores). Until the server confirms it, the receive
    // leaves the row alone (see receivedCopyMayReplace in JournalRepository.kt).

    /** Why an edited received row cannot be sent, found without asking the server. */
    internal enum class ReceivedEditHold {
        /** No id the server knows the document by. */
        NO_DOCUMENT,
        /** Received from a server other than the one treatments go to. */
        OTHER_SERVER,
        /** Edited into another kind of treatment, which the document cannot become. */
        TYPE_CHANGED
    }

    /**
     * Why [entry]'s edit cannot be sent; null when it can. A held edit stays on the row, pending,
     * and the receive does not write over it.
     *
     * @param sourcePrefix how the rows received from the upload server are named
     *        ([NightscoutJournalFollowerImporter.sourcePrefix])
     */
    internal fun receivedEditHold(entry: JournalEntryEntity, sourcePrefix: String): ReceivedEditHold? {
        if (entry.nsRemoteId.isNullOrBlank()) return ReceivedEditHold.NO_DOCUMENT
        val sourceRecordId = entry.sourceRecordId ?: return ReceivedEditHold.OTHER_SERVER
        // A follower can read another server than the one sent to, and its document ids mean
        // nothing on this one.
        if (!sourceRecordId.startsWith("$sourcePrefix:")) return ReceivedEditHold.OTHER_SERVER
        // The row keeps the name it was received under, which ends in the kind it was.
        if (sourceRecordId.substringAfterLast(':') != entry.entryType) return ReceivedEditHold.TYPE_CHANGED
        return null
    }

    /** Where the document a received row stands for is read, to be changed. */
    internal fun receivedDocumentUrl(baseUrl: String, remoteId: String, useV3: Boolean): String {
        if (useV3) return "$baseUrl/api/v3/treatments/${pathSegment(remoteId)}"
        // v1 turns find[_id] into an ObjectId (and then needs no date). An identifier needs the
        // date, or only the last four days are looked at.
        val find = if (isObjectId(remoteId)) {
            urlEncoded("find[_id]") + "=" + urlEncoded(remoteId)
        } else {
            urlEncoded("find[identifier]") + "=" + urlEncoded(remoteId) + "&" +
                urlEncoded("find[created_at][\$gte]") + "=" + V1_QUERY_ALL_TIME
        }
        // Two, to tell one document from an identifier several carry.
        return "$baseUrl/api/v1/treatments.json?$find&count=2"
    }

    /** Where the edit goes: the document itself on v3; v1's PUT finds it by the _id in the body. */
    internal fun receivedEditWriteUrl(baseUrl: String, remoteId: String, useV3: Boolean): String =
        if (useV3) "$baseUrl/api/v3/treatments/${pathSegment(remoteId)}" else "$baseUrl/api/v1/treatments/"

    private fun pathSegment(value: String): String = urlEncoded(value).replace("+", "%20")

    /** A received document as read back to be changed. */
    internal sealed class ReceivedDocumentRead {
        class Found(val document: JSONObject) : ReceivedDocumentRead()
        /** Not on the server any more (or marked deleted there). */
        object Gone : ReceivedDocumentRead()
        /** Several documents carry the identifier; changing a guess could change another's. */
        object Ambiguous : ReceivedDocumentRead()
        class Failed(val code: Int) : ReceivedDocumentRead()
    }

    /** What a read of one document answered: a v3 {status, result}, or a v1 array of matches. */
    internal fun receivedDocumentRead(code: Int, body: String): ReceivedDocumentRead {
        if (!answeredByNightscout(code, body)) return ReceivedDocumentRead.Failed(code)
        if (code == HttpURLConnection.HTTP_NOT_FOUND || code == HttpURLConnection.HTTP_GONE) {
            return ReceivedDocumentRead.Gone
        }
        if (code !in 200..299) return ReceivedDocumentRead.Failed(code)
        val documents = runCatching {
            val trimmed = body.trim()
            val array = if (trimmed.startsWith("[")) {
                JSONArray(trimmed)
            } else {
                val wrapper = JSONObject(trimmed)
                wrapper.optJSONArray("result")
                    ?: JSONArray().put(wrapper.optJSONObject("result") ?: wrapper)
            }
            (0 until array.length()).mapNotNull { array.optJSONObject(it) }
        }.getOrElse { return ReceivedDocumentRead.Failed(code) }
        return when (documents.size) {
            0 -> ReceivedDocumentRead.Gone
            1 -> ReceivedDocumentRead.Found(documents.single())
            else -> ReceivedDocumentRead.Ambiguous
        }
    }

    /** What to do with an edit, given the document as the server holds it now. */
    internal sealed class ReceivedEditPlan {
        /** The server's copy stands, and the next receive brings it. */
        class ServerWins(val reason: String) : ReceivedEditPlan()
        /** Nothing is sent and the edit stays on the row. */
        class KeepLocal(val reason: String) : ReceivedEditPlan()
        /** Nothing the server would take differs from what it holds: the edit is settled. */
        class Settled(val timeKeptByServer: Boolean) : ReceivedEditPlan()
        class Send(val changes: JournalTreatmentTransfer.ReceivedEditChanges, val timeKeptByServer: Boolean) :
            ReceivedEditPlan()
    }

    /**
     * Decides what becomes of [entry]'s edit, given [document] as the server now holds it.
     *
     * A loop system's document is never written to: the edit stays here. The server wins where it
     * changed the document after the edit was made, where the document no longer holds the row's
     * part, and where it may not be changed. API v3 will not move a document's date (it answers
     * 400), so on v3 an edited time is not sent: it is reported as kept by the server, and the
     * next receive shows the server's time again.
     */
    internal fun receivedEditPlan(entry: JournalEntryEntity, document: JSONObject, useV3: Boolean): ReceivedEditPlan {
        if (JournalTreatmentTransfer.isLoopSystemDocument(document)) {
            return ReceivedEditPlan.KeepLocal("the document belongs to a loop or pump system")
        }
        if (JournalTreatmentTransfer.isReadOnlyDocument(document)) {
            return ReceivedEditPlan.ServerWins("the document is read-only")
        }
        val modifiedAt = JournalTreatmentTransfer.serverModifiedMillis(document)
        if (modifiedAt != null && modifiedAt > entry.updatedAt) {
            return ReceivedEditPlan.ServerWins("the server changed it after the edit")
        }
        val changes = JournalTreatmentTransfer.receivedEditChanges(entry, document)
            ?: return ReceivedEditPlan.ServerWins("the document no longer holds this ${entry.entryType}")
        // v1 finds the document by the _id in the body and makes it an ObjectId; anything else
        // fails there (500) on every attempt.
        if (!useV3 && !isObjectId(document.optString("_id"))) {
            return ReceivedEditPlan.ServerWins("v1 cannot address a document whose _id is not an ObjectId")
        }
        val timeKeptByServer = useV3 && changes.timestampMillis != null
        val sendable = if (useV3) changes.copy(timestampMillis = null) else changes
        return if (sendable.fields.length() == 0 && sendable.timestampMillis == null) {
            ReceivedEditPlan.Settled(timeKeptByServer)
        } else {
            ReceivedEditPlan.Send(sendable, timeKeptByServer)
        }
    }

    internal enum class ReceivedEditAction {
        /** The server has the edit, or there is nothing left on it to change: settled. */
        CONFIRM,
        /** The server's copy stands; the next receive writes it over the row. */
        SERVER_WINS,
        /**
         * Not sent, and not sent again unless the row is edited again ([NIGHTSCOUT_EDIT_KEPT_LOCAL]):
         * a loop system's document, or an edit the server refused.
         */
        KEEP_LOCAL,
        /** The server failed on this edit: it stays pending and waits on its own ([EditRetries]). */
        RETRY,
        /** The server is out of reach: the edit stays pending and the pass waits. */
        WAIT
    }

    /**
     * What a write of an edit answered. A document gone meanwhile (404, 410) has nothing left to
     * change, and no read will bring it back over the row; it is settled rather than retried for
     * good, which would hold every later entry back.
     */
    internal fun receivedEditWriteAction(code: Int, answeredByNightscout: Boolean): ReceivedEditAction = when {
        answeredByNightscout && (
            code == HttpURLConnection.HTTP_OK ||
                code == HttpURLConnection.HTTP_CREATED ||
                code == HttpURLConnection.HTTP_NO_CONTENT ||
                code == HttpURLConnection.HTTP_NOT_FOUND ||
                code == HttpURLConnection.HTTP_GONE
            ) -> ReceivedEditAction.CONFIRM
        else -> receivedEditFailureAction(code, answeredByNightscout)
    }

    /**
     * What a failed read or write of an edit does to it. Nightscout's own refusal (403 for a token
     * that may create but not change, 422, 400) is not retried: asking again only gets the same
     * answer, and the edit stays on the row instead. A server error on it is retried on its own;
     * anything about the whole server makes the pass wait.
     */
    internal fun receivedEditFailureAction(code: Int, answeredByNightscout: Boolean): ReceivedEditAction =
        when (operationFailure(code, answeredByNightscout)) {
            OperationFailure.WAIT -> ReceivedEditAction.WAIT
            OperationFailure.RETRY -> ReceivedEditAction.RETRY
            OperationFailure.REFUSED -> ReceivedEditAction.KEEP_LOCAL
        }

    /** One pass's outcome for one edit. */
    private class ReceivedEditResult(
        val action: ReceivedEditAction,
        val code: Int = 0,
        val message: String = "",
        /** The server accepted a write for it. */
        val wrote: Boolean = false
    )

    private fun sendReceivedEdit(
        entry: JournalEntryEntity,
        baseUrl: String,
        rawSecret: String,
        secretHashed: String?,
        useV3: Boolean
    ): ReceivedEditResult {
        val remoteId = entry.nsRemoteId!!
        val readUrl = receivedDocumentUrl(baseUrl, remoteId, useV3)
        val read = httpRequest("GET", readUrl, rawSecret, tokenAuth = useV3)
        val document = when (val found = receivedDocumentRead(read.code, read.body)) {
            is ReceivedDocumentRead.Found -> found.document
            ReceivedDocumentRead.Gone -> {
                Log.i(LOG_ID, "edit of received entry id=${entry.id}: remoteId=$remoteId is gone from the server; kept as edited")
                return ReceivedEditResult(ReceivedEditAction.CONFIRM)
            }
            ReceivedDocumentRead.Ambiguous -> {
                Log.e(LOG_ID, "edit of received entry id=${entry.id}: several documents carry remoteId=$remoteId; not sent")
                return ReceivedEditResult(ReceivedEditAction.SERVER_WINS)
            }
            is ReceivedDocumentRead.Failed -> return ReceivedEditResult(
                receivedEditFailureAction(found.code, answeredByNightscout(read.code, read.body)),
                found.code,
                serverMessage(read.body)
            )
        }
        val changes = when (val plan = receivedEditPlan(entry, document, useV3)) {
            is ReceivedEditPlan.KeepLocal -> {
                Log.i(LOG_ID, "edit of received entry id=${entry.id} remoteId=$remoteId kept here, not sent: ${plan.reason}")
                return ReceivedEditResult(ReceivedEditAction.KEEP_LOCAL)
            }
            is ReceivedEditPlan.ServerWins -> {
                Log.i(LOG_ID, "edit of received entry id=${entry.id} remoteId=$remoteId not sent: ${plan.reason}")
                return ReceivedEditResult(ReceivedEditAction.SERVER_WINS)
            }
            is ReceivedEditPlan.Settled -> {
                if (plan.timeKeptByServer) logTimeKept(entry, remoteId)
                return ReceivedEditResult(ReceivedEditAction.CONFIRM)
            }
            is ReceivedEditPlan.Send -> {
                if (plan.timeKeptByServer) logTimeKept(entry, remoteId)
                plan.changes
            }
        }
        val writeUrl = receivedEditWriteUrl(baseUrl, remoteId, useV3)
        val code: Int
        val body: String
        if (useV3) {
            // Only what changed, none of it a field v3 holds immutable (date, utcOffset,
            // eventType, app, device, isValid): the other app's document keeps its identity.
            code = NightPost.uploadPatch(writeUrl, changes.fields.toString().toByteArray(Charsets.UTF_8), secretHashed)
            body = NightPost.getLastPrimaryResponseBody()
        } else {
            val payload = JournalTreatmentTransfer.receivedEditV1Document(document, changes, System.currentTimeMillis())
            val answer = httpRequest("PUT", writeUrl, rawSecret, tokenAuth = false, payload = payload.toString().toByteArray(Charsets.UTF_8))
            code = answer.code
            body = answer.body
        }
        val action = receivedEditWriteAction(code, answeredByNightscout(code, body))
        if (action == ReceivedEditAction.CONFIRM) {
            Log.i(LOG_ID, "edit of received entry id=${entry.id} sent to remoteId=$remoteId: ${changes.fields.names()} code=$code")
        }
        return ReceivedEditResult(
            action = action,
            code = code,
            message = if (action == ReceivedEditAction.CONFIRM) "" else serverMessage(body),
            wrote = action == ReceivedEditAction.CONFIRM && code in 200..299
        )
    }

    private fun logTimeKept(entry: JournalEntryEntity, remoteId: String) {
        Log.i(
            LOG_ID,
            "edit of received entry id=${entry.id}: API v3 does not let a client move remoteId=$remoteId in time; " +
                "the server keeps its time"
        )
    }

    private class HttpAnswer(val code: Int, val body: String)

    /**
     * One request with the uploader's credentials: the v3 token when [tokenAuth], the configured
     * secret otherwise. Code -1 when nothing answered.
     */
    private fun httpRequest(
        method: String,
        endpoint: String,
        secret: String,
        tokenAuth: Boolean,
        payload: ByteArray? = null
    ): HttpAnswer {
        val auth = if (tokenAuth) NightPost.getV3AuthorizationHeader() else ""
        if (tokenAuth && auth.isEmpty()) return HttpAnswer(NightPost.ERROR_NO_RESPONSE, "")
        val connection = runCatching { URL(endpoint).openConnection() as HttpURLConnection }
            .getOrElse { return HttpAnswer(NightPost.ERROR_NO_RESPONSE, "") }
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.requestMethod = method
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "JugglucoNG Nightscout journal sync")
            if (tokenAuth) {
                connection.setRequestProperty("Authorization", auth)
            } else {
                NightscoutFollowerRegistry.applyAuth(connection, secret)
            }
            if (payload != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("Content-Length", payload.size.toString())
                connection.outputStream.use { it.write(payload) }
            }
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()
                ?.use { it.readText() }
                .orEmpty()
            if (code !in 200..299) Log.e(LOG_ID, "$method ${endpointPath(endpoint)} ResponseCode=$code\n${body.take(512)}")
            return HttpAnswer(code, body)
        } catch (th: Throwable) {
            Log.e(LOG_ID, "$method ${endpointPath(endpoint)} failure:\n${Log.stackline(th)}")
            return HttpAnswer(NightPost.ERROR_NO_RESPONSE, "")
        } finally {
            connection.disconnect()
        }
    }

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

    /** How a receive went: done (or not due), refused by the server, or not answered at all. */
    internal enum class ReceiveResult { DONE, REFUSED, UNANSWERED }

    /**
     * What a treatment pass tells the native uploader. False makes it back off, which holds the
     * next journal changes back as well, up to four hours.
     *
     * A refused read is the receive's own failure (a token without api:treatments:read answers
     * 403 every time): it is retried on the receive's interval, and folding it in held every new
     * entry back for nothing. A read nobody answered is folded in like a delete nobody answered,
     * as a server out of reach, unless this pass's own writes have just reached it.
     */
    internal fun treatmentPassOk(
        sendsOk: Boolean,
        deletesUnanswered: Boolean,
        receive: ReceiveResult,
        wroteThisPass: Boolean
    ): Boolean = sendsOk && !deletesUnanswered && !(receive == ReceiveResult.UNANSWERED && !wroteThisPass)

    /**
     * How a receive went. [readBody] is the treatment list the server answered with, null when
     * nothing was read (deferred, failed, or no such endpoint).
     */
    private class ReceiveOutcome(val result: ReceiveResult, val readBody: String? = null)

    private fun receiveRemoteTreatments(baseUrl: String, secret: String, useV3: Boolean): ReceiveOutcome {
        val floorKey = "${if (useV3) "v3" else "v1"} ${NightscoutFollowerRegistry.normalizeUrl(baseUrl)}"
        val waitMillis = receiveFloor.waitMillis(floorKey, System.currentTimeMillis())
        if (waitMillis > 0L) {
            bookTreatmentWake(waitMillis)
            return ReceiveOutcome(ReceiveResult.DONE)
        }
        var readBody: String? = null
        val result = runCatching {
            val read = fetchTreatmentsRead(baseUrl, secret, useV3)
            receiveFloor.recordRead(floorKey, System.currentTimeMillis())
            readBody = read
            val body = read ?: return@runCatching ReceiveResult.DONE
            if (body.isBlank() || body == "[]") return@runCatching ReceiveResult.DONE
            val sensorId = NightscoutFollowerRegistry.deriveSensorId(baseUrl)
            val imported = NightscoutJournalFollowerImporter.importTreatments(sensorId, body)
            if (imported > 0) {
                UiRefreshBus.requestDataRefresh()
                Log.i(LOG_ID, "received $imported Nightscout treatment journal items")
            }
            receiveErrorLog.reset()
            ReceiveResult.DONE
        }.getOrElse { error ->
            // The uploader retries on its own cadence, so an unreachable or refusing server
            // used to write the same line every few seconds and bury the rest of the trace.
            val message = "receive treatments failed: ${error.message}"
            val repeats = receiveErrorLog.suppressedSince(message, System.currentTimeMillis())
            if (repeats >= 0) {
                Log.e(LOG_ID, if (repeats == 0) message else "$message (repeated ${repeats + 1}x)")
            }
            // No answer is a failed connection or read; an error status is thrown as anything else.
            if (error is IOException) {
                ReceiveResult.UNANSWERED
            } else {
                // No backoff covers a refusal any more (see treatmentPassOk): the interval does.
                receiveFloor.recordRead(floorKey, System.currentTimeMillis())
                ReceiveResult.REFUSED
            }
        }
        return ReceiveOutcome(result, readBody)
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
