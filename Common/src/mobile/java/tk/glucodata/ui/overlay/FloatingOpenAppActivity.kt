package tk.glucodata.ui.overlay

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import tk.glucodata.Log

/**
 * The floating glucose's "open app" while the keyguard is up: drawn over the status bar, the
 * pill and its details card show on the lock screen. The app is not shown over the keyguard;
 * this asks for the keyguard to be dismissed, so the user unlocks first as for any app (the
 * bouncer asks for the credentials of a secure lock), and opens the app once that is done.
 * Backing out of the bouncer, or the screen going off, leaves the phone locked and the app
 * closed.
 *
 * Nothing of its own on screen, never shown over the keyguard (no showWhenLocked), its own
 * task, out of Recents. With the keyguard down the app opens directly; see [open].
 */
class FloatingOpenAppActivity : Activity() {
    private var done = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val keyguard = getSystemService(KeyguardManager::class.java)
        // Unlocked by now: onResume opens the app.
        if (keyguard == null || !keyguard.isKeyguardLocked) return
        keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() = openAppAndFinish()

            override fun onDismissCancelled() = close()

            override fun onDismissError() {
                // The request was refused: the app opens behind the keyguard instead, shown
                // once the user unlocks, as it did before this asked.
                Log.i(LOG_ID, "keyguard dismiss failed")
                openAppAndFinish()
            }
        })
    }

    override fun onResume() {
        super.onResume()
        // Resumed with the keyguard down: unlocked, whether or not its callback came first.
        // Never left over the screen, where its window would take the touches.
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (keyguard == null || !keyguard.isKeyguardLocked) openAppAndFinish()
    }

    private fun openAppAndFinish() {
        if (done) return
        done = true
        startApp(this)
        finish()
    }

    private fun close() {
        if (done) return
        done = true
        finish()
    }

    companion object {
        private const val LOG_ID = "FloatingOpenApp"

        /**
         * Opens the app from the floating glucose: at once with the keyguard down, else
         * through this, once the user has unlocked.
         */
        @JvmStatic
        fun open(context: Context) {
            val keyguard = context.getSystemService(KeyguardManager::class.java)
            if (keyguard == null || !keyguard.isKeyguardLocked) {
                startApp(context)
                return
            }
            try {
                context.startActivity(
                    Intent(context, FloatingOpenAppActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (t: Throwable) {
                Log.stack(LOG_ID, "open", t)
            }
        }

        private fun startApp(context: Context) {
            context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { intent ->
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                try {
                    context.startActivity(intent)
                } catch (t: Throwable) {
                    Log.stack(LOG_ID, "startApp", t)
                }
            }
        }
    }
}
