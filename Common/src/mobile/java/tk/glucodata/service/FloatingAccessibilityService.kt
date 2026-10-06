package tk.glucodata.service

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Provides the window token for drawing the floating glucose as an
 * accessibility overlay, which sits above the status bar and so can be tapped
 * there. It requests no events and no window content. Separate from the AOD
 * service so that enabling one does not enable the other.
 */
class FloatingAccessibilityService : AccessibilityService() {
    companion object {
        private val host = MutableStateFlow<WindowManager?>(null)

        /** The WindowManager to add accessibility overlays through, or null while the service is off. */
        @JvmStatic
        val windowManager: StateFlow<WindowManager?> = host.asStateFlow()

        /**
         * Lists the service in the system's Accessibility settings only while an
         * option needs it (it is disabled in the manifest).
         */
        @JvmStatic
        fun setAvailable(context: Context, available: Boolean) {
            val component = ComponentName(context, FloatingAccessibilityService::class.java)
            val state = if (available) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
            }
            val packageManager = context.packageManager
            if (packageManager.getComponentEnabledSetting(component) != state) {
                packageManager.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
            }
        }
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
