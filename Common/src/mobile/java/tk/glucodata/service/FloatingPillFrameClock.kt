package tk.glucodata.service

import android.os.Handler
import android.view.Choreographer
import androidx.compose.runtime.MonotonicFrameClock
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * The floating pill's frames: at the next vsync, as Compose's own clock gives them, or
 * [FALLBACK_MS] later from the main thread's [handler] when no vsync came by then.
 *
 * Compose's clock (AndroidUiFrameClock) has each frame come from a Choreographer
 * animation callback, and from nothing else. One UI stops scheduling those for a
 * process in the background with no window on screen (logcat: "Choreographer:
 * CoreRune.SYSPERF_ACTIVE_APP_BBA_ENABLE : stop animation in background states"),
 * which the service is from the moment the screen-off path removes the pill's window,
 * and they stay stopped when the window comes back: a callback asked for then only runs
 * on a frame something else brings, the window's traversal after a tap or an
 * invalidation. The pill's recomposer waits for one of those callbacks before every
 * recomposition, so the reading handed to the pill at screen on was not composed, and
 * its new window drew the composition from before the screen went off until tapped.
 *
 * Here a frame does not depend on that callback: the handler delivers it when the vsync
 * does not. A composition so applied invalidates the pill's window, and its traversal
 * (not an animation callback) draws it. Frames are only asked for when the recomposer
 * has work, and the service pauses them while the screen is off (it wraps this clock in
 * a PausableMonotonicFrameClock), so the handler never runs for the pill then.
 */
internal class FloatingPillFrameClock(
    private val choreographer: Choreographer,
    private val handler: Handler,
) : MonotonicFrameClock {
    // Main thread only, as are both of a frame's deliveries.
    private var lastFrameNanos = Long.MIN_VALUE

    override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R =
        suspendCancellableCoroutine { continuation ->
            val frame = Frame { frameTimeNanos ->
                continuation.resumeWith(runCatching { onFrame(frameTimeNanos) })
            }
            choreographer.postFrameCallback(frame)
            handler.postDelayed(frame, FALLBACK_MS)
            continuation.invokeOnCancellation { frame.cancel() }
        }

    /** One frame, delivered once: by the vsync or by the handler, whichever comes first. */
    private inner class Frame(private val deliver: (Long) -> Unit) : Choreographer.FrameCallback, Runnable {
        private var delivered = false

        override fun doFrame(frameTimeNanos: Long) = deliverOnce(frameTimeNanos)

        override fun run() = deliverOnce(System.nanoTime())

        fun cancel() {
            choreographer.removeFrameCallback(this)
            handler.removeCallbacks(this)
        }

        private fun deliverOnce(frameTimeNanos: Long) {
            if (delivered) return
            delivered = true
            cancel()
            // A vsync's time can precede the handler's previous one: never go back.
            val time = maxOf(frameTimeNanos, lastFrameNanos)
            lastFrameNanos = time
            deliver(time)
        }
    }

    companion object {
        /**
         * How long a frame waits for the vsync before the handler delivers it: longer
         * than a vsync takes at any refresh rate the pill is drawn at, too short to see.
         */
        const val FALLBACK_MS = 50L
    }
}
