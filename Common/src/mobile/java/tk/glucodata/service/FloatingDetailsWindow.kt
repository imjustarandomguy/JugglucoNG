package tk.glucodata.service

import android.view.WindowManager

/**
 * The details card's window. It goes in through the WindowManager the pill is in, and as the
 * pill's window type: an accessibility overlay beside a pill drawn over the status bar, which
 * Android shows over the keyguard as it does the pill, so a tap on the pill on the lock screen
 * opens a card that can be seen there; an app overlay, which the keyguard hides, would open
 * unseen. Beside an app overlay pill the card stays an app overlay.
 */
internal object FloatingDetailsWindow {
    /** The card's window type beside a pill window of [pillType]. */
    fun type(pillType: Int): Int =
        if (pillType == WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY) {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        }

    /**
     * The same for either type: never focusable, and touches outside the card go on to what
     * is below it, the card only closing on them (ACTION_OUTSIDE, delivered to it while it is
     * above the window touched: as an accessibility overlay, above the keyguard and the
     * status bar too).
     */
    const val FLAGS: Int = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
        WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
}
