package tk.glucodata

/**
 * Bridges managed-driver live writes to the native Nightscout uploader.
 *
 * Call this only after the matching point has been written to native poll storage. History
 * backfills deliberately do not wake the uploader; the next live point or explicit full sync
 * handles them without producing one network wake per historical row.
 */
object NightscoutUploadWake {
    private const val TAG = "NightscoutWake"

    /**
     * A journal entry was written, changed or deleted.
     *
     * Cheap enough to call per row: the uploader collapses everything raised before its next
     * pass into one, and one pass sends the whole backlog. Silent when treatments are not
     * being sent, so nothing wakes a network thread for a setting that is off.
     */
    @JvmStatic
    fun afterJournalChange() {
        if (!runCatching { Natives.getpostTreatments() }.getOrDefault(false)) return
        runCatching {
            Natives.waketreatments()
        }.onFailure {
            Log.stack(TAG, "journal wake failed", it)
        }
    }

    /**
     * The app came to the front.
     *
     * Treatments entered on the server are read back only by a treatment pass, and the readings
     * that wake the uploader all day run one only every quarter of an hour: opening the app to
     * look would show nothing new until a pass came by for another reason (leaving the app, a
     * journal change, a network change). This is the treatments' own wake, not a full pass: a
     * full "Send now" pass also ends the backoff after a refusal, and this is no reason to. It
     * asks for a light read, the newest few treatments on a short interval of their own, as the
     * full read's five minutes would hold back a dose entered just after a read, however often
     * the app is opened to look. Does nothing unless the server is set up and treatments are
     * received.
     */
    @JvmStatic
    fun afterAppToFront() {
        if (!runCatching { Natives.getuseuploader() }.getOrDefault(false)) return
        if (!JournalTreatmentUploadAccess.getReceiveTreatments()) return
        // Before the wake, so the pass it brings finds the request.
        JournalTreatmentUploadAccess.requestLightReceive()
        runCatching {
            Natives.waketreatments()
        }.onFailure {
            Log.stack(TAG, "app-to-front wake failed", it)
        }
    }

    @JvmStatic
    fun afterLiveNativeWrite(source: String, timestampMillis: Long) {
        if (timestampMillis <= 0L || !Natives.getuseuploader()) return
        runCatching {
            Natives.wakeNightscoutForLiveReading(source, timestampMillis)
        }.onFailure {
            Log.stack(TAG, "wake failed source=$source timestamp=$timestampMillis", it)
        }
    }
}
