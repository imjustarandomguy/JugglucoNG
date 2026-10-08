package tk.glucodata.service

import android.view.WindowManager.LayoutParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FloatingDetailsWindowTests {
    @Test
    fun besideAPillOverTheStatusBarTheCardIsAnAccessibilityOverlayAndShowsOverTheKeyguard() {
        assertEquals(
            LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            FloatingDetailsWindow.type(LayoutParams.TYPE_ACCESSIBILITY_OVERLAY),
        )
    }

    @Test
    fun besideAnAppOverlayPillTheCardStaysAnAppOverlay() {
        assertEquals(
            LayoutParams.TYPE_APPLICATION_OVERLAY,
            FloatingDetailsWindow.type(LayoutParams.TYPE_APPLICATION_OVERLAY),
        )
    }

    @Test
    fun theCardNeverTakesFocusAndOnlyWatchesTouchesOutsideIt() {
        val flags = FloatingDetailsWindow.FLAGS
        assertTrue(flags and LayoutParams.FLAG_NOT_FOCUSABLE != 0)
        assertTrue(flags and LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH != 0)
        // Its own touches are its own: the buttons work.
        assertEquals(0, flags and LayoutParams.FLAG_NOT_TOUCHABLE)
        // Nothing that would show it, or unlock, on the keyguard on its own: that is the
        // window type's doing, and only for a card opened by a tap.
        @Suppress("DEPRECATION")
        val keyguardFlags = LayoutParams.FLAG_SHOW_WHEN_LOCKED or LayoutParams.FLAG_DISMISS_KEYGUARD or
            LayoutParams.FLAG_TURN_SCREEN_ON
        assertEquals(0, flags and keyguardFlags)
        assertNotEquals(0, flags and LayoutParams.FLAG_LAYOUT_IN_SCREEN)
    }

    @Test
    fun aTapOnThePillThatClosedTheCardOnlyClosesIt() {
        // The card got the pill's touch down as a touch outside it, at the same event time.
        assertTrue(FloatingDetailsWindow.closedByThisTap(closedByTouchAt = 5_000L, tapDownAt = 5_000L))
    }

    @Test
    fun aTapAfterATouchElsewhereClosedTheCardOpensItHoweverSoon() {
        assertFalse(FloatingDetailsWindow.closedByThisTap(closedByTouchAt = 5_000L, tapDownAt = 5_001L))
        assertFalse(FloatingDetailsWindow.closedByThisTap(closedByTouchAt = 5_000L, tapDownAt = 5_120L))
    }

    @Test
    fun aTapWithTheCardNeverClosedByATouchOpensIt() {
        assertFalse(FloatingDetailsWindow.closedByThisTap(closedByTouchAt = Long.MIN_VALUE, tapDownAt = 0L))
        assertFalse(FloatingDetailsWindow.closedByThisTap(closedByTouchAt = Long.MIN_VALUE, tapDownAt = 5_000L))
    }
}
