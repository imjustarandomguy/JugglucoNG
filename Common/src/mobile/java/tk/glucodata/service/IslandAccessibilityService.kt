package tk.glucodata.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Lends its window token to the floating glucose's Dynamic Island.
 *
 * App overlays sit under the status bar window, so an island drawn beside the
 * camera could be seen but not tapped: every touch there went to the status
 * bar. Accessibility overlays sit above it. While this service is on,
 * [FloatingGlucoseService] adds the island through its [WindowManager] when
 * "Tappable island" is set.
 *
 * It does nothing else: it asks for no events and cannot read the screen
 * (res/xml/island_accessibility_config.xml). It is kept apart from the AOD
 * service so that turning one on does not turn the other on.
 */
class IslandAccessibilityService : AccessibilityService() {
    companion object {
        private val host = MutableStateFlow<WindowManager?>(null)

        /** What accessibility overlays are added through, while the service is on; else null. */
        @JvmStatic
        val windowManager: StateFlow<WindowManager?> = host.asStateFlow()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        host.value = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        host.value = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        host.value = null
        super.onDestroy()
    }
}
