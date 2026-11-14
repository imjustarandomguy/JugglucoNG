package tk.glucodata;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Decides whether a connect request may take the place of the one already queued. */
final class PendingConnectPolicy {

    private PendingConnectPolicy() {
    }

    /**
     * Cancels {@code pending} when a request due after {@code delayMillis} would run sooner.
     * Returns true only when the queued connect will no longer run, so the caller schedules
     * its own. A request due at the same time or later is already covered by the queued
     * connect, and one the executor has started can no longer be withdrawn.
     */
    static boolean cancelForSoonerRequest(ScheduledFuture<?> pending, long delayMillis) {
        if (pending == null) {
            return false;
        }
        // Both delays are measured on the executor's clock, so they compare directly.
        if (Math.max(0L, delayMillis) >= pending.getDelay(TimeUnit.MILLISECONDS)) {
            return false;
        }
        return pending.cancel(false);
    }
}
