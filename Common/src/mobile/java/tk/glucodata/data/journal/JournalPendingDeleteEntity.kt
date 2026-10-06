package tk.glucodata.data.journal

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A journal entry that was deleted locally and still has to be deleted on Nightscout.
 *
 * While it exists, reads of Nightscout skip the document it names, so a treatment deleted
 * here is not received back before the server has let go of it. It stays until a read made
 * after the server confirmed the delete no longer serves the document; with sending off it is
 * never sent, and only keeps the document from being received while reads still serve it.
 *
 * [attempts] counts the deletes the server refused. A document the server will never
 * let us delete (a token without `api:treatments:delete`, say) would otherwise be
 * retried on every upload cycle forever, so the tombstone is dropped past
 * [JournalTreatmentUploader.MAX_DELETE_ATTEMPTS]. A delete that got no answer (the server
 * out of reach) is not a refusal and does not count.
 */
@Entity(tableName = "journal_pending_deletes")
data class JournalPendingDeleteEntity(
    @PrimaryKey
    val entryId: Long,
    val nsRemoteId: String,
    val deletedAt: Long,
    val attempts: Int = 0,
    val lastAttemptAt: Long = 0L
)
