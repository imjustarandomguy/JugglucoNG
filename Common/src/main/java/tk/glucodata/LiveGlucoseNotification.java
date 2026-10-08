package tk.glucodata;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;

import androidx.annotation.ChecksSdkIntAtLeast;
import androidx.annotation.RequiresApi;

/**
 * The optional live notification (phone): the current glucose as an Android 16 Live
 * Update, the promoted ongoing notification shown on the lock screen, the Always-on
 * Display and as a status bar chip, and as a Samsung One UI live notification, which
 * puts it in the Now Bar.
 *
 * It is a notification of its own beside the glucose notification, which keeps its
 * custom views and chart: a notification is only promoted with a standard style, no
 * custom views and no colorization. It uses standard fields only, on a silent channel
 * of its own, and is not bridged to watches. Notify posts it with each publication of
 * the glucose notification, the stale state included, and takes it down with it; it
 * has no timer or wakeup of its own.
 */
public final class LiveGlucoseNotification {
    public static final String PREF_ENABLED = "notification_live_update";

    private static final String PREFS = "tk.glucodata_preferences";
    private static final String CHANNEL_ID = "glucoseLiveUpdate";
    private static final int NOTIFICATION_ID = 81436;

    // Samsung's live notification ("ongoing activity") extras, read by One UI 7 and later
    // for the Now Bar and the status bar chip, where Android's promotion alone may not
    // reach. The app opts in with the com.samsung.android.support.ongoing_activity
    // meta-data in the phone manifest.
    private static final String SAMSUNG_STYLE = "android.ongoingActivityNoti.style";
    private static final int SAMSUNG_STYLE_STANDARD = 1;
    private static final String SAMSUNG_PRIMARY_INFO = "android.ongoingActivityNoti.primaryInfo";
    private static final String SAMSUNG_SECONDARY_INFO = "android.ongoingActivityNoti.secondaryInfo";
    private static final String SAMSUNG_CHIP_ICON = "android.ongoingActivityNoti.chipIcon";
    private static final String SAMSUNG_CHIP_TEXT = "android.ongoingActivityNoti.chipExpandedText";
    private static final String SAMSUNG_NOWBAR_PRIMARY_INFO = "android.ongoingActivityNoti.nowbarPrimaryInfo";
    private static final String SAMSUNG_NOWBAR_SECONDARY_INFO = "android.ongoingActivityNoti.nowbarSecondaryInfo";

    /** Unknown after a restart, so the first cancel always reaches the system. */
    private static boolean mayBeShowing = true;
    private static boolean channelCreated = false;

    private LiveGlucoseNotification() {
    }

    /** Live Updates exist from Android 16. */
    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.BAKLAVA)
    public static boolean isSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA;
    }

    public static boolean isEnabled(Context context) {
        return isSupported() && context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(PREF_ENABLED, false);
    }

    /** Whether Android lets this app's notifications be promoted; the user can turn it off per app. */
    public static boolean canPromote(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) {
            return false;
        }
        final NotificationManager manager = context.getSystemService(NotificationManager.class);
        return manager != null && manager.canPostPromotedNotifications();
    }

    /** Android's screen where the user allows this app's Live Updates. */
    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    public static Intent promotionSettingsIntent(Context context) {
        return new Intent(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.getPackageName())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    /** A current reading: "5.8 ↗", its change and time; the chip shows the value. */
    static synchronized void showReading(Context context, CurrentDisplaySource.Snapshot reading,
            float arrowRate, float delta) {
        if (!isEnabled(context) || reading == null) {
            cancel(context);
            return;
        }
        final String value = reading.getPrimaryStr();
        final long readingMillis = reading.getTimeMillis();
        final String deltaText = GlucoseDelta.format(delta, reading.isMmol());
        final String title = LiveGlucoseText.title(value, arrowRate);
        final Notification.Builder builder = builder(context, title,
                LiveGlucoseText.detail(deltaText, clockTime(context, readingMillis)),
                LiveGlucoseText.chip(value, arrowRate), readingMillis);
        builder.setTimeoutAfter(LiveGlucoseText.freshTimeoutMs(
                readingMillis, System.currentTimeMillis(), Notify.glucosetimeout));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN) {
            builder.setStyle(metrics(context, title, deltaText, reading.isMmol(), readingMillis));
        }
        post(context, builder.build());
    }

    /**
     * No current reading: the glucose notification's stale title ("No new value since
     * 10:32") over the last value, and no value in the chip.
     */
    static synchronized void showStale(Context context, CurrentDisplaySource.Snapshot last, String staleTitle) {
        if (!isEnabled(context) || last == null) {
            cancel(context);
            return;
        }
        post(context, builder(context, staleTitle, LiveGlucoseText.lastValue(last.getPrimaryStr()),
                LiveGlucoseText.NO_VALUE, last.getTimeMillis()).build());
    }

    public static synchronized void cancel(Context context) {
        if (!mayBeShowing) {
            return;
        }
        final NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.cancel(NOTIFICATION_ID);
            mayBeShowing = false;
        }
    }

    private static Notification.Builder builder(Context context, String title, String text, String chip,
            long readingMillis) {
        final Notification.Builder builder = new Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.novalue)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(Notify.mkpending())
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setLocalOnly(true)
                .setCategory(Notification.CATEGORY_STATUS)
                .setVisibility(Notification.VISIBILITY_PUBLIC);
        if (readingMillis > 0L) {
            // The header shows the reading's age and keeps it current by itself.
            builder.setWhen(readingMillis).setShowWhen(true);
        } else {
            builder.setShowWhen(false);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            builder.setRequestPromotedOngoing(true).setShortCriticalText(chip);
        }
        final Bundle samsung = new Bundle();
        samsung.putInt(SAMSUNG_STYLE, SAMSUNG_STYLE_STANDARD);
        samsung.putString(SAMSUNG_PRIMARY_INFO, title);
        samsung.putString(SAMSUNG_SECONDARY_INFO, text);
        samsung.putString(SAMSUNG_NOWBAR_PRIMARY_INFO, title);
        samsung.putString(SAMSUNG_NOWBAR_SECONDARY_INFO, text);
        samsung.putString(SAMSUNG_CHIP_TEXT, chip);
        samsung.putParcelable(SAMSUNG_CHIP_ICON, Icon.createWithResource(context, R.drawable.novalue));
        builder.addExtras(samsung);
        return builder;
    }

    /** Android 17: the value, its change and the reading's age, which counts up by itself. */
    @RequiresApi(Build.VERSION_CODES.CINNAMON_BUN)
    private static Notification.Style metrics(Context context, String title, String deltaText, boolean mmol,
            long readingMillis) {
        final String unit = context.getString(mmol ? R.string.mmolL : R.string.mgdL);
        final Notification.MetricStyle style = new Notification.MetricStyle();
        style.addMetric(new Notification.Metric(new Notification.Metric.FixedText(title, unit),
                context.getString(R.string.glucose)));
        if (!deltaText.isEmpty()) {
            final int interval = GlucoseDelta.sanitizeIntervalMinutes(context
                    .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getInt("delta_interval_minutes", GlucoseDelta.DEFAULT_INTERVAL_MINUTES));
            style.addMetric(new Notification.Metric(new Notification.Metric.FixedText(deltaText, unit),
                    context.getString(R.string.delta_change_label, interval)));
        }
        if (readingMillis > 0L) {
            style.addMetric(new Notification.Metric(
                    Notification.Metric.TimeDifference.forStopwatch(java.time.Instant.ofEpochMilli(readingMillis),
                            Notification.Metric.TimeDifference.FORMAT_ADAPTIVE),
                    context.getString(R.string.live_notification_metric_age)));
        }
        style.setCriticalMetric(0);
        return style;
    }

    private static void post(Context context, Notification notification) {
        final NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        if (!channelCreated) {
            final NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    context.getString(R.string.live_notification_section), NotificationManager.IMPORTANCE_DEFAULT);
            // Updated with every reading: never a sound, vibration or badge.
            channel.setSound(null, null);
            channel.enableVibration(false);
            channel.setShowBadge(false);
            channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            manager.createNotificationChannel(channel);
            channelCreated = true;
        }
        manager.notify(NOTIFICATION_ID, notification);
        mayBeShowing = true;
    }

    private static String clockTime(Context context, long millis) {
        return millis > 0L ? android.text.format.DateFormat.getTimeFormat(context).format(new java.util.Date(millis)) : "";
    }
}
