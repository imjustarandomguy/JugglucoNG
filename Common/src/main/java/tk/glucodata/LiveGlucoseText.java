package tk.glucodata;

/**
 * Texts of the live notification ({@link LiveGlucoseNotification}). Values arrive
 * already formatted in the user's unit; nothing here reads the clock or Android.
 */
final class LiveGlucoseText {
    /** Shown where there is no current value. */
    static final String NO_VALUE = "---";
    /** Status bar chips always show up to seven characters in full. */
    static final int CHIP_MAX_LENGTH = 7;
    static final String SEPARATOR = " · ";
    /** A current reading's notification is withdrawn by the system this long after it ages. */
    static final long TIMEOUT_GRACE_MS = 60_000L;
    static final long MIN_TIMEOUT_MS = 60_000L;

    private LiveGlucoseText() {
    }

    /**
     * The trend arrow as text, bucketed from the angle the glucose notification and
     * the dashboard draw ({@link TrendArrowAngle}), so the three agree. Doubled past
     * 2 mg/dL per minute, as the drawn arrow is. Empty for an unknown rate.
     */
    static String trendArrow(float rateMgdlPerMinute) {
        if (!Float.isFinite(rateMgdlPerMinute))
            return "";
        final float angle = TrendArrowAngle.rotationDegrees(rateMgdlPerMinute);
        final float magnitude = Math.abs(angle);
        if (magnitude < 22.5f)
            return "→";
        // Negative angles point the arrow up.
        final boolean rising = angle < 0f;
        if (magnitude < 67.5f)
            return rising ? "↗" : "↘";
        if (Math.abs(rateMgdlPerMinute) > 2f)
            return rising ? "↑↑" : "↓↓";
        return rising ? "↑" : "↓";
    }

    /** "5.8 ↗": the value and its arrow; {@link #NO_VALUE} without a value. */
    static String title(String value, float rateMgdlPerMinute) {
        final String shown = clean(value);
        if (shown.isEmpty())
            return NO_VALUE;
        final String arrow = trendArrow(rateMgdlPerMinute);
        return arrow.isEmpty() ? shown : shown + " " + arrow;
    }

    /** The status bar chip: the title when it fits in full, else the value alone. */
    static String chip(String value, float rateMgdlPerMinute) {
        final String full = title(value, rateMgdlPerMinute);
        if (full.length() <= CHIP_MAX_LENGTH)
            return full;
        final String shown = clean(value);
        return shown.isEmpty() ? NO_VALUE : shown;
    }

    /**
     * "+0.3 · 10:32": the change and the clock time of the reading. A clock time,
     * not an age, as the text is not redrawn while no reading comes.
     */
    static String detail(String deltaText, String clockTime) {
        final String delta = clean(deltaText);
        final String time = clean(clockTime);
        if (delta.isEmpty())
            return time;
        return time.isEmpty() ? delta : delta + SEPARATOR + time;
    }

    /** The last value of an aged reading, or {@link #NO_VALUE}. */
    static String lastValue(String value) {
        final String shown = clean(value);
        return shown.isEmpty() ? NO_VALUE : shown;
    }

    /**
     * Timeout for a current reading's notification: just after the reading ages, when
     * the glucose notification turns to its stale state and replaces it. Should that
     * not happen (the process gone, or asleep), the system takes the value down
     * instead of leaving it up as current.
     */
    static long freshTimeoutMs(long readingMillis, long nowMillis, long freshnessMillis) {
        final long longest = freshnessMillis + TIMEOUT_GRACE_MS;
        final long left = readingMillis + longest - nowMillis;
        return Math.max(MIN_TIMEOUT_MS, Math.min(longest, left));
    }

    private static String clean(String text) {
        return text == null ? "" : text.trim();
    }
}
