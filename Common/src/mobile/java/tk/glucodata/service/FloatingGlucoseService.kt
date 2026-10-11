package tk.glucodata.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Point
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.core.os.HandlerCompat
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import tk.glucodata.CurrentReadingHub
import tk.glucodata.DisplayedReading
import tk.glucodata.ui.optOutOfForceDark
import tk.glucodata.data.settings.FloatingSettingsRepository
import tk.glucodata.ui.overlay.FloatingDetailsCard
import tk.glucodata.ui.overlay.FloatingDetailsCardWidth
import tk.glucodata.ui.overlay.FloatingGlucoseOverlay
import tk.glucodata.ui.overlay.OverlayPosition
import tk.glucodata.ui.overlay.OverlayScreenArea
import tk.glucodata.ui.overlay.clampOverlayPixels
import tk.glucodata.ui.overlay.overlayPixels
import tk.glucodata.ui.overlay.overlayPosition
import tk.glucodata.ui.overlay.FloatingNextReading
import tk.glucodata.ui.overlay.FloatingOpenAppActivity
import tk.glucodata.ui.overlay.FloatingDetailsCardState

class FloatingGlucoseService : Service(), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
    private companion object {
        private const val LOG_ID = "FloatingGlucose"
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
    // The WindowManager the card went in through: the pill's; see FloatingDetailsWindow.
    private var detailsHost: WindowManager? = null
    // Event times (uptime): of the touch outside the card that last closed it, and of the
    // last touch down on the pill; see FloatingDetailsWindow.closedByThisTap.
    private var detailsClosedByTouchAt = Long.MIN_VALUE
    private var pillTouchDownAt = 0L
    // The card's background opacity, followed from the settings: a card opens with it, and follows it.
    private val detailsOpacity = MutableStateFlow(FloatingSettingsRepository.DEFAULT_DETAILS_OPACITY)
    // Whether the card is open; while it is, the card's clock keeps its age going.
    private val detailsShown = MutableStateFlow(false)
    // The card's clock, for the age it shows; see runCardAgeClock.
    private val cardNow = MutableStateFlow(System.currentTimeMillis())
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private lateinit var settingsRepository: FloatingSettingsRepository

    private val presence = FloatingPillPresence()

    // The reading the pill and the card show: the device's current reading, as
    // CurrentReadingHub publishes it, handed on while the screen is on; see followHub.
    private val reading = MutableStateFlow(DisplayedReading.NONE)
    private var screenJob: Job? = null

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
    // pass and done after it, or COMPOSE_TIMEOUT_MS after the handing at the latest.
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

        val ui = AndroidUiDispatcher.Main
        pillFrameClock = PausableMonotonicFrameClock(
            FloatingPillFrameClock(Choreographer.getInstance(), HandlerCompat.createAsync(Looper.getMainLooper()))
        ).apply { pause() }
        pillRecomposer = Recomposer(ui + pillFrameClock)
        serviceScope.launch(ui + pillFrameClock) { pillRecomposer.runRecomposeAndApplyChanges() }
        serviceScope.launch { runCardAgeClock() }

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
    // Freeform placement is kept screen-relative and resolved against the current
    // screen area on every placement; see OverlayPosition.
    private var freeformPosition = OverlayPosition.CENTRE
    // Pixel position from before #685, converted once the overlay has a size.
    private var legacyPixelPosition: Pair<Int, Int>? = null
    private var screenArea: OverlayScreenArea? = null
    private var dragging = false
    // Where the pill goes: the app's WindowManager or FloatingAccessibilityService's; see attachToHost.
    private var pillHost: WindowManager? = null
    // The WindowManager the pill is attached through, or null while it is off screen.
    private var hostWindowManager: WindowManager? = null

    private fun setupOverlay() {
        if (composeView != null) return

        val root = CutoutAwareContainer(
            this,
            onTouchDown = { pillTouchDownAt = it },
        ) { v, insets ->
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
            // Compose looks the view-tree owners up from the window's rootView,
            // which is this container now — the owners have to live here.
            setViewTreeLifecycleOwner(this@FloatingGlucoseService)
            setViewTreeViewModelStoreOwner(this@FloatingGlucoseService)
            setViewTreeSavedStateRegistryOwner(this@FloatingGlucoseService)
        }
        overlayRoot = root
        root.optOutOfForceDark()

        composeView = ComposeView(this).apply {
            setParentCompositionContext(pillRecomposer)
            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnLifecycleDestroyed(this@FloatingGlucoseService)
            )
            setContent {
                FloatingGlucoseOverlay(
                    repository = settingsRepository,
                    readingFlow = reading,
                    onReadingComposed = { onPillComposed(it) },
                    onUpdatePosition = { x, y -> updateViewPosition(x, y) },
                    onDragFinished = { persistViewPosition() },
                    cutoutDataFlow = cutoutData,
                    onToggleDetails = { toggleDetails() },
                    onTapNotOpened = { tapNotOpened(it) },
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

        // updatePillWindow adds it once the settings and the reading are known.
        // The layout listener places it again once it has its size.
    }
    
    private fun toggleDetails() {
        if (detailsRoot != null) {
            closeDetails()
            tapNotOpened("the card was open, closed it")
            return
        }
        if (FloatingDetailsWindow.closedByThisTap(detailsClosedByTouchAt, pillTouchDownAt)) {
            tapNotOpened("its touch closed the card")
            return
        }
        openDetails()
    }

    /** The trace line for a tap on the pill that does not open the details card: why not. */
    private fun tapNotOpened(reason: String) {
        tk.glucodata.Log.i(LOG_ID, "tap: $reason")
    }

    /**
     * Opens the card beside the pill, on the side with room: away from the edge
     * an island is docked to; for the free pill below it in the top half of the
     * screen and above it in the bottom half, aligned to its nearer side edge.
     * It goes in beside the pill, through the pill's WindowManager and as its window
     * type, so it shows wherever the pill does (FloatingDetailsWindow): over the status
     * bar and the lock screen too, for the pill drawn there. It shows the pill's reading,
     * and follows it while open.
     */
    private fun openDetails() {
        val app = windowManager ?: return tapNotOpened("no window manager")
        // The pill is off screen: there is nothing to open the card beside.
        val host = hostWindowManager ?: return tapNotOpened("the pill has no window")
        val anchor = overlayRoot ?: return tapNotOpened("the pill has no view")
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
            FloatingDetailsWindow.type(layoutParams.type),
            // Outside touches still reach the app below; the card only closes on them.
            FloatingDetailsWindow.FLAGS,
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

        val root = OutsideTouchContainer(this) { touchAt ->
            detailsClosedByTouchAt = touchAt
            closeDetails()
        }.apply {
            setViewTreeLifecycleOwner(this@FloatingGlucoseService)
            setViewTreeViewModelStoreOwner(this@FloatingGlucoseService)
            setViewTreeSavedStateRegistryOwner(this@FloatingGlucoseService)
        }
        root.optOutOfForceDark()
        val card = ComposeView(this).apply {
            setContent {
                val opacity by detailsOpacity.collectAsState()
                FloatingDetailsCard(
                    readingFlow = reading,
                    nowFlow = cardNow,
                    isDark = androidx.compose.foundation.isSystemInDarkTheme(),
                    onOpenApp = {
                        closeDetails()
                        openApp()
                    },
                    backgroundOpacity = opacity,
                )
            }
        }
        root.addView(
            card,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT)
        )
        // As the pill does: an app overlay if the accessibility host refuses it.
        // No timeout: the card stays until a tap outside it, the pill, or screen off closes it.
        val added = addDetails(host, root, params) || (
            host !== app && addDetails(app, root, params.apply {
                type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                token = null
            })
        )
        if (added) tk.glucodata.Log.i(LOG_ID, "tap: card opened as ${windowTypeName(params.type)}")
    }

    /** Adds the card's window; a refusal is logged, for the tap that then opens nothing. */
    private fun addDetails(host: WindowManager, root: View, params: WindowManager.LayoutParams): Boolean =
        try {
            host.addView(root, params)
            detailsRoot = root
            detailsHost = host
            detailsShown.value = true
            true
        } catch (e: Exception) {
            tk.glucodata.Log.i(LOG_ID, "tap: card not added as ${windowTypeName(params.type)}: $e")
            false
        }

    private fun windowTypeName(type: Int): String =
        if (type == WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY) "accessibility overlay" else "app overlay"

    private fun closeDetails() {
        val root = detailsRoot ?: return
        val host = detailsHost
        detailsRoot = null
        detailsHost = null
        detailsShown.value = false
        try {
            host?.removeView(root)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** At once, or after the user unlocks while the keyguard is up; see FloatingOpenAppActivity. */
    private fun openApp() {
        FloatingOpenAppActivity.open(this)
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
            settingsRepository.detailsOpacity.collect { detailsOpacity.value = it }
        }

        serviceScope.launch {
            settingsRepository.isEnabled.collectLatest { enabled ->
                if (!enabled) {
                    FloatingAccessibilityService.syncAvailability(this@FloatingGlucoseService)
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
                // This service runs only while floating glucose is on.
                val wanted = FloatingAccessibilityAvailability.wanted(enabled = true, tapShowsDetails, aboveStatusBar)
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
        val time = if (handed.timeMillis > 0L) {
            android.text.format.DateFormat.format("HH:mm:ss", handed.timeMillis)
        } else {
            "none"
        }
        val state = when {
            composed == null -> "first composition"
            composed >= handed.revision -> "composed before its first frame"
            else -> "NOT composed before its first frame (composed rev $composed)"
        }
        tk.glucodata.Log.i(
            LOG_ID,
            "screen on: pill window added after $after ms with reading $time ${handed.shownText} " +
                "${handed.freshness} rev ${handed.revision}, $state",
        )
    }

    /** Adds the pill through [pillHost], or as an app overlay if that host refuses it. */
    private fun attachPill() {
        val root = overlayRoot ?: return
        val app = windowManager ?: return
        val host = pillHost ?: return
        if (hostWindowManager != null) return
        forceLayoutTree(root)
        if (!addOverlay(host, root) && host !== app) addOverlay(app, root)
    }

    /**
     * Has every view of the pill measured afresh at its new window's first layout, as a
     * rotation does (a window's resize or new configuration forces the layout of every view
     * in it). Without this a pill that grew while off screen came back clipped on one side
     * until a rotation, or a later reading of another width.
     *
     * Its composition goes on without a window: at screen on it composes the new reading,
     * 9.8 to 10.2 say, before the window goes back. With no window the composition asks
     * the views for no layout, so the new window took their measures from the views' cache,
     * as wide as the last window, and the composition's root kept the width it was last
     * allowed: its last window's, from that window's first layout. The wider pill was laid
     * out past that width on both sides, and as its root's size had not changed, nothing
     * asked for a wider window.
     */
    private fun forceLayoutTree(view: View) {
        view.forceLayout()
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) forceLayoutTree(view.getChildAt(i))
        }
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
     * frames are paused and it does not follow the current reading: nothing runs for it.
     * When the screen comes on, its frames resume, it is handed the current reading
     * again, and it goes back in a new window once its composition holds that reading,
     * so the new window's first frame shows it; see FloatingPillPresence. The lock screen
     * keeps it. The details card closes with the screen, never to come back without a tap.
     */
    private fun updateForScreen() {
        val power = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        val turnedOn = presence.onScreen(power.isInteractive)
        // Set from the state, not only on its changes, so the frames can never stay
        // paused under a pill on screen.
        if (presence.screenOn) pillFrameClock.resume() else pillFrameClock.pause()
        if (turnedOn) screenOnAt = SystemClock.uptimeMillis() else if (!presence.screenOn) screenOnAt = 0L
        if (turnedOn) {
            followHub()
        } else if (!presence.screenOn) {
            // detachPill closes it with the pill's window; this, even with no pill window in.
            closeDetails()
            screenJob?.cancel()
            screenJob = null
        }
        updatePillWindow()
    }

    /**
     * Until the screen goes off: hands the pill each reading CurrentReadingHub publishes,
     * a new one, the same one gone stale or taken down, or another display setting, as it
     * is published. The pill draws what it is handed and keeps no clock of its own, so it
     * changes with the notification and the other surfaces, never at another moment.
     */
    private fun followHub() {
        screenJob?.cancel()
        screenJob = serviceScope.launch {
            handReading(readingAtScreenOn())
            CurrentReadingHub.readings.collect { handReading(it) }
        }
    }

    /**
     * The hub's reading for the pill's first frame at screen on. The hub's clock may have
     * slept through a deadline with the phone, or the hub may not have published yet:
     * the pill then waits, a moment at most, for the publish that brings the reading up
     * to date rather than show one already stale; see FloatingPillPresence.waitsForHub.
     */
    private suspend fun readingAtScreenOn(): DisplayedReading {
        val shown = CurrentReadingHub.current()
        if (!FloatingPillPresence.waitsForHub(shown, System.currentTimeMillis())) return shown
        if (shown.revision > 0L) CurrentReadingHub.onClockChanged()
        return withTimeoutOrNull(FloatingPillPresence.COMPOSE_TIMEOUT_MS) {
            CurrentReadingHub.readings.first { it.revision != shown.revision }
        } ?: CurrentReadingHub.current()
    }

    private fun handReading(shown: DisplayedReading) {
        reading.value = shown
        // The card follows the reading; with none left it has nothing to show.
        if (shown.timeMillis <= 0L) closeDetails()
        presence.onReadingHanded(shown.revision)
        updatePillWindow()
    }

    /**
     * The details card's clock, for the age it shows: set when the card opens and at each
     * minute of the reading's age while it stays open, and not at all while it is closed.
     * Whether the reading is stale is the hub's to say, not this clock's.
     */
    private suspend fun runCardAgeClock() {
        combine(reading.map { it.timeMillis }.distinctUntilChanged(), detailsShown) { time, shown -> time to shown }
            .collectLatest { (time, shown) ->
                if (!shown) return@collectLatest
                while (true) {
                    val now = System.currentTimeMillis()
                    cardNow.value = now
                    if (time <= 0L) break
                    delay(FloatingDetailsCardState.nextAgeTickDelay(time, now))
                }
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
        /** With the event time of each touch down on the pill. */
        private val onTouchDown: (Long) -> Unit,
        private val onInsets: (View, WindowInsets) -> Unit
    ) : FrameLayout(context) {
        override fun dispatchApplyWindowInsets(insets: WindowInsets): WindowInsets {
            onInsets(this, insets)
            return super.dispatchApplyWindowInsets(insets)
        }

        override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
            if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN) onTouchDown(event.eventTime)
            return super.dispatchTouchEvent(event)
        }
    }

    /** Root of the details card's window: reports touches that land outside it, with their event time. */
    private class OutsideTouchContainer(
        context: Context,
        private val onOutsideTouch: (Long) -> Unit
    ) : FrameLayout(context) {
        override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
            if (event.actionMasked == android.view.MotionEvent.ACTION_OUTSIDE) {
                onOutsideTouch(event.eventTime)
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
