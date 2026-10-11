package tk.glucodata;

/** Tells a sensor's silence before its first reading apart from a lost signal. */
final class WarmupSilencePolicy {
    /** The first reading follows the warmup at the sensor's next slots. */
    static final long FIRST_READING_GRACE_MS = 15L * 60L * 1000L;

    private WarmupSilencePolicy() {
    }

    /**
     * True while a session with no reading yet is younger than its warmup plus
     * {@link #FIRST_READING_GRACE_MS}. An unknown start or any reading ends it.
     */
    static boolean awaitingFirstReading(long sessionStartMs, long warmupMs, long lastReadingMs, long nowMs) {
        if (sessionStartMs <= 0L || warmupMs <= 0L || lastReadingMs > 0L) {
            return false;
        }
        final long age = nowMs - sessionStartMs;
        return age >= 0L && age < warmupMs + FIRST_READING_GRACE_MS;
    }
}
