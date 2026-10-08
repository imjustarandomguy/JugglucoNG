package tk.glucodata.ui

/**
 * Lets the screen on display hold back the navigation the app starts on its own
 * - a tab of the navigation bar, a notification's route - while it has edits the
 * reader has neither saved nor discarded. The screen asks its question and runs
 * the navigation it was handed once the reader has answered. Back and the
 * screen's own arrow are the screen's to catch.
 *
 * Main thread only.
 */
object LeaveGuard {
    private var guard: ((proceed: () -> Unit) -> Unit)? = null

    /** Runs [proceed] now, or hands it to the screen holding the guard. */
    fun leave(proceed: () -> Unit) {
        val current = guard
        if (current == null) proceed() else current(proceed)
    }

    fun hold(guard: (proceed: () -> Unit) -> Unit) {
        this.guard = guard
    }

    fun release(guard: (proceed: () -> Unit) -> Unit) {
        if (this.guard === guard) this.guard = null
    }
}
