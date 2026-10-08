package tk.glucodata;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;

import androidx.annotation.ChecksSdkIntAtLeast;
import androidx.annotation.RequiresApi;

import java.util.ArrayList;
import java.util.List;

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
 *
 * Optionally a current reading shows as a gauge ({@link LiveGlucoseGauge}): a progress
 * bar over the chart range in the range colours, with the trend arrow at the value.
 */
public final class LiveGlucoseNotification {
    public static final String PREF_ENABLED = "notification_live_update";
    public static final String PREF_GAUGE = "notification_live_update_gauge";

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
    // Samsung's progress, for the gauge: drawn in the expanded live notification, not in the
    // Now Bar. Progress over its max, segments as bundles of a colour and where they start
    // (0 to 1 along the bar), the icon drawn at the progress, and the colour One UI paints
    // the bar in up to it.
    private static final String SAMSUNG_PROGRESS = "android.ongoingActivityNoti.progress";
    private static final String SAMSUNG_PROGRESS_MAX = "android.ongoingActivityNoti.progressMax";
    private static final String SAMSUNG_PROGRESS_SEGMENTS = "android.ongoingActivityNoti.progressSegments";
    private static final String SAMSUNG_SEGMENT_START = "android.ongoingActivityNoti.progressSegments.segmentStart";
    private static final String SAMSUNG_SEGMENT_COLOR = "android.ongoingActivityNoti.progressSegments.segmentColor";
    private static final String SAMSUNG_PROGRESS_ICON = "android.ongoingActivityNoti.progressSegments.icon";
    private static final String SAMSUNG_PROGRESS_COLOR = "android.ongoingActivityNoti.progressSegments.progressColor";
    /** The gauge's arrow: the glucose notification's arrow (20dp) at this scale. */
    private static final float TRACKER_SCALE = 1.2f;

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
        final boolean asGauge = Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA
                && context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(PREF_GAUGE, false)
                && setGauge(context, builder, reading, arrowRate);
        if (!asGauge && Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN) {
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

    /**
     * The gauge in place of the metrics: the chart range as a progress bar in the range
     * colours, with the trend arrow as the tracker at the value, for Android and for One UI.
     * Not styled by progress, which would dim every range above the value (the targets
     * included, often the ones to watch); the arrow marks the value instead. Returns
     * whether it was set; it is not without a usable value.
     */
    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    private static boolean setGauge(Context context, Notification.Builder builder,
            CurrentDisplaySource.Snapshot reading, float arrowRate) {
        final float value = reading.getPrimaryValue();
        final boolean mmol = reading.isMmol();
        final float targetLow = Natives.targetlow();
        final float targetHigh = Natives.targethigh();
        final float veryLow = Natives.alarmverylow();
        final float veryHigh = Natives.alarmveryhigh();
        final LiveGlucoseGauge.Bar bar = LiveGlucoseGauge.bar(value, mmol, Natives.graphlow(), Natives.graphhigh(),
                targetLow, targetHigh, veryLow, veryHigh);
        if (bar == null) {
            return false;
        }
        final boolean dark = (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        final int[] bandColors = {
                GlucoseRangeColors.veryLow(dark),
                GlucoseRangeColors.low(dark),
                GlucoseRangeColors.inRange(dark),
                GlucoseRangeColors.high(dark),
                GlucoseRangeColors.veryHigh(dark),
        };
        // The arrow coloured as the glucose notification colours its value: the text colour,
        // or the value's range colour when the user colours values by range. Outlined, so it
        // stands out over the bar's colours.
        final int arrowColor = GlucoseValueTone.valueColorArgb(value, dark, mmol, targetLow, targetHigh,
                veryLow, veryHigh, dark ? Color.WHITE : Color.BLACK);
        final Icon tracker = Icon.createWithBitmap(tracker(context, arrowRate, mmol, arrowColor));

        final List<Notification.ProgressStyle.Segment> segments = new ArrayList<>(bar.segments.size());
        final Bundle[] samsungSegments = new Bundle[bar.segments.size()];
        for (int i = 0; i < bar.segments.size(); i++) {
            final LiveGlucoseGauge.Segment segment = bar.segments.get(i);
            final int color = bandColors[segment.band.ordinal()];
            segments.add(new Notification.ProgressStyle.Segment(segment.length).setColor(color));
            final Bundle samsungSegment = new Bundle();
            samsungSegment.putFloat(SAMSUNG_SEGMENT_START, bar.fraction(segment.start));
            samsungSegment.putInt(SAMSUNG_SEGMENT_COLOR, color);
            samsungSegments[i] = samsungSegment;
        }
        builder.setStyle(new Notification.ProgressStyle()
                .setProgressSegments(segments)
                .setProgress(bar.progress)
                .setProgressTrackerIcon(tracker)
                .setStyledByProgress(false));

        final Bundle samsung = new Bundle();
        samsung.putInt(SAMSUNG_PROGRESS, bar.progress);
        samsung.putInt(SAMSUNG_PROGRESS_MAX, bar.max);
        samsung.putParcelableArray(SAMSUNG_PROGRESS_SEGMENTS, samsungSegments);
        samsung.putParcelable(SAMSUNG_PROGRESS_ICON, tracker);
        // One UI always paints the bar up to the icon in one colour: the value's range.
        samsung.putInt(SAMSUNG_PROGRESS_COLOR, bandColors[bar.band.ordinal()]);
        builder.addExtras(samsung);
        return true;
    }

    /** The gauge's tracker: the trend arrow, or a dot for an unknown rate. */
    private static Bitmap tracker(Context context, float rate, boolean mmol, int color) {
        if (Float.isFinite(rate)) {
            return NotificationChartDrawer.drawArrow(context, rate, mmol, color, TRACKER_SCALE, true);
        }
        final int size = Math.round(20f * TRACKER_SCALE * context.getResources().getDisplayMetrics().density);
        final Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        final Canvas canvas = new Canvas(bitmap);
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final float center = size / 2f;
        paint.setColor(Color.luminance(color) > 0.5f ? 0xB0000000 : 0xC8FFFFFF);
        canvas.drawCircle(center, center, size * 0.3f, paint);
        paint.setColor(color);
        canvas.drawCircle(center, center, size * 0.2f, paint);
        return bitmap;
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
