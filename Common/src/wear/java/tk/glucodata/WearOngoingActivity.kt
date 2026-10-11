package tk.glucodata

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status

object WearOngoingActivity : OngoingNotification {
    private const val LOG_ID = "WearOngoingActivity"

    /** The status text the ongoing activity was last given. Guarded by this object. */
    private var shownStatus: String? = null

    override fun attach(context: Context, notification: Notification, notificationId: Int): Notification {
        return try {
            val text = statusText(CurrentReadingHub.current())
            val builder = NotificationCompat.Builder(context, notification)
            OngoingActivity.Builder(context, notificationId, builder)
                .setStaticIcon(R.drawable.novalue)
                .setStatus(status(text))
                .setTouchIntent(mainPendingIntent(context))
                .setOngoingActivityId(notificationId)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setTitle(context.getString(R.string.app_name))
                .build()
                .apply(context)
            synchronized(this) { shownStatus = text }
            builder.build().also { it.flags = it.flags or Notification.FLAG_ONGOING_EVENT }
        } catch (th: Throwable) {
            Log.stack(LOG_ID, "attach", th)
            notification
        }
    }

    override fun updateStatus(context: Context?, notificationId: Int) {
        context ?: return
        try {
            // Resolved under the lock, so a slower caller cannot put back an older value.
            synchronized(this) {
                val text = statusText(CurrentReadingHub.current())
                // Several data changes arrive per reading; an unchanged value is not
                // worth reposting the notification for.
                if (text == shownStatus) return
                val activity = OngoingActivity.recoverOngoingActivity(context, notificationId) ?: return
                activity.update(context, status(text))
                shownStatus = text
            }
        } catch (th: Throwable) {
            Log.stack(LOG_ID, "updateStatus", th)
        }
    }

    /**
     * The watch's current reading as every other watch surface shows it: the value while
     * it is shown (a status line cannot dim a stale one) and "---" once it is taken down.
     * The resolver falls back to history well past any timeout, so reading it here kept
     * the last value up as if it were live after readings stopped.
     */
    internal fun statusText(reading: DisplayedReading): String = reading.shownText

    private fun status(text: String): Status = Status.forPart(Status.TextPart(text))

    private fun mainPendingIntent(context: Context): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, 0, intent, flags)
    }
}
