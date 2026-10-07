package tk.glucodata.journal

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.text.format.DateFormat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tk.glucodata.Log
import tk.glucodata.R
import tk.glucodata.SensorIdentity
import tk.glucodata.data.journal.InsulinReminderPolicy
import tk.glucodata.data.journal.InsulinReminderPolicy.Slot
import tk.glucodata.data.journal.JournalEntryInput
import tk.glucodata.data.journal.JournalEntryType
import tk.glucodata.data.journal.JournalInsulinDosing
import tk.glucodata.data.journal.JournalInsulinPreset
import tk.glucodata.data.journal.JournalRepository
import tk.glucodata.ui.journal.JournalQuickEntryActivity
import tk.glucodata.ui.journal.JournalQuickEntryPrefs
import tk.glucodata.ui.journal.formatFloatForEditor

/**
 * The basal reminder: "Tresiba not logged" when a long-acting dose is not in the journal by its
 * time. What counts as logged is [InsulinReminderPolicy]'s; this is the plumbing around it.
 *
 * - Every reminder time of every long-acting preset has one exact AlarmManager alarm, at its next
 *   time (allow-while-idle, not an alarm clock: it is a reminder, not an alarm). Nothing else
 *   runs: one wake-up per reminder per day.
 * - The alarms are set again at every process start, after a reboot, a change of time or time
 *   zone, an update of the app ([InsulinReminderRescheduleReceiver]), and whenever the presets
 *   change (a Room observer of the preset table, so every path that writes presets counts:
 *   the library, an import, a merge).
 * - At its time the alarm reads the journal and posts the notification, once; only a snooze
 *   brings it back.
 */
object InsulinReminders {
    private const val LOG_ID = "InsulinReminders"
    private const val PREFS_NAME = "tk.glucodata_preferences"
    private const val JOURNAL_ENABLED_KEY = "dashboard_journal_enabled"
    private const val SCHEDULED_SLOTS_KEY = "insulin_reminder_alarm_slots"
    private const val CHANNEL_ID = "insulin_reminders"
    private const val NOTIFICATION_TAG = "insulin_reminder"
    private const val CONFIRMATION_TIMEOUT_MS = 8_000L
    private const val URI_SCHEME = "tk.glucodata.insulin-reminder"

    internal const val ACTION_REMINDER = "tk.glucodata.insulin_reminder.REMINDER"
    internal const val ACTION_SNOOZE_END = "tk.glucodata.insulin_reminder.SNOOZE_END"
    internal const val ACTION_LOG = "tk.glucodata.insulin_reminder.LOG"
    internal const val ACTION_SNOOZE = "tk.glucodata.insulin_reminder.SNOOZE"
    private const val EXTRA_PRESET_ID = "preset_id"
    private const val EXTRA_MINUTE = "minute_of_day"
    /** The reminder time the alarm, the snooze or the action is about. */
    private const val EXTRA_DUE_AT = "due_at"
    /** When this alarm was set to ring: the reminder time, or the end of a snooze. */
    private const val EXTRA_SCHEDULED_FOR = "scheduled_for"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val observing = AtomicBoolean(false)
    private val scheduleLock = Any()
    // One Log at a time: two quick taps must not both find the dose missing.
    private val logLock = Mutex()

    /**
     * Process start: watches the presets for as long as the process lives. Room re-reads the
     * preset table only when it is written, and the first read schedules the alarms.
     */
    @JvmStatic
    fun start(context: Context) {
        if (!observing.compareAndSet(false, true)) return
        val app = context.applicationContext
        scope.launch {
            try {
                JournalRepository().observeInsulinPresets()
                    .map(InsulinReminderPolicy::slots)
                    .distinctUntilChanged()
                    .collect { slots -> schedule(app, slots, System.currentTimeMillis()) }
            } catch (t: Throwable) {
                observing.set(false)
                Log.stack(LOG_ID, "observe presets", t)
            }
        }
    }

    /** Sets every alarm again from the presets as they are now; [done] runs when finished. */
    fun rescheduleAsync(context: Context, done: () -> Unit = {}) {
        val app = context.applicationContext
        scope.launch {
            try {
                reschedule(app, System.currentTimeMillis())
            } catch (t: Throwable) {
                Log.stack(LOG_ID, "reschedule", t)
            } finally {
                done()
            }
        }
    }

    /** Handles one of this feature's own broadcasts (an alarm or a notification action). */
    internal fun handleAsync(context: Context, intent: Intent, done: () -> Unit) {
        val app = context.applicationContext
        scope.launch {
            try {
                when (intent.action) {
                    ACTION_REMINDER -> onAlarm(app, intent, snoozeEnd = false)
                    ACTION_SNOOZE_END -> onAlarm(app, intent, snoozeEnd = true)
                    ACTION_LOG -> logLock.withLock { onLog(app, intent) }
                    ACTION_SNOOZE -> onSnooze(app, intent)
                }
            } catch (t: Throwable) {
                Log.stack(LOG_ID, "handle ${intent.action}", t)
            } finally {
                done()
            }
        }
    }

    /**
     * Takes down the reminder of every insulin newly logged among [inputs] (an edit of an older
     * entry is not a dose taken now): once a dose is in, "not logged" is no longer true. A
     * pending snooze finds the dose and stays quiet on its own.
     */
    @JvmStatic
    fun onEntriesSaved(context: Context, inputs: List<JournalEntryInput>) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        inputs.asSequence()
            .filter { it.id == null && it.type == JournalEntryType.INSULIN }
            .mapNotNull { it.insulinPresetId }
            .distinct()
            .forEach { manager.cancel(NOTIFICATION_TAG, notificationId(it)) }
    }

    private suspend fun reschedule(context: Context, afterMillis: Long) {
        val presets = JournalRepository().getInsulinPresetsSnapshot()
        schedule(context, InsulinReminderPolicy.slots(presets), afterMillis)
    }

    private fun schedule(context: Context, slots: List<Slot>, afterMillis: Long) = synchronized(scheduleLock) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val previous = prefs.getStringSet(SCHEDULED_SLOTS_KEY, emptySet()).orEmpty()
        val wanted = slots.map { it.key }.toSet()
        // A time removed, an insulin archived or deleted, or no longer long-acting: its alarm goes.
        (previous - wanted).mapNotNull(Slot::fromKey).forEach { cancelAlarm(context, it) }
        slots.forEach { slot ->
            val dueAt = InsulinReminderPolicy.nextOccurrence(afterMillis, slot.minuteOfDay)
            armAlarm(context, ACTION_REMINDER, slot, dueAt = dueAt, ringAt = dueAt)
        }
        prefs.edit().putStringSet(SCHEDULED_SLOTS_KEY, wanted).apply()
        if (slots.isNotEmpty() || previous.isNotEmpty()) {
            Log.i(LOG_ID, "scheduled ${slots.size} reminder(s), cancelled ${(previous - wanted).size}")
        }
    }

    private suspend fun onAlarm(context: Context, intent: Intent, snoozeEnd: Boolean) {
        val slot = slotOf(intent) ?: return
        val now = System.currentTimeMillis()
        val dueAt = intent.getLongExtra(EXTRA_DUE_AT, now)
        val scheduledFor = intent.getLongExtra(EXTRA_SCHEDULED_FOR, dueAt)
        val repository = JournalRepository()
        if (!snoozeEnd) {
            // Tomorrow's alarm for this time, counted from the time this one was for: an alarm
            // that came a moment early must not be set again for today.
            reschedule(context, maxOf(now, dueAt))
        }
        if (!InsulinReminderPolicy.firesInTime(scheduledFor, now)) {
            Log.i(LOG_ID, "reminder ${slot.key} came ${(now - scheduledFor) / 60_000L} min late; skipped")
            return
        }
        if (!journalEnabled(context)) return
        val preset = repository.getInsulinPresetsSnapshot().firstOrNull { it.id == slot.presetId } ?: return
        if (!InsulinReminderPolicy.remindsFor(preset)) return
        val windowStart = InsulinReminderPolicy.windowStart(dueAt, slot.minuteOfDay, preset.reminderTimes)
        val entries = repository.getEntriesBetweenSnapshot(
            windowStart,
            now + InsulinReminderPolicy.FUTURE_TOLERANCE_MILLIS
        )
        if (InsulinReminderPolicy.shouldNotify(preset, slot.minuteOfDay, dueAt, now, entries)) {
            postReminder(context, preset, slot, dueAt, windowStart)
        }
    }

    /** "Log 25 U": the default dose, now, through the repository like any entry (so it uploads). */
    private suspend fun onLog(context: Context, intent: Intent) {
        val slot = slotOf(intent) ?: return
        val repository = JournalRepository()
        val preset = repository.getInsulinPresetsSnapshot().firstOrNull { it.id == slot.presetId }
        val dose = JournalInsulinDosing.sanitizeDefaultDose(preset?.defaultDose)
        if (preset == null || dose == null) {
            cancelNotification(context, slot.presetId)
            return
        }
        val now = System.currentTimeMillis()
        val dueAt = intent.getLongExtra(EXTRA_DUE_AT, now)
        val windowStart = InsulinReminderPolicy.windowStart(dueAt, slot.minuteOfDay, preset.reminderTimes)
        val alreadyLogged = InsulinReminderPolicy.loggedDose(
            preset,
            slot.minuteOfDay,
            dueAt,
            now,
            repository.getEntriesBetweenSnapshot(windowStart, now + InsulinReminderPolicy.FUTURE_TOLERANCE_MILLIS)
        )
        if (alreadyLogged != null) {
            // A second tap, or a dose logged meanwhile from the app or elsewhere: not twice.
            postConfirmation(context, preset, alreadyLogged.amount ?: dose)
            return
        }
        val input = JournalEntryInput(
            timestamp = now,
            sensorSerial = SensorIdentity.resolveMainSensor()?.takeIf { it.isNotBlank() },
            type = JournalEntryType.INSULIN,
            title = preset.displayName,
            amount = dose,
            insulinPresetId = preset.id
        )
        repository.upsertEntry(input)
        JournalQuickEntryPrefs.rememberType(JournalEntryType.INSULIN, context)
        postConfirmation(context, preset, dose)
    }

    private fun onSnooze(context: Context, intent: Intent) {
        val slot = slotOf(intent) ?: return
        val now = System.currentTimeMillis()
        val dueAt = intent.getLongExtra(EXTRA_DUE_AT, now)
        cancelNotification(context, slot.presetId)
        armAlarm(context, ACTION_SNOOZE_END, slot, dueAt = dueAt, ringAt = InsulinReminderPolicy.snoozeUntil(now))
    }

    private fun postReminder(context: Context, preset: JournalInsulinPreset, slot: Slot, dueAt: Long, windowStart: Long) {
        val manager = notificationManager(context) ?: return
        val title = context.getString(R.string.insulin_reminder_not_logged, preset.displayName)
        val text = context.getString(
            R.string.insulin_reminder_not_logged_since,
            DateFormat.getTimeFormat(context).format(Date(windowStart))
        )
        val openSheet = PendingIntent.getActivity(
            context,
            0,
            JournalQuickEntryActivity.intent(context, JournalEntryType.INSULIN, preset.id)
                .setData(uri("open", slot)),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val publicVersion = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.novalue)
            .setContentTitle(context.getString(R.string.insulin_reminder_public_title))
            .build()
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.novalue)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openSheet)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setWhen(dueAt)
            .setShowWhen(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
        val dose = JournalInsulinDosing.sanitizeDefaultDose(preset.defaultDose)
        val logAction = if (dose != null) {
            NotificationCompat.Action.Builder(
                0,
                context.getString(
                    R.string.insulin_reminder_log_dose,
                    context.getString(R.string.unit_insulin_value, formatFloatForEditor(dose))
                ),
                broadcast(context, ACTION_LOG, slot, dueAt)
            )
        } else {
            // No default to log in one tap: the entry sheet, on this insulin.
            NotificationCompat.Action.Builder(0, context.getString(R.string.journal_log_action), openSheet)
        }
        // A dose is written to the journal (and uploaded): from the lock screen, unlock first.
        builder.addAction(logAction.setAuthenticationRequired(true).build())
        builder.addAction(
            NotificationCompat.Action.Builder(
                0,
                context.getString(R.string.insulin_reminder_snooze),
                broadcast(context, ACTION_SNOOZE, slot, dueAt)
            ).build()
        )
        manager.notify(NOTIFICATION_TAG, notificationId(preset.id), builder.build())
    }

    /** "Logged 25 U Tresiba", in place of the reminder, for a few seconds. */
    private fun postConfirmation(context: Context, preset: JournalInsulinPreset, dose: Float) {
        val manager = notificationManager(context) ?: return
        val summary = listOf(
            context.getString(R.string.unit_insulin_value, formatFloatForEditor(dose)),
            preset.displayName
        ).joinToString(" ")
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.novalue)
            .setContentTitle(context.getString(R.string.insulin_reminder_logged, summary))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setTimeoutAfter(CONFIRMATION_TIMEOUT_MS)
            .build()
        manager.notify(NOTIFICATION_TAG, notificationId(preset.id), notification)
    }

    private fun cancelNotification(context: Context, presetId: Long) {
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
            ?.cancel(NOTIFICATION_TAG, notificationId(presetId))
    }

    private fun notificationManager(context: Context): NotificationManager? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return null
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.insulin_reminder_channel),
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = context.getString(R.string.insulin_reminder_channel_desc)
                }
            )
        }
        return manager
    }

    private fun armAlarm(context: Context, action: String, slot: Slot, dueAt: Long, ringAt: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            reminderIntent(context, action, slot)
                .putExtra(EXTRA_DUE_AT, dueAt)
                .putExtra(EXTRA_SCHEDULED_FOR, ringAt),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val exactAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()
        try {
            if (exactAllowed) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ringAt, pendingIntent)
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ringAt, pendingIntent)
            }
        } catch (e: SecurityException) {
            // Exact alarms withdrawn: a few minutes late still reminds.
            Log.stack(LOG_ID, "exact alarm denied, falling back", e)
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ringAt, pendingIntent)
        }
    }

    private fun cancelAlarm(context: Context, slot: Slot) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            reminderIntent(context, ACTION_REMINDER, slot),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        ) ?: return
        alarmManager.cancel(pendingIntent)
        pendingIntent.cancel()
    }

    private fun broadcast(context: Context, action: String, slot: Slot, dueAt: Long): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            0,
            reminderIntent(context, action, slot).putExtra(EXTRA_DUE_AT, dueAt),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    /** One intent per action and reminder time: the data URI keeps their PendingIntents apart. */
    private fun reminderIntent(context: Context, action: String, slot: Slot): Intent =
        Intent(context, InsulinReminderReceiver::class.java)
            .setAction(action)
            .setData(uri(action.substringAfterLast('.').lowercase(), slot))
            .putExtra(EXTRA_PRESET_ID, slot.presetId)
            .putExtra(EXTRA_MINUTE, slot.minuteOfDay)

    private fun uri(kind: String, slot: Slot): Uri =
        Uri.parse("$URI_SCHEME://$kind/${slot.presetId}/${slot.minuteOfDay}")

    private fun slotOf(intent: Intent): Slot? {
        val presetId = intent.getLongExtra(EXTRA_PRESET_ID, -1L).takeIf { it >= 0L } ?: return null
        val minute = intent.getIntExtra(EXTRA_MINUTE, -1).takeIf { it in 0 until 24 * 60 } ?: return null
        return Slot(presetId, minute)
    }

    private fun notificationId(presetId: Long): Int = presetId.toInt()

    private fun journalEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(JOURNAL_ENABLED_KEY, true)
}
