package tk.glucodata.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.Gravity
import android.view.Surface
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.core.view.doOnLayout
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
import kotlinx.coroutines.launch
import tk.glucodata.UiRefreshBus
import tk.glucodata.data.GlucoseRepository
import tk.glucodata.data.settings.FloatingSettingsRepository
import tk.glucodata.ui.overlay.FloatingDetailsCard
import tk.glucodata.ui.overlay.FloatingDetailsRequest
import tk.glucodata.ui.overlay.FloatingGlucoseOverlay
import tk.glucodata.Natives

class FloatingGlucoseService : Service(), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
    private companion object {
        private const val FLOATING_HISTORY_WINDOW_MS = 6L * 60L * 60L * 1000L
        /** How long the details card stays open on its own. */
        private const val DETAILS_TIMEOUT_MS = 10_000L
        /** A tap within this of the card closing on an outside touch is that same touch. */
        private const val DETAILS_REOPEN_GUARD_MS = 400L
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
    private var tapShowsDetails = true
    private var aboveStatusBar = false
    // The app's WindowManager or FloatingAccessibilityService's; see attachToHost.
    private var hostWindowManager: WindowManager? = null
    private var freeformX = 0
    private var freeformY = 0
    
    // ...

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
            // Compose resolves the window recomposer's lifecycle from the window's
            // rootView, which is this container now — the owners have to live here.
            setViewTreeLifecycleOwner(this@FloatingGlucoseService)
            setViewTreeViewModelStoreOwner(this@FloatingGlucoseService)
            setViewTreeSavedStateRegistryOwner(this@FloatingGlucoseService)
        }
        overlayRoot = root

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
        
        // Initial position
        val (startX, startY) = settingsRepository.getPosition()
        freeformX = startX
        freeformY = startY
        layoutParams.gravity = Gravity.TOP or Gravity.START
        layoutParams.x = startX
        layoutParams.y = startY

        root.addView(
            composeView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )

        try {
            windowManager?.addView(root, layoutParams)
            hostWindowManager = windowManager
            root.requestApplyInsets()
            // Place again once the pill has its size, for the edge limits.
            root.doOnLayout { applyOverlayPlacement() }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    private fun toggleDetails(request: FloatingDetailsRequest) {
        if (detailsRoot != null) {
            closeDetails()
            return
        }
        // That tap already closed the card as an outside touch.
        if (android.os.SystemClock.uptimeMillis() - detailsClosedByOutsideTouchAt < DETAILS_REOPEN_GUARD_MS) return
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
        val islandEdge = if (dynamicIslandEnabled) {
            cutoutData.value.edge.takeIf { it != CutoutEdge.NONE } ?: CutoutEdge.TOP
        } else {
            null
        }
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
                params.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                params.x = pillCentreX - screen.x / 2
                params.y = screen.y - pillTop + gap
            }
            CutoutEdge.TOP, CutoutEdge.NONE -> {
                params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                params.x = pillCentreX - screen.x / 2
                params.y = pillBottom + gap
            }
            null -> {
                val below = pillCentreY < screen.y / 2
                val alignStart = pillCentreX < screen.x / 2
                params.gravity = (if (below) Gravity.TOP else Gravity.BOTTOM) or
                    (if (alignStart) Gravity.START else Gravity.END)
                params.x = maxOf(margin, if (alignStart) pillLeft else screen.x - pillRight)
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
        if (overlayRoot == null || windowManager == null) return
        // The card is placed against the pill; it would be left behind.
        closeDetails()

        layoutParams.x += xDelta
        layoutParams.y += yDelta
        keepFreePillTouchable()
        freeformX = layoutParams.x
        freeformY = layoutParams.y

        try {
            hostWindowManager?.updateViewLayout(overlayRoot, layoutParams)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Keeps the free pill on screen, and below the status bar unless it is an
     * accessibility overlay: the status bar window takes every touch in its
     * strip from app overlays.
     */
    private fun keepFreePillTouchable() {
        val limits = freePillLimits ?: computeFreePillLimits().also { freePillLimits = it }
        layoutParams.x = layoutParams.x.coerceIn(limits.left, maxOf(limits.left, limits.right))
        layoutParams.y = layoutParams.y.coerceIn(limits.top, maxOf(limits.top, limits.bottom))
    }

    /** Where the free pill's top-left corner may go; cached for the length of a drag. */
    private var freePillLimits: android.graphics.Rect? = null

    private fun computeFreePillLimits(): android.graphics.Rect {
        val screen = displaySizePx()
        val width = overlayRoot?.width ?: 0
        val height = overlayRoot?.height ?: 0
        val overStatusBar = layoutParams.type == WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        val top = if (overStatusBar) 0 else statusBarHeightPx()
        return android.graphics.Rect(0, top, screen.x - width, screen.y - height)
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
            return wm.maximumWindowMetrics.windowInsets
                .getInsetsIgnoringVisibility(WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout())
                .top
        }
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else 0
    }

    private fun persistViewPosition() {
        freePillLimits = null
        if (dynamicIslandEnabled) return
        serviceScope.launch {
            settingsRepository.savePosition(freeformX, freeformY)
        }
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
        
        serviceScope.launch {
            settingsRepository.isDynamicIslandEnabled.collectLatest { isIsland ->
                dynamicIslandEnabled = isIsland
                attachToHost()
                applyOverlayPlacement()
            }
        }

        serviceScope.launch {
            settingsRepository.tapShowsDetails.collectLatest { enabled ->
                tapShowsDetails = enabled
                attachToHost()
                applyOverlayPlacement()
            }
        }

        serviceScope.launch {
            settingsRepository.isAboveStatusBar.collectLatest { enabled ->
                aboveStatusBar = enabled
                attachToHost()
                applyOverlayPlacement()
            }
        }

        serviceScope.launch {
            FloatingAccessibilityService.windowManager.collectLatest {
                attachToHost()
                applyOverlayPlacement()
            }
        }
    }

    /**
     * Attaches the pill as an accessibility overlay when "Over the status bar"
     * is on and its service is running, otherwise as an app overlay. App
     * overlays sit under the status bar window, so touches there never reach them.
     */
    private fun attachToHost() {
        val root = overlayRoot ?: return
        val accessibilityWm = FloatingAccessibilityService.windowManager.value
        val useAccessibility = tapShowsDetails && aboveStatusBar && accessibilityWm != null
        val target = (if (useAccessibility) accessibilityWm else windowManager) ?: return
        if (target === hostWindowManager) return
        closeDetails()
        hostWindowManager?.let { old ->
            try {
                old.removeViewImmediate(root)
            } catch (e: Exception) {
                // The system has already removed it if the accessibility service stopped.
            }
        }
        hostWindowManager = null
        layoutParams.type = if (useAccessibility) {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        }
        try {
            target.addView(root, layoutParams)
            hostWindowManager = target
            root.requestApplyInsets()
        } catch (e: Exception) {
            e.printStackTrace()
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
            val islandEdge = cutoutData.value.edge.takeIf { it != CutoutEdge.NONE }
                ?: resolveIslandEdge()
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
            layoutParams.x = freeformX
            layoutParams.y = freeformY
            // A saved position may lie in the status bar strip or off a smaller screen.
            freePillLimits = null
            keepFreePillTouchable()
            freeformX = layoutParams.x
            freeformY = layoutParams.y
        }

        try {
            hostWindowManager?.updateViewLayout(overlayRoot, layoutParams)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        closeDetails()
        overlayRoot?.post {
            overlayRoot?.requestApplyInsets()
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
