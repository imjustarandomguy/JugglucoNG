package tk.glucodata.ui.alerts

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import tk.glucodata.Log
import tk.glucodata.MainActivity
import tk.glucodata.R
import tk.glucodata.ui.PendingNavigation

/**
 * "Unsaved alarm changes": a quiet, ongoing notification for a draft of the
 * alarm page ([AlertSettingsEditor]) that holds edits when the app leaves the
 * screen (Home, the recents screen, another app), since only the page itself
 * says so. A tap opens the page, which finds the draft, kept on disk also when
 * Android has stopped the process since; Save or Discard removes the notice.
 */
internal object UnsavedAlarmChangesNotice {
    private const val LOG_ID = "UnsavedAlarmChanges"
    private const val CHANNEL_ID = "ALERT_SETTINGS_DRAFT"
    private const val NOTIFICATION_ID = 0x4153
    private const val ALARMS_ROUTE = "settings/alerts"

    /**
     * Whether the notice is wanted: the draft in memory says, when the page made
     * one in this process ([draftDirty]); else the edits kept on disk do.
     */
    fun wanted(draftDirty: Boolean?, editsOnDisk: Boolean): Boolean = draftDirty ?: editsOnDisk

    /** The app left the screen: posts the notice when a draft holds edits. Never throws. */
    fun onAppStopped(context: Context) {
        try {
            if (AlertSettingsEditor.hasUnsavedEdits()) post(context.applicationContext)
        } catch (t: Throwable) {
            Log.stack(LOG_ID, "onAppStopped", t)
        }
    }

    /** Nothing is left unsaved (saved, discarded, or edited back): the notice goes. */
    fun cancel(context: Context) {
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)?.cancel(NOTIFICATION_ID)
    }

    private fun post(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val title = context.getString(R.string.alert_settings_unsaved_title)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, title, NotificationManager.IMPORTANCE_LOW).apply {
                    setShowBadge(false)
                    setSound(null, null)
                }
            )
        }
        val open = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(PendingNavigation.EXTRA_ROUTE, ALARMS_ROUTE)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = context.getString(R.string.alert_settings_unsaved_text)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.novalue)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }
}
