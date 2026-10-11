package tk.glucodata;

/** Pure ordering policy for callbacks that are allowed to drive realtime surfaces. */
final class RealtimeReadingPolicy {
    private static final long NATIVE_CURRENT_READING_SKEW_MS = 1_000L;

    private RealtimeReadingPolicy() {
    }

    static boolean shouldDispatch(long candidateTimeMs, long inMemoryHighWaterMs, long nativeHighWaterMs) {
        if (candidateTimeMs <= 0L) {
            return false;
        }
        if (inMemoryHighWaterMs > 0L && candidateTimeMs < inMemoryHighWaterMs) {
            return false;
        }
        if (nativeHighWaterMs <= 0L || candidateTimeMs >= nativeHighWaterMs) {
            return true;
        }

        // Native storage can round the same live reading just ahead of the BLE callback timestamp.
        return nativeHighWaterMs - candidateTimeMs < NATIVE_CURRENT_READING_SKEW_MS;
    }

    /**
     * Whether a reading is the one handled last, under the other device's time:
     * one of the two came from the other device and they are the same reading.
     * Two readings this device took keep the rule above.
     */
    static boolean isOtherDevicesCopy(long candidateTimeMs, boolean candidateFromPeer, long lastTimeMs,
            boolean lastFromPeer) {
        return candidateFromPeer != lastFromPeer && candidateTimeMs > 0L && lastTimeMs > 0L
                && Math.abs(candidateTimeMs - lastTimeMs) < DirectReadingStatus.SAME_READING_MS;
    }

    /** Whether native storage holds the live reading of {@code callbackTimeMs} under {@code nativeTimeMs}. */
    static boolean isNativeCopy(long callbackTimeMs, long nativeTimeMs) {
        return callbackTimeMs > 0L && nativeTimeMs > 0L
                && Math.abs(nativeTimeMs - callbackTimeMs) < NATIVE_CURRENT_READING_SKEW_MS;
    }
}
