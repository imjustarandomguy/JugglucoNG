package tk.glucodata.data.journal

import kotlinx.coroutines.CancellationException

/**
 * Per-insulin dosing settings kept in the insulin library (Room v33): the stored dose step
 * column, the default dose used by a long-acting reminder's one-tap Log button, and the times
 * of day to be reminded of a long-acting dose.
 */
object JournalInsulinDosing {
    /**
     * How a preset without a step of its own stores it. The column is NOT NULL (its schema is
     * fixed at v33), so 0, which no step can be, stands for none.
     */
    const val NO_STEP = 0f

    private const val MAX_STEP = 10f
    private const val MINUTES_PER_DAY = 24 * 60

    /** A step is a positive amount up to 10 U; anything else is none. */
    fun sanitizeStep(step: Float?): Float? = step?.takeIf { it.isFinite() && it > 0f && it <= MAX_STEP }

    /** The stored form of [step]: itself when usable, else [NO_STEP]. */
    fun storedStep(step: Float?): Float = sanitizeStep(step) ?: NO_STEP

    /** A default dose is a positive amount or nothing; never 0, which would read as a dose. */
    fun sanitizeDefaultDose(dose: Float?): Float? = dose?.takeIf { it.isFinite() && it > 0f }

    /** Minutes after midnight, in range, without duplicates, earliest first. */
    fun normalizeReminderTimes(minutes: Collection<Int>): List<Int> =
        minutes.filter { it in 0 until MINUTES_PER_DAY }.distinct().sorted()

    /** The stored form: minutes after midnight, comma-separated ("1260" is 21:00). */
    fun encodeReminderTimes(minutes: Collection<Int>): String =
        normalizeReminderTimes(minutes).joinToString(",")

    fun decodeReminderTimes(stored: String?): List<Int> =
        normalizeReminderTimes(
            stored.orEmpty().split(',').mapNotNull { it.trim().toIntOrNull() }
        )

    /** Reminders belong to long-acting insulin only: a preset counted toward IOB keeps none. */
    fun reminderTimesFor(countsTowardIob: Boolean, minutes: Collection<Int>): List<Int> =
        if (countsTowardIob) emptyList() else normalizeReminderTimes(minutes)
}

/**
 * One save of the standalone entry sheet, truthful about what was stored: all of its inputs are
 * written in one transaction, and only once they are committed do the reminders they answer go.
 * A failure is returned, not thrown, so the sheet can keep the inputs and offer to try again.
 */
object JournalSave {
    sealed interface Outcome {
        /** Everything was stored: [ids] are the rows, in the order of the inputs. */
        data class Saved(val ids: List<Long>) : Outcome

        /** Nothing was stored. */
        data class Failed(val error: Throwable) : Outcome
    }

    /**
     * Writes [inputs] with [writeAll], which stores all of them or none, then runs [afterCommit]
     * (taking down the reminders of the doses saved). [afterCommit] runs only after a commit;
     * its own failure does not make a stored save a failed one.
     */
    suspend fun commit(
        inputs: List<JournalEntryInput>,
        writeAll: suspend (List<JournalEntryInput>) -> List<Long>,
        afterCommit: (List<JournalEntryInput>) -> Unit
    ): Outcome {
        val ids = try {
            writeAll(inputs)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            return Outcome.Failed(error)
        }
        runCatching { afterCommit(inputs) }
        return Outcome.Saved(ids)
    }
}
