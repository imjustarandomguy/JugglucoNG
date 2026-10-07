package tk.glucodata.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.view.Gravity
import android.view.Surface
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.runtime.PausableMonotonicFrameClock
import androidx.compose.runtime.Recomposer
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.core.os.HandlerCompat
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
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import tk.glucodata.CurrentDisplaySource
import tk.glucodata.CurrentGlucoseSource
import tk.glucodata.HistoryRepositoryAccess
import tk.glucodata.LiveReadingLanes
import tk.glucodata.Notify
import tk.glucodata.SensorIdentity
import tk.glucodata.SensorSourceResolver
import tk.glucodata.UiRefreshBus
import tk.glucodata.data.GlucoseRepository
import tk.glucodata.data.settings.FloatingSettingsRepository
import tk.glucodata.ui.overlay.FloatingDetailsCard
import tk.glucodata.ui.overlay.FloatingDetailsCardWidth
import tk.glucodata.ui.overlay.FloatingDetailsRequest
import tk.glucodata.ui.overlay.FloatingGlucoseOverlay
import tk.glucodata.ui.overlay.FloatingNextReading
import tk.glucodata.ui.overlay.FloatingPillReading
import tk.glucodata.ui.GlucosePoint
import tk.glucodata.Natives

class FloatingGlucoseService : Service(), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
    private companion object {
        private const val LOG_ID = "FloatingGlucose"
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

    private val presence = FloatingPillPresence()
    // The pill's readings, followed only while the screen is on; see loadHistory.
    private val history = MutableStateFlow<List<GlucosePoint>>(emptyList())
    private var historyJob: Job? = null
    // Whether the readings arrived since the screen came on; see resolveReadings.
    private var readingsArrived = false

    // The reading the pill shows, resolved here from the readings and the live value;
    // see resolvePillReading. Asked for on each change of either, and by the self-check.
    private val reading = MutableStateFlow(FloatingPillReading.NONE)
    private val resolveRequests = Channel<Unit>(Channel.CONFLATED)
    private var resolvedInputs: FloatingPillWatchdog.ResolveInputs? = null
    // The resolver, the refresh bus and the self-check: like the readings, screen on only.
    private var screenJob: Job? = null

    // The pill's check on itself; see FloatingPillWatchdog and checkPill.
    private val watchdog = FloatingPillWatchdog()
    // The revision of the reading the pill last drew; written from its draw pass.
    private var drawnRevision = -1L
    private val frameRequests = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    // The pill's window is removed while the screen is off and when it changes host,
    // but its composition stays, so that it comes back with its settings instead of a
    // first frame of defaults. It therefore runs on this recomposer rather than its
    // window's, which ends with the window, and its frames pause while the screen is off.
    // They come from FloatingPillFrameClock, not Compose's vsync-only clock, so that the
    // composition keeps up with no window and in the background; see there.
    private lateinit var pillFrameClock: PausableMonotonicFrameClock
    private lateinit var pillRecomposer: Recomposer

    // At screen on the pill's window goes back in only once its composition holds the
    // reading handed to it (see FloatingPillPresence): asked from the composition's apply
    // pass and done after it, or COMPOSE_TIMEOUT_MS after the readings at the latest.
    private val updatePillWindowLater = Runnable { updatePillWindow() }
    private var composeTimeoutPosted = false
    private val composeTimedOut = Runnable {
        composeTimeoutPosted = false
        presence.onComposeTimedOut()
        updatePillWindow()
    }
    // When the screen came on (uptime) until the pill's window is back, for its log line.
    private var screenOnAt = 0L

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        settingsRepository = FloatingSettingsRepository(this)
        glucoseRepository.refreshSensorSerial()

        val ui = AndroidUiDispatcher.Main
        pillFrameClock = PausableMonotonicFrameClock(
            FloatingPillFrameClock(Choreographer.getInstance(), HandlerCompat.createAsync(Looper.getMainLooper()))
        ).apply { pause() }
        pillRecomposer = Recomposer(ui + pillFrameClock)
        serviceScope.launch(ui + pillFrameClock) { pillRecomposer.runRecomposeAndApplyChanges() }

        setupOverlay()
        androidx.core.content.ContextCompat.registerReceiver(
            this,
            screenStateReceiver,
            android.content.IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            },
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        updateForScreen()
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
    // Where the pill goes: the app's WindowManager or FloatingAccessibilityService's; see attachToHost.
    private var pillHost: WindowManager? = null
    // The WindowManager the pill is attached through, or null while it is off screen.
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
            // Compose looks the view-tree owners up from the window's rootView,
            // which is this container now — the owners have to live here.
            setViewTreeLifecycleOwner(this@FloatingGlucoseService)
            setViewTreeViewModelStoreOwner(this@FloatingGlucoseService)
            setViewTreeSavedStateRegistryOwner(this@FloatingGlucoseService)
        }
        overlayRoot = root

        composeView = ComposeView(this).apply {
            setParentCompositionContext(pillRecomposer)
            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnLifecycleDestroyed(this@FloatingGlucoseService)
            )
            setContent {
                FloatingGlucoseOverlay(
                    repository = settingsRepository,
                    historyFlow = history,
                    readingFlow = reading,
                    frameRequests = frameRequests,
                    onReadingDrawn = { drawnRevision = it },
                    onReadingComposed = { onPillComposed(it) },
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

        // updatePillWindow adds it once the settings and readings are known.
        // Placed again once it has its size, for the edge limits.
        root.doOnLayout { applyOverlayPlacement() }
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
     * Puts the pill through [host] from now on: FloatingAccessibilityService's
     * WindowManager to draw it over the status bar (app overlays sit under the
     * status bar window, which takes every touch in its strip), else the app's.
     */
    private fun attachToHost(host: WindowManager?) {
        if (host == null || host === pillHost) return
        pillHost = host
        detachPill()
        updatePillWindow()
    }

    /** Adds or removes the pill as [presence] says. */
    private fun updatePillWindow() {
        if (presence.awaitingComposition) {
            if (!composeTimeoutPosted) {
                composeTimeoutPosted = true
                mainHandler.postDelayed(composeTimedOut, FloatingPillPresence.COMPOSE_TIMEOUT_MS)
            }
        } else if (composeTimeoutPosted) {
            composeTimeoutPosted = false
            mainHandler.removeCallbacks(composeTimedOut)
        }
        if (presence.shown) {
            attachPill()
            if (screenOnAt != 0L && hostWindowManager != null) logScreenOnWindow()
        } else {
            detachPill()
        }
    }

    /** From the pill's composition, each time one of its recompositions has been applied. */
    private fun onPillComposed(revision: Long) {
        val awaiting = presence.awaitingComposition
        presence.onReadingComposed(revision)
        // After the apply pass, not within it: the window's attach measures the composition.
        if (awaiting && presence.shown) {
            mainHandler.removeCallbacks(updatePillWindowLater)
            mainHandler.post(updatePillWindowLater)
        }
    }

    /**
     * The one line per screen on, as the pill's window goes back in: the reading handed
     * to the pill, and whether its composition held that one before the window's first
     * frame ("composed"), or the window had to go in without it ("NOT composed").
     */
    private fun logScreenOnWindow() {
        val handed = reading.value
        val composed = presence.composedRevision
        val after = SystemClock.uptimeMillis() - screenOnAt
        screenOnAt = 0L
        val time = if (handed.readingTime > 0L) {
            android.text.format.DateFormat.format("HH:mm:ss", handed.readingTime)
        } else {
            "none"
        }
        val value = handed.snapshot?.primaryStr ?: handed.point?.value?.toString() ?: "---"
        val state = when {
            composed == null -> "first composition"
            composed >= handed.revision -> "composed before its first frame"
            else -> "NOT composed before its first frame (composed rev $composed)"
        }
        tk.glucodata.Log.i(
            LOG_ID,
            "screen on: pill window added after $after ms with reading $time $value rev ${handed.revision}, $state",
        )
    }

    /** Adds the pill through [pillHost], or as an app overlay if that host refuses it. */
    private fun attachPill() {
        val root = overlayRoot ?: return
        val app = windowManager ?: return
        val host = pillHost ?: return
        if (hostWindowManager != null) return
        if (!addOverlay(host, root) && host !== app) addOverlay(app, root)
    }

    /** Removes the pill's window; its composition stays, see pillRecomposer. */
    private fun detachPill() {
        val root = overlayRoot ?: return
        val host = hostWindowManager ?: return
        closeDetails()
        hostWindowManager = null
        try {
            host.removeViewImmediate(root)
        } catch (e: Exception) {
            // Not attached; nothing to remove.
        }
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

    /**
     * While the screen is off the pill's window is removed (an accessibility overlay
     * would also draw on the always-on display, where a fixed pill burns in), its
     * frames are paused and its readings are not followed: nothing runs for it, the
     * self-check included. When the screen comes on, its frames resume, the readings
     * are loaded again, and the pill goes back in a new window once they are in and
     * resolved, and its composition holds the reading resolved from them, so the new
     * window's first frame shows it; see FloatingPillPresence. The lock screen keeps it.
     */
    private fun updateForScreen() {
        val power = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        val turnedOn = presence.onScreen(power.isInteractive)
        // Set from the state, not only on its changes, so the frames can never stay
        // paused under a pill on screen.
        if (presence.screenOn) pillFrameClock.resume() else pillFrameClock.pause()
        if (turnedOn) screenOnAt = SystemClock.uptimeMillis() else if (!presence.screenOn) screenOnAt = 0L
        if (turnedOn) {
            readingsArrived = false
            loadHistory()
            followReadings()
        } else if (!presence.screenOn) {
            historyJob?.cancel()
            historyJob = null
            screenJob?.cancel()
            screenJob = null
        }
        updatePillWindow()
    }

    /** Follows the readings, from a window that starts now, until the screen goes off. */
    private fun loadHistory() {
        historyJob?.cancel()
        historyJob = serviceScope.launch {
            glucoseRepository.getHistoryFlow(
                System.currentTimeMillis() - FLOATING_HISTORY_WINDOW_MS,
                Natives.getunit() == 1
            ).collect { points ->
                history.value = points
                readingsArrived = true
                resolveRequests.trySend(Unit)
            }
        }
    }

    /**
     * Until the screen goes off: resolves the pill's reading whenever it may have
     * changed, and has the pill check itself; see checkPill.
     */
    private fun followReadings() {
        screenJob?.cancel()
        screenJob = serviceScope.launch {
            launch { resolveReadings() }
            // Every reading path ends in a refresh request: a new live value, a reading
            // stored from the watch, a calibration. The current value is skipped: the
            // readings, loading now, ask for the first resolution.
            launch { UiRefreshBus.revision.drop(1).collect { resolveRequests.trySend(Unit) } }
            launch { checkPillPeriodically() }
        }
    }

    /**
     * Resolves the pill's reading on each request, one at a time, off the main thread.
     * The pill goes on screen after the first resolution from the readings loaded since
     * the screen came on, once it has composed it, so its first frame already shows them.
     */
    private suspend fun resolveReadings() {
        while (true) {
            resolveRequests.receive()
            val points = history.value
            val loaded = readingsArrived
            val (resolved, inputs) = withContext(Dispatchers.IO) { resolvePillReading(points) }
            val current = reading.value
            if (resolved.copy(revision = current.revision) != current) {
                reading.value = resolved.copy(revision = current.revision + 1)
            }
            resolvedInputs = inputs
            if (loaded) {
                presence.onReadingsLoaded(reading.value.revision)
                updatePillWindow()
            }
        }
    }

    /**
     * The newest reading and the current value as the notification resolves it,
     * except while the live source still holds an older reading than one stored
     * since: then the stored one, resolved as it will be once current, instead of the
     * older live one for the minutes until that expires. With the sensor's reading
     * interval, for the time to the next reading, from its kind or the readings' spacing.
     */
    private fun resolvePillReading(
        points: List<GlucosePoint>,
    ): Pair<FloatingPillReading, FloatingPillWatchdog.ResolveInputs> {
        val sensorId = SensorIdentity.resolveMainSensor()
        val newest = points.lastOrNull()
        val liveTime = liveReadingTime(sensorId)
        val snapshot = runCatching {
            if (newest != null && FloatingPillWatchdog.storeIsAheadOfLive(liveTime, newest.timestamp)) {
                CurrentDisplaySource.resolveIncomingReading(
                    reading = LiveReadingLanes.stock(newest.value, newest.rawValue),
                    rate = Float.NaN,
                    targetTimeMillis = newest.timestamp,
                    preferredSensorId = sensorId,
                    source = "history",
                )
            } else {
                CurrentDisplaySource.resolveCurrent(Notify.glucosetimeout, sensorId)
            }
        }.getOrNull()
        val sensorKind = runCatching {
            SensorSourceResolver.resolveSensorKind(sensorId, SensorSourceResolver.SENSOR_KIND_UNKNOWN)
        }.getOrDefault(SensorSourceResolver.SENSOR_KIND_UNKNOWN)
        val interval = FloatingNextReading.intervalMillis(
            sensorKind = sensorKind,
            readingTimes = points.takeLast(FloatingNextReading.SPACING_READINGS).map { it.timestamp },
        )
        return FloatingPillReading(
            point = newest,
            snapshot = snapshot,
            sensorId = sensorId,
            intervalMillis = interval,
        ) to FloatingPillWatchdog.ResolveInputs(sensorId, liveTime)
    }

    /** Time of the live reading the current value is resolved with, or 0 when there is none. */
    private fun liveReadingTime(sensorId: String?): Long =
        runCatching { CurrentGlucoseSource.getFresh(Notify.glucosetimeout, sensorId)?.timeMillis }
            .getOrNull() ?: 0L

    /**
     * While the screen is on: checks the pill every CHECK_INTERVAL_MS, and VERIFY_DELAY_MS
     * after each new reading it is handed and after each repair, so a reading that does
     * not reach the screen is noticed at once and a missed event is caught within the
     * interval. Plain delays on the main looper: they never wake the device, and the
     * screen-off path cancels them with the rest.
     */
    private suspend fun checkPillPeriodically() {
        var verified = reading.value.revision
        var wait = FloatingPillWatchdog.CHECK_INTERVAL_MS
        while (true) {
            val handed = withTimeoutOrNull(wait) { reading.first { it.revision != verified } }
            if (handed != null) {
                verified = handed.revision
                delay(FloatingPillWatchdog.VERIFY_DELAY_MS)
            }
            wait = if (checkPill()) {
                FloatingPillWatchdog.VERIFY_DELAY_MS
            } else {
                FloatingPillWatchdog.CHECK_INTERVAL_MS
            }
        }
    }

    /** One check of the pill; true when something was repaired, to look again soon. */
    private suspend fun checkPill(): Boolean {
        // A missed SCREEN_OFF would otherwise keep the pill and this check going.
        val power = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        if (power.isInteractive != presence.screenOn) updateForScreen()
        if (!presence.shown || hostWindowManager == null) return false

        val serial = glucoseRepository.currentSerial.value
        val storedNewest = if (serial.isBlank()) 0L else withContext(Dispatchers.IO) {
            HistoryRepositoryAccess.getLatestTimestampForSensorBlocking(serial)
        }
        val sensorId = SensorIdentity.resolveMainSensor()
        val action = watchdog.check(
            storedNewest = storedNewest,
            loadedNewest = history.value.lastOrNull()?.timestamp ?: 0L,
            inputsNow = FloatingPillWatchdog.ResolveInputs(sensorId, liveReadingTime(sensorId)),
            inputsResolved = resolvedInputs,
            handed = reading.value.revision,
            drawn = drawnRevision,
        )
        if (action != FloatingPillWatchdog.Action.NONE) {
            tk.glucodata.Log.i(LOG_ID, "pill behind: $action")
        }
        when (action) {
            FloatingPillWatchdog.Action.NONE -> return false
            // Its emission asks for a resolution.
            FloatingPillWatchdog.Action.RELOAD -> loadHistory()
            FloatingPillWatchdog.Action.RESOLVE -> resolveRequests.trySend(Unit)
            FloatingPillWatchdog.Action.REDRAW -> redrawPill()
            FloatingPillWatchdog.Action.REATTACH -> {
                detachPill()
                attachPill()
            }
        }
        return true
    }

    /**
     * What a tap does to the pill, without the tap: pending state changes are applied
     * and its recomposer runs a frame, then its window redraws from the root down (an
     * invalidation from the root also redraws a child whose own was lost).
     */
    private fun redrawPill() {
        Snapshot.sendApplyNotifications()
        frameRequests.tryEmit(Unit)
        val root = overlayRoot ?: return
        invalidateTree(root)
        root.requestLayout()
    }

    private fun invalidateTree(view: View) {
        view.invalidate()
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) invalidateTree(view.getChildAt(i))
        }
    }

    private val screenStateReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateForScreen()
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
        unregisterReceiver(screenStateReceiver)
        closeDetails()
        mainHandler.removeCallbacks(updatePillWindowLater)
        mainHandler.removeCallbacks(composeTimedOut)
        // The id is shared with keeprunning's glucose notification; detach it while keeprunning still holds it.
        val held = tk.glucodata.Notify.keeprunningHoldsGlucoseNotification()
        stopForeground(if (held) STOP_FOREGROUND_DETACH else STOP_FOREGROUND_REMOVE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
        pillRecomposer.cancel()
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
