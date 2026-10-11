package tk.glucodata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Portable JVM tests for the startup header-timestamp policy: the timestamp of the reading
 * actually displayed, never a fabricated one. What a restored notification shows is the
 * current reading ([CurrentReadingHub]); its look is [NotificationRefreshPolicy.ongoingLook],
 * covered by NotificationRefreshPolicyTests. Application to the real builder code is
 * covered by NotificationStartupRestoreTests.
 */
class NotificationStartupPolicyTests {
    @Test fun aDisplayedReadingGivesTheHeaderItsTime() {
        assertEquals(1_700_000_000_123L, NotificationStartupPolicy.resolveHeaderWhenMillis(1_700_000_000_123L))
        assertTrue(NotificationStartupPolicy.showHeaderWhen(1_700_000_000_123L))
    }

    @Test fun noReadingHidesTheHeaderTime() {
        assertEquals(0L, NotificationStartupPolicy.resolveHeaderWhenMillis(0L))
        assertEquals(0L, NotificationStartupPolicy.resolveHeaderWhenMillis(-5L))
        assertFalse(NotificationStartupPolicy.showHeaderWhen(0L))
    }
}
