package tk.glucodata.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Point
import android.os.IBinder
import android.view.Gravity
import android.view.Surface
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import tk.glucodata.ui.optOutOfForceDark
import tk.glucodata.UiRefreshBus
import tk.glucodata.data.GlucoseRepository
import tk.glucodata.data.settings.FloatingSettingsRepository
import tk.glucodata.ui.overlay.FloatingDetailsCard
import tk.glucodata.ui.overlay.FloatingDetailsCardWidth
import tk.glucodata.ui.overlay.FloatingDetailsRequest
import tk.glucodata.ui.overlay.FloatingGlucoseOverlay
import tk.glucodata.ui.overlay.OverlayPosition
import tk.glucodata.ui.overlay.OverlayScreenArea
import tk.glucodata.ui.overlay.clampOverlayPixels
import tk.glucodata.ui.overlay.overlayPixels
import tk.glucodata.ui.overlay.overlayPosition
import tk.glucodata.Natives

class FloatingGlucoseService : Service(), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
    private companion object {
        private const val FLOATING_HISTORY_WINDOW_MS = 6L * 60L * 60L * 1000L
        /** How long the details card stays open on its own. */
        private const val DETAILS_TIMEOUT_MS = 10_000L
    }

    enum class CutoutEdge {
        NONE,
        TOP,
        BOTTOM,
        LEFT,
        RIGHT
    }

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()
    
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = store

    private var windowManager: WindowManager? = null
    private var composeView: ComposeView? = null
    // The view actually attached to the WindowManager; see CutoutAwareContainer.
    private var overlayRoot: View? = null
    private lateinit var layoutParams: WindowManager.LayoutParams
    
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // The details card has its own window, so opening it never resizes or moves the pill.
    private var detailsRoot: View? = null
    private var detailsClosedByOutsideTouchAt = 0L
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val closeDetailsRunnable = Runnable { closeDetails() }

    private lateinit var settingsRepository: FloatingSettingsRepository
    private val glucoseRepository = GlucoseRepository()

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        settingsRepository = FloatingSettingsRepository(this)
        glucoseRepository.refreshSensorSerial()

        setupOverlay()
        androidx.core.content.ContextCompat.registerReceiver(
            this,
            screenStateReceiver,
            android.content.IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            },
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        observeSettings()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        
        // Satisfy Foreground Service requirement immediately
        // Same id as the glucose notification, so both services share one; repost it over the placeholder.
        startForeground(tk.glucodata.Notify.GLUCOSE_NOTIFICATION_ID, createForegroundNotification())
        tk.glucodata.Notify.showoldglucose()
    }

    private fun createForegroundNotification(): android.app.Notification {
        // Notify's glucose channel, unchanged: another name here would relabel it in system settings.
        tk.glucodata.Notify.ensureNotificationChannels(applicationContext)
        val builder = android.app.Notification.Builder(this, tk.glucodata.Notify.GLUCOSE_CHANNEL_ID)
        
        val prefs = getSharedPreferences("tk.glucodata_preferences", Context.MODE_PRIVATE)
        val hideIcon = prefs.getBoolean("notification_hide_status_icon", false)
        val icon = if (hideIcon) tk.glucodata.R.drawable.transparent_icon else tk.glucodata.R.drawable.novalue
        
        return builder.setOngoing(true)
            .setSmallIcon(icon)
            .setContentTitle("JugglucoNG Overlay")
            .setContentText("Service is running")
            .setCategory(android.app.Notification.CATEGORY_SERVICE)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        // If the system kills the service, recreate it with a null intent
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // State for Dynamic Island
    data class CutoutData(
        val size: androidx.compose.ui.unit.Dp,
        val edge: CutoutEdge
    )
    private val cutoutData = kotlinx.coroutines.flow.MutableStateFlow(CutoutData(0.dp, CutoutEdge.NONE))
    private var dynamicIslandEnabled = false
    // Freeform placement is kept screen-relative and resolved against the current
    // screen area on every placement; see OverlayPosition.
    private var freeformPosition = OverlayPosition.CENTRE
    // Pixel position from before #685, converted once the overlay has a size.
    private var legacyPixelPosition: Pair<Int, Int>? = null
    private var screenArea: OverlayScreenArea? = null
    private var dragging = false
    // The app's WindowManager or FloatingAccessibilityService's; see attachToHost.
    private var hostWindowManager: WindowManager? = null

    private fun setupOverlay() {
        if (composeView != null) return

        val root = CutoutAwareContainer(this) { v, insets ->
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                cutoutData.value = resolveCutoutData(v, insets.displayCutout)
                if (dynamicIslandEnabled) {
                    applyOverlayPlacement()
                }
            }
        }.apply {
            // A new value width, font size or secondary line resizes the pill; re-place
            // it so it grows away from the edge it is docked to instead of over it.
            addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
                val resized = right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop
                if (resized && !dynamicIslandEnabled && !dragging) {
                    applyOverlayPlacement()
                }
            }
            // Compose resolves the window recomposer's lifecycle from the window's
            // rootView, which is this container now — the owners have to live here.
            setViewTreeLifecycleOwner(this@FloatingGlucoseService)
            setViewTreeViewModelStoreOwner(this@FloatingGlucoseService)
            setViewTreeSavedStateRegistryOwner(this@FloatingGlucoseService)
        }
        overlayRoot = root
        root.optOutOfForceDark()

        composeView = ComposeView(this).apply {
            setContent {
                FloatingGlucoseOverlay(
                    repository = settingsRepository,
                    historyFlow = glucoseRepository.getHistoryFlow(
                        System.currentTimeMillis() - FLOATING_HISTORY_WINDOW_MS,
                        Natives.getunit() == 1
                    ),
                    onUpdatePosition = { x, y -> updateViewPosition(x, y) },
                    onDragFinished = { persistViewPosition() },
                    cutoutDataFlow = cutoutData,
                    onToggleDetails = { toggleDetails(it) },
                    onOpenApp = {
                        closeDetails()
                        openApp()
                    },
                )
            }
        }
        
        // ... (LayoutParams init)

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or 
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or 
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            layoutParams.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        
        // Initial position; corrected once the overlay has been measured.
        val savedPosition = settingsRepository.getPosition()
        if (savedPosition != null) {
            freeformPosition = savedPosition
        } else {
            // Before #685 a fresh install started at (100, 100) px; keep that spot.
            legacyPixelPosition = settingsRepository.getLegacyPixelPosition() ?: (100 to 100)
        }
        screenArea = currentScreenArea()
        layoutParams.gravity = Gravity.TOP or Gravity.START
        placeFreeform()

        root.addView(
            composeView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )

        // attachToHost adds it once the settings are known; the layout listener
        // places it again once it has its size.
    }
    
    private fun toggleDetails(request: FloatingDetailsRequest) {
        if (detailsRoot != null) {
            closeDetails()
            return
        }
        // A pill tap this soon after an outside touch closed the card is that same touch.
        val sinceClosed = android.os.SystemClock.uptimeMillis() - detailsClosedByOutsideTouchAt
        if (sinceClosed < android.view.ViewConfiguration.getLongPressTimeout()) return
        openDetails(request)
    }

    /**
     * Opens the card beside the pill, on the side with room: away from the edge
     * an island is docked to; for the free pill below it in the top half of the
     * screen and above it in the bottom half, aligned to its nearer side edge.
     */
    private fun openDetails(request: FloatingDetailsRequest) {
        val wm = windowManager ?: return
        val anchor = overlayRoot ?: return
        val location = IntArray(2)
        anchor.getLocationOnScreen(location)
        val pillLeft = location[0]
        val pillTop = location[1]
        val pillRight = pillLeft + anchor.width
        val pillBottom = pillTop + anchor.height
        val pillCentreX = (pillLeft + pillRight) / 2
        val pillCentreY = (pillTop + pillBottom) / 2
        val screen = displaySizePx()
        val density = resources.displayMetrics.density
        val gap = (6 * density).toInt()
        val margin = (8 * density).toInt()

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                // Outside touches still reach the app below; the card only closes on them.
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        )
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        val cardWidth = (FloatingDetailsCardWidth.value * density).toInt()
        // Left edge for a card centred on, or aligned with, the pill, kept on screen.
        fun cardLeft(left: Int) = left.coerceIn(margin, maxOf(margin, screen.x - cardWidth - margin))
        val islandEdge = if (dynamicIslandEnabled) currentIslandEdge() else null
        when (islandEdge) {
            CutoutEdge.LEFT -> {
                params.gravity = Gravity.START or Gravity.CENTER_VERTICAL
                params.x = pillRight + gap
                params.y = pillCentreY - screen.y / 2
            }
            CutoutEdge.RIGHT -> {
                params.gravity = Gravity.END or Gravity.CENTER_VERTICAL
                params.x = screen.x - pillLeft + gap
                params.y = pillCentreY - screen.y / 2
            }
            CutoutEdge.BOTTOM -> {
                params.gravity = Gravity.BOTTOM or Gravity.START
                params.x = cardLeft(pillCentreX - cardWidth / 2)
                params.y = screen.y - pillTop + gap
            }
            CutoutEdge.TOP, CutoutEdge.NONE -> {
                params.gravity = Gravity.TOP or Gravity.START
                params.x = cardLeft(pillCentreX - cardWidth / 2)
                params.y = pillBottom + gap
            }
            null -> {
                val below = pillCentreY < screen.y / 2
                val alignStart = pillCentreX < screen.x / 2
                params.gravity = (if (below) Gravity.TOP else Gravity.BOTTOM) or Gravity.START
                params.x = cardLeft(if (alignStart) pillLeft else pillRight - cardWidth)
                params.y = if (below) pillBottom + gap else screen.y - pillTop + gap
            }
        }

        val root = OutsideTouchContainer(this) {
            detailsClosedByOutsideTouchAt = android.os.SystemClock.uptimeMillis()
            closeDetails()
        }.apply {
            setViewTreeLifecycleOwner(this@FloatingGlucoseService)
            setViewTreeViewModelStoreOwner(this@FloatingGlucoseService)
            setViewTreeSavedStateRegistryOwner(this@FloatingGlucoseService)
        }
        val card = ComposeView(this).apply {
            setContent {
                FloatingDetailsCard(
                    request = request,
                    isDark = androidx.compose.foundation.isSystemInDarkTheme(),
                    onOpenApp = {
                        closeDetails()
                        openApp()
                    },
                )
            }
        }
        root.addView(
            card,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT)
        )
        try {
            wm.addView(root, params)
            detailsRoot = root
            mainHandler.postDelayed(closeDetailsRunnable, DETAILS_TIMEOUT_MS)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun closeDetails() {
        mainHandler.removeCallbacks(closeDetailsRunnable)
        val root = detailsRoot ?: return
        detailsRoot = null
        try {
            windowManager?.removeView(root)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun openApp() {
        packageManager.getLaunchIntentForPackage(packageName)?.let { intent ->
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            startActivity(intent)
        }
    }

    private fun updateViewPosition(xDelta: Int, yDelta: Int) {
        val root = overlayRoot ?: return
        if (windowManager == null) return
        // The card is placed against the pill; it would be left behind.
        closeDetails()
        if (!dragging) {
            // Re-read once per drag, in case the screen changed since the last placement.
            dragging = true
            screenArea = currentScreenArea()
        }

        val area = screenArea
        val x = layoutParams.x + xDelta
        val y = layoutParams.y + yDelta
        if (area != null) {
            val (clampedX, clampedY) = clampOverlayPixels(x, y, area, root.width, root.height)
            layoutParams.x = clampedX
            layoutParams.y = clampedY
            freeformPosition = overlayPosition(clampedX, clampedY, area, root.width, root.height, freeformPosition)
        } else {
            layoutParams.x = x
            layoutParams.y = y
        }

        try {
            hostWindowManager?.updateViewLayout(overlayRoot, layoutParams)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** Full display size in pixels: the overlay windows are laid out in screen coordinates. */
    @Suppress("DEPRECATION")
    private fun displaySizePx(): android.graphics.Point {
        val wm = windowManager ?: return android.graphics.Point(Int.MAX_VALUE, Int.MAX_VALUE)
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            val bounds = wm.maximumWindowMetrics.bounds
            return android.graphics.Point(bounds.width(), bounds.height())
        }
        val metrics = android.util.DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(metrics)
        return android.graphics.Point(metrics.widthPixels, metrics.heightPixels)
    }

    private fun statusBarHeightPx(): Int {
        val wm = windowManager ?: return 0
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            return wm.currentWindowMetrics.windowInsets
                .getInsetsIgnoringVisibility(WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout())
                .top
        }
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else 0
    }

    private fun persistViewPosition() {
        dragging = false
        if (dynamicIslandEnabled) return
        // A reading can resize the pill mid-drag, when the layout listener stands
        // back; place it again with the final size before saving.
        applyOverlayPlacement()
        settingsRepository.savePosition(freeformPosition)
    }


    private fun observeSettings() {
        serviceScope.launch {
            UiRefreshBus.events.collectLatest {
                glucoseRepository.refreshSensorSerial()
            }
        }

        serviceScope.launch {
            settingsRepository.isEnabled.collectLatest { enabled ->
                if (!enabled) {
                    stopSelf()
                }
            }
        }
        
        // Picks up "Reset position" from settings. Our own saves echo back unchanged.
        serviceScope.launch {
            settingsRepository.position.collectLatest { saved ->
                if (saved != null && saved != freeformPosition) {
                    freeformPosition = saved
                    legacyPixelPosition = null
                    applyOverlayPlacement()
                }
            }
        }

        serviceScope.launch {
            combine(
                settingsRepository.isDynamicIslandEnabled,
                settingsRepository.tapShowsDetails,
                settingsRepository.isAboveStatusBar,
                FloatingAccessibilityService.windowManager,
            ) { island, tapShowsDetails, aboveStatusBar, accessibilityWm ->
                // "Over the status bar" is there to open the details card from the status bar.
                val wanted = tapShowsDetails && aboveStatusBar
                FloatingAccessibilityService.setAvailable(this@FloatingGlucoseService, wanted)
                island to (if (wanted && accessibilityWm != null) accessibilityWm else windowManager)
            }.distinctUntilChanged().collect { (island, host) ->
                dynamicIslandEnabled = island
                attachToHost(host)
                applyOverlayPlacement()
            }
        }
    }

    /**
     * Attaches the pill through [host]: FloatingAccessibilityService's
     * WindowManager to draw it over the status bar (app overlays sit under the
     * status bar window, which takes every touch in its strip), else the app's.
     */
    private fun attachToHost(host: WindowManager?) {
        val root = overlayRoot ?: return
        val app = windowManager ?: return
        if (host == null || host === hostWindowManager) return
        closeDetails()
        hostWindowManager?.let { old ->
            try {
                old.removeViewImmediate(root)
            } catch (e: Exception) {
                // Not attached; nothing to remove.
            }
        }
        hostWindowManager = null
        if (!addOverlay(host, root) && host !== app) addOverlay(app, root)
        updateLockScreenVisibility()
    }

    private fun addOverlay(host: WindowManager, root: View): Boolean {
        layoutParams.type = if (host === windowManager) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        }
        // addView stamps the host's window token into the params; clear the previous host's.
        layoutParams.token = null
        return try {
            host.addView(root, layoutParams)
            hostWindowManager = host
            root.requestApplyInsets()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /** An accessibility overlay also draws over the lock screen; hide the pill there. */
    private fun updateLockScreenVisibility() {
        val root = overlayRoot ?: return
        val keyguard = getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
        val overStatusBar = layoutParams.type == WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        root.visibility = if (overStatusBar && keyguard.isKeyguardLocked) View.GONE else View.VISIBLE
    }

    private val screenStateReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) closeDetails()
            updateLockScreenVisibility()
        }
    }

    @Suppress("DEPRECATION")
    private fun resolveCutoutData(
        anchorView: View?,
        cutout: android.view.DisplayCutout?
    ): CutoutData {
        val density = resources.displayMetrics.density
        val edge = resolveIslandEdge(anchorView)
        val cutoutRect = when (edge) {
            CutoutEdge.LEFT -> cutout?.boundingRects?.minByOrNull { it.left }
            CutoutEdge.RIGHT -> cutout?.boundingRects?.maxByOrNull { it.right }
            CutoutEdge.BOTTOM -> cutout?.boundingRects?.maxByOrNull { it.bottom }
            else -> cutout?.boundingRects?.minByOrNull { it.top }
        }
        val gapPx = cutoutRect?.width()?.takeIf { it > 0 }
            ?: cutoutRect?.height()?.takeIf { it > 0 }
            ?: 0
        return CutoutData(
            size = if (gapPx > 0) (gapPx / density).dp else 0.dp,
            edge = edge
        )
    }

    /** The edge the island is docked to: the cutout's, or the rotation's on a screen without one. */
    private fun currentIslandEdge(): CutoutEdge =
        cutoutData.value.edge.takeIf { it != CutoutEdge.NONE } ?: resolveIslandEdge()

    @Suppress("DEPRECATION")
    private fun resolveIslandEdge(anchorView: View? = composeView): CutoutEdge {
        val rotation = anchorView?.display?.rotation
            ?: windowManager?.defaultDisplay?.rotation
            ?: Surface.ROTATION_0
        return when (rotation) {
            // Android ROTATION_90/270 are mirrored relative to the user's
            // landscape left/right expectation on the Pixel punch-hole case.
            Surface.ROTATION_90 -> CutoutEdge.LEFT
            Surface.ROTATION_180 -> CutoutEdge.BOTTOM
            Surface.ROTATION_270 -> CutoutEdge.RIGHT
            else -> CutoutEdge.TOP
        }
    }

    private fun applyOverlayPlacement() {
        if (overlayRoot == null || windowManager == null) return

        if (dynamicIslandEnabled) {
            val islandEdge = currentIslandEdge()
            when (islandEdge) {
                CutoutEdge.LEFT -> {
                    layoutParams.gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    layoutParams.x = 0
                    layoutParams.y = 0
                }
                CutoutEdge.RIGHT -> {
                    layoutParams.gravity = Gravity.END or Gravity.CENTER_VERTICAL
                    layoutParams.x = 0
                    layoutParams.y = 0
                }
                CutoutEdge.BOTTOM -> {
                    layoutParams.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                    layoutParams.x = 0
                    layoutParams.y = 0
                }
                else -> {
                    layoutParams.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    layoutParams.x = 0
                    layoutParams.y = 0
                }
            }
        } else {
            layoutParams.gravity = Gravity.TOP or Gravity.START
            screenArea = currentScreenArea()
            placeFreeform()
        }

        try {
            hostWindowManager?.updateViewLayout(overlayRoot, layoutParams)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** Resolves [freeformPosition] into layoutParams.x/y for the current screen area. */
    private fun placeFreeform() {
        val area = screenArea
        val root = overlayRoot
        if (area == null || root == null) {
            legacyPixelPosition?.let { (x, y) ->
                layoutParams.x = x
                layoutParams.y = y
            }
            return
        }
        val width = root.width
        val height = root.height
        val legacy = legacyPixelPosition
        if (legacy != null) {
            if (width == 0 || height == 0) {
                // Not measured yet: keep it near the old spot until the first layout.
                val (x, y) = clampOverlayPixels(legacy.first, legacy.second, area, 0, 0)
                layoutParams.x = x
                layoutParams.y = y
                return
            }
            freeformPosition = overlayPosition(legacy.first, legacy.second, area, width, height, OverlayPosition.CENTRE)
            legacyPixelPosition = null
            settingsRepository.savePosition(freeformPosition)
        }
        val (x, y) = overlayPixels(freeformPosition, area, width, height)
        layoutParams.x = x
        layoutParams.y = y
    }

    /**
     * The display below the status bar, in the coordinates the TOP|START overlay
     * window is laid out in. The navigation bar and side cutouts are allowed: the
     * pill may sit there, it just can't leave the screen. The status bar strip is
     * not, unless the pill is an accessibility overlay: app overlays sit under the
     * status bar window, so every touch there goes to the status bar and a pill
     * dragged into it could not be grabbed again.
     */
    @Suppress("DEPRECATION")
    private fun currentScreenArea(): OverlayScreenArea? {
        val wm = windowManager ?: return null
        return runCatching {
            val overStatusBar = layoutParams.type == WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            val statusBar = if (overStatusBar) 0 else statusBarHeightPx()
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val bounds = wm.currentWindowMetrics.bounds
                OverlayScreenArea(bounds.left, bounds.top + statusBar, bounds.right, bounds.bottom)
            } else {
                val real = Point().also { wm.defaultDisplay.getRealSize(it) }
                OverlayScreenArea(0, statusBar, real.x, real.y)
            }
        }.getOrNull()?.takeIf { it.right > it.left && it.bottom > it.top }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        closeDetails()
        overlayRoot?.post {
            overlayRoot?.requestApplyInsets()
            // Freeform too: a spot that was fine in portrait can be off-screen in
            // landscape.
            applyOverlayPlacement()
        }
    }

    /**
     * Cutout detection used to hang on setOnApplyWindowInsetsListener of the
     * ComposeView itself. Since Compose 1.10 the AndroidComposeView child installs
     * its own OnApplyWindowInsetsListener on its *parent* when it attaches (see
     * InsetsListener.onViewAttachedToWindow), silently replacing ours: cutoutData
     * stayed NONE, so the island still docked to the correct edge via the rotation
     * fallback but rendered as the horizontal pill with the default gap in
     * landscape. This container is the window root; Compose never touches it,
     * and dispatchApplyWindowInsets runs before any listener anyway.
     */
    private class CutoutAwareContainer(
        context: Context,
        private val onInsets: (View, WindowInsets) -> Unit
    ) : FrameLayout(context) {
        override fun dispatchApplyWindowInsets(insets: WindowInsets): WindowInsets {
            onInsets(this, insets)
            return super.dispatchApplyWindowInsets(insets)
        }
    }

    /** Root of the details card's window: reports touches that land outside it. */
    private class OutsideTouchContainer(
        context: Context,
        private val onOutsideTouch: () -> Unit
    ) : FrameLayout(context) {
        override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
            if (event.actionMasked == android.view.MotionEvent.ACTION_OUTSIDE) {
                onOutsideTouch()
                return false
            }
            return super.dispatchTouchEvent(event)
        }
    }

    override fun onDestroy() {
        unregisterReceiver(screenStateReceiver)
        closeDetails()
        // The id is shared with keeprunning's glucose notification; detach it while keeprunning still holds it.
        val held = tk.glucodata.Notify.keeprunningHoldsGlucoseNotification()
        stopForeground(if (held) STOP_FOREGROUND_DETACH else STOP_FOREGROUND_REMOVE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
        serviceScope.cancel()
        if (overlayRoot != null) {
            try {
                hostWindowManager?.removeView(overlayRoot)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            overlayRoot = null
            composeView = null
        }
        super.onDestroy()
    }
}
