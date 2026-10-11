package tk.glucodata;

/** Pure timing rules for the phone's ongoing glucose notification. */
final class NotificationRefreshPolicy {
    private NotificationRefreshPolicy() {}

    /** How the ongoing notification shows the current reading. */
    enum OngoingLook {
        /** The value with its arrow and chart. */
        READING,
        /** The next reading is overdue: the value kept, no arrow, "no new value since". */
        STALE_READING,
        /** Too old to show a value: "no new value since" alone. */
        NO_VALUE,
        /** No reading to show, or glucose kept out of the notification: the sensor's status. */
        STATUS,
        NO_SENSOR,
    }

    /**
     * The look of the ongoing notification for the current reading. {@code presentGlucose}
     * says whether glucose is shown in it at all; without, an overdue reading still says
     * since when there is no new value.
     */
    static OngoingLook ongoingLook(boolean presentGlucose, DisplayedReading.Freshness freshness,
            boolean showsValue, long readingMillis) {
        if (freshness == DisplayedReading.Freshness.NO_SENSOR) {
            return OngoingLook.NO_SENSOR;
        }
        final boolean overdue = readingMillis > 0L
                && (freshness == DisplayedReading.Freshness.STALE || freshness == DisplayedReading.Freshness.NO_VALUE);
        if (presentGlucose && showsValue && freshness == DisplayedReading.Freshness.FRESH) {
            return OngoingLook.READING;
        }
        if (presentGlucose && showsValue && freshness == DisplayedReading.Freshness.STALE) {
            return OngoingLook.STALE_READING;
        }
        return overdue ? OngoingLook.NO_VALUE : OngoingLook.STATUS;
    }

    /**
     * A pending data refresh keeps its first deadline. Repeated requests coalesce
     * without moving the work farther into the future.
     */
    static long boundedDebounceAt(long pendingAtMs, long nowMs, long delayMs) {
        if (pendingAtMs != 0L) {
            return pendingAtMs;
        }
        final long safeDelay = Math.max(0L, delayMs);
        return nowMs > Long.MAX_VALUE - safeDelay ? Long.MAX_VALUE : nowMs + safeDelay;
    }

    static boolean mayCancelForOwner(Object requestedOwner, Object currentOwner) {
        return requestedOwner == null || requestedOwner == currentOwner;
    }

    static boolean shouldPinOngoingGlucose(boolean wearable, boolean glucoseChannel,
            boolean onlyAlertOnce, boolean alertWatch) {
        return !wearable && glucoseChannel && (onlyAlertOnce || alertWatch);
    }

    static boolean shouldSchedulePublicationRetry(long retryGeneration, long generation) {
        return generation != 0L && retryGeneration != generation;
    }
}
