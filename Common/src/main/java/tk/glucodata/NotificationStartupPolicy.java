package tk.glucodata;

/**
 * Testable policy for the truthful foreground-service startup subset (N1).
 *
 * <p>Pure Java with no Android dependencies so the rules can be exercised on the JVM:
 * the notification header must show the timestamp of the reading actually displayed
 * (never render time, never a fabricated value).
 *
 * <p>Restore lifecycle (ticket epochs, atomic check-and-publish, stop invalidation)
 * lives in {@code Notify} so the real publication paths can be harnessed; what the
 * restored notification shows is the current reading ({@link CurrentReadingHub}), as
 * every later render. This class only selects timestamps.
 */
public final class NotificationStartupPolicy {
    private NotificationStartupPolicy() {
    }

    /**
     * Header timestamp for reading-bearing ongoing content: the reading actually
     * displayed, including the fallback/multi-source primary resolution. Returns 0
     * when no valid reading is displayed; the caller must then hide the header
     * timestamp instead of fabricating one (not render time, not the blindly passed
     * incoming argument).
     */
    public static long resolveHeaderWhenMillis(long displayedReadingMillis) {
        return displayedReadingMillis > 0L ? displayedReadingMillis : 0L;
    }

    /** Whether a header timestamp may be shown for the displayed reading. */
    public static boolean showHeaderWhen(long displayedReadingMillis) {
        return displayedReadingMillis > 0L;
    }
}
