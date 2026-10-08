package tk.glucodata.data.journal

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A journal entry that was deleted locally and still has to be deleted on Nightscout.
 *
 * While it exists, reads of Nightscout skip the document it names, so a treatment deleted
 * here is not received back before the server has let go of it. It stays until a read made
 * after the server confirmed the delete no longer serves the document. One whose delete is not
 * sent (sending off, or see below) stays until a read of that document finds it gone; a read
 * of the newest treatments that leaves it out does not count.
 *
 * [attempts] counts the deletes the server refused or failed on, [lastAttemptAt] the last of
 * them; each waits longer before it is sent again. A delete that got no answer (the server
 * out of reach) is not counted.
 *
 * A tombstone at [JournalTreatmentUploader.MAX_DELETE_ATTEMPTS] is not sent any more: the
 * server would not take it (a token without `api:treatments:delete`, say), or its document
 * is a loop system's, which is never deleted from here. It stays, and only keeps the
 * document from being received again.
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
