package tk.glucodata.service

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import tk.glucodata.data.settings.FloatingSettingsRepository

/** When FloatingAccessibilityService is offered, and what its setup says. Pure, see the tests. */
internal object FloatingAccessibilityAvailability {
    /** Installers whose installs Android never treats as restricted. */
    private val STORE_INSTALLERS = setOf(
        "com.android.vending",
        "com.sec.android.app.samsungapps",
        "com.amazon.venezia",
        "com.huawei.appmarket",
    )

    /**
     * Only "Over the status bar" uses the service, and only with "Details on tap", and
     * neither while floating glucose itself is off.
     */
    fun wanted(enabled: Boolean, tapShowsDetails: Boolean, aboveStatusBar: Boolean): Boolean =
        enabled && tapShowsDetails && aboveStatusBar

    /**
     * Whether Android may refuse to turn the service on until "Allow restricted settings"
     * (App info) is chosen: from Android 13 on, for an app not installed by a store.
     */
    fun mayBeRestricted(sdkInt: Int, installer: String?): Boolean =
        sdkInt >= Build.VERSION_CODES.TIRAMISU && installer !in STORE_INSTALLERS
}

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

        /** Applies [FloatingAccessibilityAvailability.wanted] to the stored settings. */
        @JvmStatic
        fun syncAvailability(context: Context) {
            val prefs = context.getSharedPreferences("tk.glucodata_preferences", Context.MODE_PRIVATE)
            setAvailable(
                context,
                FloatingAccessibilityAvailability.wanted(
                    enabled = prefs.getBoolean(FloatingSettingsRepository.KEY_ENABLED, false),
                    tapShowsDetails = prefs.getBoolean(FloatingSettingsRepository.KEY_TAP_DETAILS, true),
                    aboveStatusBar = prefs.getBoolean(FloatingSettingsRepository.KEY_ABOVE_STATUS_BAR, false),
                ),
            )
        }

        /** Whether the setup should explain "Allow restricted settings"; see [FloatingAccessibilityAvailability.mayBeRestricted]. */
        @JvmStatic
        fun mayBeRestricted(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
            val installer = runCatching {
                context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
            }.getOrNull()
            return FloatingAccessibilityAvailability.mayBeRestricted(Build.VERSION.SDK_INT, installer)
        }

        /**
         * Lists the service in the system's Accessibility settings only while it is
         * wanted (it is disabled in the manifest); disabled, it is unbound too.
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
