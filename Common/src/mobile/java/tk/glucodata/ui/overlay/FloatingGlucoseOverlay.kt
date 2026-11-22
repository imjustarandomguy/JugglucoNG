package tk.glucodata.ui.overlay

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import android.view.MotionEvent
import android.content.Intent
import android.graphics.Typeface // Added for Google Sans
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.graphics.Shape
import tk.glucodata.service.FloatingGlucoseService.CutoutData
import tk.glucodata.service.FloatingGlucoseService.CutoutEdge
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.emptyFlow
import kotlin.math.roundToInt
import tk.glucodata.data.settings.FloatingSettingsRepository
import tk.glucodata.ui.theme.MainFontFile
import tk.glucodata.ui.GlucosePoint
import tk.glucodata.ui.GlucosePaletteState
import tk.glucodata.ui.components.TrendIndicator
import tk.glucodata.logic.TrendEngine
import tk.glucodata.data.calibration.CalibrationManager
import tk.glucodata.ui.getDisplayValues
import tk.glucodata.CurrentDisplaySource
import tk.glucodata.DisplayDataState
import tk.glucodata.GlucoseValueTone
import tk.glucodata.Natives
import tk.glucodata.Notify
import tk.glucodata.SensorIdentity
import tk.glucodata.UiRefreshBus
import tk.glucodata.ui.PeerCurrentReading
import tk.glucodata.ui.SecondarySensorDisplay

/**
 * A stale reading on the pill: its last value stays, dimmed, beside the next-reading
 * ring's amber; "---" is only for no value at all. Pure, see the tests.
 */
internal object FloatingStaleValue {
    /** Opacity of a stale value against a current one: clearly old, still readable. */
    const val DIMMED_ALPHA = 0.5f

    /**
     * The opacity factor for the value, its trend arrow and its secondary value:
     * [DIMMED_ALPHA] while there is a value and it is [stale], the same flag the
     * next-reading ring is late by; 1 otherwise.
     */
    fun alpha(hasValue: Boolean, stale: Boolean): Float = if (hasValue && stale) DIMMED_ALPHA else 1f
}

/** Width of the details card; the service places its window by it. */
internal val FloatingDetailsCardWidth = 260.dp

/**
 * The reading the pill shows, resolved by FloatingGlucoseService: the newest of the
 * readings it follows, and the current value as the notification resolves it,
 * with the selected peer sensors' current readings for the secondary slot
 * ([peers], see SecondarySensorDisplay.fallback; empty unless it can use them).
 *
 * The pill used to resolve the current value itself, in a remember keyed on the
 * readings and the refresh revision. The resolution reads the live reading, which
 * Compose does not observe, so a change that moved none of the keys (the live reading
 * expiring, a value revised under the same time) left the pill on the old one.
 *
 * [revision] changes with every new reading, so the pill can say which one it drew.
 * [intervalMillis] is the sensor's reading interval, 0 when not known; see
 * FloatingNextReading.intervalMillis.
 */
data class FloatingPillReading(
    val point: GlucosePoint?,
    val snapshot: CurrentDisplaySource.Snapshot?,
    val sensorId: String?,
    val revision: Long = 0L,
    val intervalMillis: Long = 0L,
    val peers: List<PeerCurrentReading> = emptyList(),
) {
    /**
     * Time of the reading shown: the value's own (the live reading may be newer than the
     * stored one), else the stored one's. The time to the next reading counts from it.
     */
    val readingTime: Long
        get() = maxOf(snapshot?.timeMillis ?: 0L, point?.timestamp ?: 0L)

    companion object {
        val NONE = FloatingPillReading(point = null, snapshot = null, sensorId = null)
    }
}

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun FloatingGlucoseOverlay(
    repository: FloatingSettingsRepository,
    /** The readings; a StateFlow, so a new composition starts from the loaded ones. */
    historyFlow: StateFlow<List<GlucosePoint>>,
    /** The reading to show, resolved by the service. */
    readingFlow: StateFlow<FloatingPillReading>,
    /** The service's freshness clock, shared with the details card; see nextOverlayFreshnessCheckDelay. */
    freshnessNowFlow: StateFlow<Long>,
    /** Each item asks for a frame of the pill's recomposer, as a tap does; see FloatingPillWatchdog. */
    frameRequests: Flow<Unit> = emptyFlow(),
    /** Called with the [FloatingPillReading.revision] the pill draws, each time it draws. */
    onReadingDrawn: (Long) -> Unit = {},
    /**
     * Called with the [FloatingPillReading.revision] the pill composed, after each of its
     * recompositions is applied; the service puts its window back at screen on only once
     * the handed one is, see FloatingPillPresence.
     */
    onReadingComposed: (Long) -> Unit = {},
    onUpdatePosition: (Int, Int) -> Unit,
    onDragFinished: () -> Unit,
    cutoutDataFlow: Flow<tk.glucodata.service.FloatingGlucoseService.CutoutData>,
    /** Opens or closes the details card on a tap ("Details on tap"); otherwise a tap opens the app. */
    onToggleDetails: (() -> Unit)? = null,
    /** With "Details on tap" on: called, with why, for a tap that does not reach [onToggleDetails]. */
    onTapNotOpened: (String) -> Unit = {},
    /** Opens the app (a tap with "Details on tap" off, or a long press on the island). */
    onOpenApp: () -> Unit,
) {
    val context = LocalContext.current

    // Settings State
    val tapShowsDetails by repository.tapShowsDetails.collectAsState(initial = true)
    val isTransparent by repository.isTransparent.collectAsState(initial = false)
    val showSecondary by repository.showSecondary.collectAsState(initial = false)
    val fontSource by repository.fontSource.collectAsState(initial = "APP")
    val fontSize by repository.fontSize.collectAsState(initial = FloatingSettingsRepository.DEFAULT_FONT_SIZE)
    val fontWeightSetting by repository.fontWeight.collectAsState(initial = "REGULAR")
    val showDelta by repository.showDelta.collectAsState(initial = false)
    val deltaIntervalMinutes by repository.deltaIntervalMinutes.collectAsState(initial = tk.glucodata.GlucoseDelta.DEFAULT_INTERVAL_MINUTES)
    val showArrow by repository.showArrow.collectAsState(initial = true)
    val cornerRadius by repository.cornerRadius.collectAsState(initial = 28f)
    val opacity by repository.backgroundOpacity.collectAsState(initial = FloatingSettingsRepository.DEFAULT_BACKGROUND_OPACITY)
    val valueRangeColors by repository.valueRangeColors.collectAsState(initial = false)
    val isDynamicIsland by repository.isDynamicIslandEnabled.collectAsState(initial = false)
    val islandNudge by repository.islandNudge.collectAsState(initial = FloatingSettingsRepository.DEFAULT_ISLAND_NUDGE)
    val manualGap by repository.islandGap.collectAsState(initial = 0f)
    val useSubtleOutline by repository.useSubtleOutline.collectAsState(initial = false)
    val showNextReading by repository.showNextReading.collectAsState(initial = true)
    val layoutOrder by repository.layoutOrder.collectAsState(initial = FloatingLayoutOrder.DEFAULT)

    // Metrics State (from Service WindowInsets)
    val cutoutData by cutoutDataFlow.collectAsState(initial = CutoutData(0.dp, CutoutEdge.NONE))
    val cutoutEdge = cutoutData.edge
    val cutoutSize = cutoutData.size

    // Data State: History List
    val history by historyFlow.collectAsState()
    val reading by readingFlow.collectAsState()
    val refreshRevision by UiRefreshBus.revision.collectAsState(initial = 0L)

    // Derived Data
    val glucosePoint = reading.point
    val currentSensorId = reading.sensorId
    val currentSnapshot = reading.snapshot
    val peerReadings = reading.peers
    val rawDeltaText by produceState("", showDelta, currentSnapshot, deltaIntervalMinutes, refreshRevision) {
        value = if (!showDelta || currentSnapshot == null) "" else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            tk.glucodata.DisplayDeltaSource.resolve(true, currentSnapshot, deltaIntervalMinutes)
        }
    }
    val sensorHistory = remember(history, currentSensorId) {
        history.filter { currentSensorId != null && SensorIdentity.matches(it.sensorSerial, currentSensorId) }
    }

    // A frame on request, as a tap's ripple asks for one: the recomposer applies any
    // pending state changes and recomposes in it. The service asks when the pill drew
    // another reading than it was handed.
    LaunchedEffect(frameRequests) {
        frameRequests.collect { withFrameNanos { } }
    }
    // Reports what was drawn, not just composed: the frame on screen is the last link.
    val drawnRevision = reading.revision
    val reportDrawn = Modifier.drawBehind { onReadingDrawn(drawnRevision) }
    // And what was composed, once applied: with no window (the screen just came on) it
    // is what the next window's first frame will draw.
    SideEffect { onReadingComposed(drawnRevision) }

    // The overlay only recomposes on new data, so once readings stop nothing would
    // ever notice the last one aging out. The service's clock re-reads the wall clock
    // until it crosses the same timeout the widget and dashboard use; the reading then
    // stays, dimmed. The details card reads the same clock.
    val freshnessNow by freshnessNowFlow.collectAsState()
    // Every layout (pill, side and top island) dims value and arrow by this; the range
    // colours and the time to the next reading follow it too.
    val isStale = !overlayReadingIsFresh(glucosePoint, currentSnapshot?.timeMillis ?: 0L, freshnessNow)
    // View Mode & Calibration
    val viewData = remember(currentSnapshot, glucosePoint, currentSensorId) {
        Pair(floatingViewMode(currentSnapshot, currentSensorId), Natives.getunit())
    }
    val viewMode = viewData.first
    val unitInt = viewData.second
    
    // Same estimator, window and raw/smoothed choice as every other arrow; the
    // overlay used to omit useRaw, so in raw view modes it regressed over the
    // smoothed series and tilted differently from the hero it floats next to.
    // Over the same points too: the stored history plus the live reading Room
    // may not hold yet, newest-anchored, as the dashboard and notification use.
    val trendResult = remember(sensorHistory, viewMode, unitInt, currentSnapshot, currentSensorId) {
        if (sensorHistory.isNotEmpty()) {
            val stored = sensorHistory.map { tk.glucodata.GlucosePoint(it.timestamp, it.value, it.rawValue) }
            TrendEngine.calculateTrend(
                tk.glucodata.DisplayTrendSource.resolveTrendPoints(stored, currentSnapshot, currentSensorId),
                useRaw = (viewMode == 1 || viewMode == 3),
                isMmol = (unitInt == 1)
            )
        } else {
            TrendEngine.TrendResult(TrendEngine.TrendState.Unknown, currentSnapshot?.rate ?: 0f, 0f, 0f, 0f)
        }
    }
    
    // Gap Calculation
    // Use manual gap if > 0, otherwise detected width. Default 70dp if all else fails.
    val isVerticalIsland = FloatingIsland.isSide(cutoutEdge)
    val finalGap = if (manualGap > 0f) {
        manualGap.dp
    } else if (cutoutSize > 0.dp) {
        cutoutSize
    } else {
        70.dp
    }
    val isDarkTheme = isSystemInDarkTheme()
    val style = rememberFloatingOverlayStyle(
        isTransparent = isTransparent,
        darkBackdrop = isDarkTheme,
        fontSource = fontSource,
        fontSize = fontSize,
        fontWeightSetting = fontWeightSetting,
        cornerRadius = cornerRadius,
        opacity = opacity,
        useSubtleOutline = useSubtleOutline,
    )
    val overlayInteractionSource = remember { MutableInteractionSource() }
    val overlayIndication = ripple(
        bounded = true,
        color = if (isTransparent) {
            Color.White.copy(alpha = 0.24f)
        } else {
            Color.White.copy(alpha = 0.16f)
        }
    )
    var pendingDragX by remember { mutableFloatStateOf(0f) }
    var pendingDragY by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }

    // A press that ends in no click (the finger left the pill, or the system took the
    // touch) showed its press and opened nothing: said, for the trace. The free pill's
    // drags end so too, and are not taps.
    val currentTapShowsDetails by rememberUpdatedState(tapShowsDetails)
    val currentOnTapNotOpened by rememberUpdatedState(onTapNotOpened)
    LaunchedEffect(overlayInteractionSource) {
        overlayInteractionSource.interactions.collect { interaction ->
            if (interaction is PressInteraction.Cancel && !dragging && currentTapShowsDetails) {
                currentOnTapNotOpened("press cancelled")
            }
        }
    }

    // Drag Modifier
    val dragModifier = if (isDynamicIsland) Modifier else Modifier.pointerInput(Unit) {
        detectDragGestures(
            onDragStart = { dragging = true },
            onDragEnd = {
                dragging = false
                pendingDragX = 0f
                pendingDragY = 0f
                onDragFinished()
            },
            onDragCancel = {
                dragging = false
                pendingDragX = 0f
                pendingDragY = 0f
                onDragFinished()
            }
        ) { change, dragAmount ->
            change.consume()
            pendingDragX += dragAmount.x
            pendingDragY += dragAmount.y
            val wholeX = pendingDragX.toInt()
            val wholeY = pendingDragY.toInt()
            if (wholeX != 0 || wholeY != 0) {
                pendingDragX -= wholeX
                pendingDragY -= wholeY
                onUpdatePosition(wholeX, wholeY)
            }
        }
    }

    val onPillTap: () -> Unit = {
        val toggle = onToggleDetails?.takeIf { tapShowsDetails }
        if (toggle != null && glucosePoint != null) {
            toggle()
        } else {
            if (toggle != null) onTapNotOpened("no reading, opening the app")
            onOpenApp()
        }
    }
    val onPillLongPress: () -> Unit = {
        if (tapShowsDetails) onTapNotOpened("held, opening the app")
        onOpenApp()
    }

    // A stale reading keeps its value (dimmed below); "---" is only for no reading at all.
    val displayValues = glucosePoint?.let { point ->
        val unit = if (unitInt == 1) "mmol/L" else "mg/dL"
        currentSnapshot?.displayValues ?: run {
            val isRawModeForCal = viewMode == 1 || viewMode == 3
            val hasCalibration = !CalibrationManager.shouldOverwriteSensorValues() &&
                CalibrationManager.hasActiveCalibration(isRawModeForCal)
            val calibratedValue = if (hasCalibration) {
                val baseValue = if (isRawModeForCal) point.rawValue else point.value
                if (baseValue.isFinite() && baseValue > 0.1f) {
                    CalibrationManager.getCalibratedValue(baseValue, point.timestamp, isRawModeForCal)
                } else {
                    null
                }
            } else null
            getDisplayValues(point, viewMode, unit, calibratedValue)
        }
    }

    // Range colours (the app-wide setting) only while the reading is current.
    // A filled pill is dark in either theme, so it takes the dark-theme shades.
    val paletteRevision = GlucosePaletteState.revision
    val primaryValue = displayValues?.primaryValue
    val finalTextColor = style.textColor
    // The value's range colour, whether or not the value is drawn in it: the time to the
    // next reading's ring fills in it too.
    val rangeColor = remember(
        primaryValue, isTransparent, isDarkTheme, unitInt, finalTextColor, refreshRevision, paletteRevision,
    ) {
        if (primaryValue == null) {
            finalTextColor
        } else {
            Color(
                GlucoseValueTone.valueColorArgb(
                    value = primaryValue,
                    isDark = !isTransparent || isDarkTheme,
                    isMmol = unitInt == 1,
                    targetLow = Natives.targetlow(),
                    targetHigh = Natives.targethigh(),
                    veryLowThreshold = Natives.alarmverylow(),
                    veryHighThreshold = Natives.alarmveryhigh(),
                    fallbackArgb = finalTextColor.toArgb(),
                    enabled = true,
                )
            )
        }
    }
    val valueColor = if (isStale || primaryValue == null || !valueRangeColors) finalTextColor else rangeColor

    // Time to the next reading: a ring around the trend arrow, in every layout (the free
    // pill and the island, upright or not), so only while the arrow is shown. Late in the
    // range colours' amber, and late too while the pill is stale.
    val nextReadingLateColor = remember(isTransparent, isDarkTheme, paletteRevision) {
        Color(tk.glucodata.GlucoseRangeColors.valueBorderline(!isTransparent || isDarkTheme))
    }
    val hasArrow = showArrow && glucosePoint != null
    val shownArrowSize = if (isDynamicIsland && isVerticalIsland) {
        (fontSize * 0.78f).coerceIn(10f, 34f).dp
    } else {
        (fontSize * 0.9f).coerceIn(12f, 42f).dp
    }
    val nextReading = nextReadingIndicator(
        enabled = showNextReading,
        readingTime = reading.readingTime,
        intervalMillis = reading.intervalMillis,
        stale = isStale,
        color = finalTextColor,
        rangeColor = rangeColor,
        lateColor = nextReadingLateColor,
        arrowSize = if (hasArrow) shownArrowSize else null,
    )

    // A stale value is drawn dimmed: the value, its arrow and its secondary value in
    // their own colours at reduced opacity, by the same stale flag as the ring's late
    // state. Their outline or shadow stays as it is, for legibility on any background.
    val valueAlpha = FloatingStaleValue.alpha(hasValue = glucosePoint != null, stale = isStale)
    val shownValueColor = valueColor.copy(alpha = valueColor.alpha * valueAlpha)
    val secondaryValueColor = finalTextColor.copy(alpha = 0.7f * valueAlpha)
    val pillStyle = style.copy(textColor = shownValueColor)
    val arrowDecorator: (@Composable (@Composable () -> Unit) -> Unit)? = nextReading.arrowSlot?.let { ringSlot ->
        { arrow ->
            Box(
                modifier = Modifier.size(ringSlot).then(nextReading.arrowRing),
                contentAlignment = Alignment.Center
            ) {
                arrow()
            }
        }
    }

    val content = if (displayValues != null) {
        val secondaryText = displayValues.secondaryStr ?: SecondarySensorDisplay.fallback(
            displayValues, currentSensorId, peerReadings, freshnessNow
        )?.primaryStr
        val overlayReadings = OverlayReadings(currentSensorId, currentSnapshot, peerReadings, rawDeltaText, deltaIntervalMinutes)
        FloatingOverlayContent(
            primary = displayValues.primaryStr,
            secondary = secondaryText?.takeIf { showSecondary && it.isNotEmpty() },
            delta = overlayDeltaText(overlayReadings, showDelta, currentSensorId, deltaIntervalMinutes, freshnessNow)
                .takeIf { it.isNotEmpty() },
            trend = trendResult.takeIf { hasArrow },
        )
    } else {
        FloatingOverlayContent(primary = "---", secondary = null, delta = null, trend = null)
    }

    if (isDynamicIsland) {
        // Clip and click the pill itself, never a wrapper around it: the island's offset
        // wrapper is taller by the offset spacer, so its corner radius clamps differently
        // from the pill's own Surface and its arc cut into the bottom corners.
        val pillModifier = Modifier
            .clip(style.shape)
            .combinedClickable(
                interactionSource = overlayInteractionSource,
                indication = null,
                onLongClick = onPillLongPress,
                onClick = onPillTap,
            )
        CutoutOffsetLayout(
            edge = cutoutEdge,
            centre = cutoutData.centre,
            nudge = islandNudge.dp,
            modifier = reportDrawn
                .wrapContentSize()
                .then(dragModifier)
        ) {
            FloatingOverlayBody(
                order = layoutOrder,
                mode = if (isVerticalIsland) FloatingOverlayMode.ISLAND_COLUMN else FloatingOverlayMode.ISLAND_ROW,
                gap = finalGap,
                style = pillStyle,
                content = content,
                modifier = pillModifier,
                stackLimit = cutoutData.barHeight.takeIf { cutoutEdge == CutoutEdge.TOP && it > 0.dp } ?: Dp.Unspecified,
                secondaryTextColor = secondaryValueColor,
                backgroundModifier = Modifier.clip(style.shape).indication(overlayInteractionSource, overlayIndication),
                arrowDecorator = arrowDecorator,
            )
        }
    } else {
        FloatingOverlayBody(
            order = layoutOrder,
            mode = FloatingOverlayMode.PILL,
            gap = 0.dp,
            style = pillStyle,
            content = content,
            modifier = reportDrawn
                .wrapContentSize()
                .then(dragModifier)
                .clip(style.shape)
                .combinedClickable(
                    interactionSource = overlayInteractionSource,
                    indication = overlayIndication,
                    // No long press: holding the free pill is how a drag starts.
                    onClick = onPillTap,
                ),
            secondaryTextColor = secondaryValueColor,
            arrowDecorator = arrowDecorator,
        )
    }
}

/** How the overlay sits: a free pill, or split around a camera above it or beside it. */
internal enum class FloatingOverlayMode { PILL, ISLAND_ROW, ISLAND_COLUMN }

/** What the overlay draws, resolved; null for a part that is not shown right now. */
internal data class FloatingOverlayContent(
    val primary: String,
    val secondary: String?,
    val delta: String?,
    val trend: TrendEngine.TrendResult?,
)

/** The overlay's look, from its appearance and typography settings. */
@Immutable
internal data class FloatingOverlayStyle(
    val fontSize: Float,
    val fontFamily: FontFamily,
    val fontWeight: FontWeight,
    val textColor: Color,
    val outlineColor: Color,
    val shadow: Shadow,
    val useOutline: Boolean,
    val arrowOutlineColor: Color?,
    val arrowShadowColor: Color?,
    val background: Color,
    val shape: Shape,
)

/**
 * [darkBackdrop] is what the overlay is drawn over: the system theme on screen, the app
 * theme in the settings preview, so a transparent overlay keeps readable text in both.
 */
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
@Composable
internal fun rememberFloatingOverlayStyle(
    isTransparent: Boolean,
    darkBackdrop: Boolean,
    fontSource: String,
    fontSize: Float,
    fontWeightSetting: String,
    cornerRadius: Float,
    opacity: Float,
    useSubtleOutline: Boolean,
): FloatingOverlayStyle {
    val weightVal = when (fontWeightSetting) {
        "LIGHT" -> 300
        "MEDIUM" -> 500
        else -> 400
    }
    val fontFamily = remember(fontSource, weightVal) {
        if (fontSource == "APP") {
            try {
                FontFamily(Font(MainFontFile,
                    variationSettings = FontVariation.Settings(FontVariation.weight(weightVal))))
            } catch (th: Throwable) {
                android.util.Log.w("FloatingGlucoseOverlay", "Variable font fallback activated", th)
                FontFamily(Font(MainFontFile))
            }
        } else {
            val familyName = if (weightVal >= 500) "google-sans-medium" else "google-sans"
            try { FontFamily(Typeface.create(familyName, Typeface.NORMAL)) }
            catch (e: Exception) { FontFamily.SansSerif }
        }
    }
    return remember(isTransparent, darkBackdrop, fontFamily, weightVal, fontSize, cornerRadius, opacity, useSubtleOutline) {
        val textColor = if (isTransparent && !darkBackdrop) {
            // ui-guardrails: allow hex_color - drawn straight onto other apps, outside the app theme
            Color(0xFF27231F)
        } else {
            Color.White
        }
        val outlineColor = when {
            isTransparent && !darkBackdrop -> Color.White.copy(alpha = 0.88f)
            isTransparent -> Color.Black.copy(alpha = 0.58f)
            else -> Color.Black.copy(alpha = 0.28f)
        }
        val shadow = Shadow(
            color = when {
                isTransparent && !darkBackdrop -> Color.White.copy(alpha = 1.0f)
                isTransparent -> Color.Black.copy(alpha = 0.62f)
                else -> Color.Black.copy(alpha = 0.42f)
            },
            offset = Offset(0f, if (isTransparent && !darkBackdrop) 0f else if (isTransparent) 1.2f else 1.5f),
            blurRadius = if (isTransparent && !darkBackdrop) 7f else if (isTransparent) 4.5f else 5f
        )
        FloatingOverlayStyle(
            fontSize = fontSize,
            fontFamily = fontFamily,
            fontWeight = FontWeight(weightVal),
            textColor = textColor,
            outlineColor = outlineColor,
            shadow = shadow,
            useOutline = useSubtleOutline,
            arrowOutlineColor = if (isTransparent && useSubtleOutline) outlineColor else null,
            arrowShadowColor = if (isTransparent && !useSubtleOutline) shadow.color else null,
            background = if (isTransparent) Color.Transparent else Color.Black.copy(alpha = opacity),
            shape = RoundedCornerShape(cornerRadius.dp),
        )
    }
}

/**
 * The overlay itself, laid out in [order] (#981): one pill, or the items before the camera
 * on one side of a [gap] the camera's width and the rest on the other. The settings preview
 * draws this same body, so what it shows is what floats.
 *
 * In a row the delta goes under its neighbour only while the two stay about a line tall:
 * at most [RowStackLines] reading lines, and no taller than [stackLimit] (the status bar,
 * for an island at the top). Otherwise it goes beside it.
 */
@Composable
internal fun FloatingOverlayBody(
    order: List<FloatingLayoutItem>,
    mode: FloatingOverlayMode,
    gap: Dp,
    style: FloatingOverlayStyle,
    content: FloatingOverlayContent,
    modifier: Modifier = Modifier,
    stackLimit: Dp = Dp.Unspecified,
    secondaryTextColor: Color = style.textColor.copy(alpha = 0.7f),
    backgroundModifier: Modifier = Modifier,
    arrowDecorator: (@Composable (@Composable () -> Unit) -> Unit)? = null,
) {
    val fontSize = style.fontSize
    val inlineSpacing = (fontSize * 0.35f).coerceIn(4f, 10f).dp
    val pillHorizontalPadding = (fontSize * 0.85f).coerceIn(10f, 24f).dp
    val pillVerticalPadding = (fontSize * 0.45f).coerceIn(4f, 12f).dp
    val sideIslandHorizontalPadding = (fontSize * 0.55f).coerceIn(6f, 16f).dp
    val sideIslandVerticalPadding = (fontSize * 0.32f).coerceIn(3f, 8f).dp
    val sideIslandSplitPadding = (fontSize * 0.18f).coerceIn(2f, 6f).dp
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    // Keyed on the density too: a new font or display size changes the line's pixel height.
    val readingLine = remember(style.fontSize, style.fontFamily, style.fontWeight, density, textMeasurer) {
        textMeasurer.measure(
            "0",
            TextStyle(fontSize = style.fontSize.sp, fontFamily = style.fontFamily, fontWeight = style.fontWeight)
        ).size.height
    }
    val rowStackLimit = with(density) { (readingLine * RowStackLines).toDp() }
        .let { lines -> if (stackLimit.isSpecified) minOf(lines, stackLimit) else lines }

    fun blocks(items: List<FloatingLayoutItem>) = floatingLayoutBlocks(
        items,
        showSecondary = content.secondary != null,
        showArrow = content.trend != null,
        showDelta = content.delta != null,
    )

    val stacked = mode == FloatingOverlayMode.ISLAND_COLUMN
    val side: @Composable (List<FloatingLayoutItem>) -> Unit = { items ->
        if (stacked) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(sideIslandSplitPadding)
            ) {
                blocks(items).forEach {
                    FloatingLayoutBlockContent(it, stacked = true, style, content, Dp.Unspecified, inlineSpacing, secondaryTextColor, arrowDecorator)
                }
            }
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(inlineSpacing)
            ) {
                blocks(items).forEach {
                    FloatingLayoutBlockContent(it, stacked = false, style, content, rowStackLimit, inlineSpacing, secondaryTextColor, arrowDecorator)
                }
            }
        }
    }
    val background: @Composable () -> Unit = {
        Surface(color = style.background, shape = style.shape, modifier = Modifier.fillMaxSize().then(backgroundModifier)) {}
    }

    when (mode) {
        FloatingOverlayMode.PILL -> Surface(
            color = style.background,
            shape = style.shape,
            modifier = modifier
        ) {
            Box(Modifier.padding(horizontal = pillHorizontalPadding, vertical = pillVerticalPadding)) {
                side(order)
            }
        }
        FloatingOverlayMode.ISLAND_ROW -> {
            val sides = FloatingLayoutOrder.sides(order)
            AsymmetricCenteringRow(
                modifier = modifier,
                gap = gap,
                verticalAlignment = Alignment.CenterVertically,
                backgroundContent = background
            ) {
                Box(Modifier.padding(start = pillHorizontalPadding, top = pillVerticalPadding, bottom = pillVerticalPadding)) {
                    side(sides.before)
                }
                Box(Modifier.padding(end = pillHorizontalPadding, top = pillVerticalPadding, bottom = pillVerticalPadding)) {
                    side(sides.after)
                }
            }
        }
        FloatingOverlayMode.ISLAND_COLUMN -> {
            val sides = FloatingLayoutOrder.sides(order)
            AsymmetricCenteringColumn(
                modifier = modifier,
                gap = gap,
                horizontalAlignment = Alignment.CenterHorizontally,
                backgroundContent = background
            ) {
                Box(
                    modifier = Modifier.padding(
                        start = sideIslandHorizontalPadding,
                        end = sideIslandHorizontalPadding,
                        top = sideIslandVerticalPadding + sideIslandSplitPadding,
                        bottom = sideIslandSplitPadding
                    )
                ) {
                    side(sides.before)
                }
                Box(
                    modifier = Modifier.padding(
                        start = sideIslandHorizontalPadding,
                        end = sideIslandHorizontalPadding,
                        top = sideIslandSplitPadding,
                        bottom = sideIslandVerticalPadding + sideIslandSplitPadding
                    )
                ) {
                    side(sides.after)
                }
            }
        }
    }
}

/**
 * How tall, in reading lines, the delta and what it sits under may stand in a row. The
 * smaller secondary value or the arrow over the delta fit; the reading over it does not.
 */
private const val RowStackLines = 1.5f

/**
 * One block: [stacked] beside a side cutout, where the pieces sit under each other. A block
 * with the delta draws it underneath, or [inlineSpacing] beside it past [stackLimit]. In a
 * row, a reading with its secondary value carries the delta under the smaller secondary.
 */
@Composable
private fun FloatingLayoutBlockContent(
    block: FloatingLayoutBlock,
    stacked: Boolean,
    style: FloatingOverlayStyle,
    content: FloatingOverlayContent,
    stackLimit: Dp,
    inlineSpacing: Dp,
    secondaryColor: Color,
    arrowDecorator: (@Composable (@Composable () -> Unit) -> Unit)?,
) {
    val fontSize = style.fontSize
    val secondaryFontSize = if (stacked) fontSize * 0.58f else fontSize * 0.7f
    val text: @Composable (String, Float, Color, TextAlign) -> Unit = { value, size, color, align ->
        FloatingStyledText(
            text = value,
            fontSize = size,
            fontFamily = style.fontFamily,
            fontWeight = style.fontWeight,
            textColor = color,
            outlineColor = style.outlineColor,
            shadow = style.shadow,
            useOutline = style.useOutline,
            textAlign = align
        )
    }
    val delta = content.delta
    val deltaText: @Composable () -> Unit = {
        if (delta != null) text(delta, fontSize * 0.65f, style.textColor, TextAlign.Center)
    }
    val deltaUnderSecondary = !stacked && delta != null && block is FloatingLayoutBlock.Reading &&
        block.withDelta && block.withSecondary && content.secondary != null
    val main: @Composable () -> Unit = {
        when (block) {
            is FloatingLayoutBlock.Reading -> {
                val secondary = content.secondary.takeIf { block.withSecondary }
                if (stacked) {
                    val spacing = (fontSize * 0.08f).coerceIn(1f, 4f).dp
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (secondary != null && block.secondaryFirst) {
                            text(secondary, secondaryFontSize, secondaryColor, TextAlign.Center)
                            Spacer(Modifier.height(spacing))
                        }
                        text(content.primary, fontSize, style.textColor, TextAlign.Center)
                        if (secondary != null && !block.secondaryFirst) {
                            Spacer(Modifier.height(spacing))
                            text(secondary, secondaryFontSize, secondaryColor, TextAlign.Center)
                        }
                    }
                } else {
                    val spacing = (fontSize * 0.35f).coerceIn(4f, 10f).dp
                    val secondaryPart: @Composable (String) -> Unit = { value ->
                        if (deltaUnderSecondary) {
                            DeltaBeneath(stackLimit, inlineSpacing, { text(value, secondaryFontSize, secondaryColor, TextAlign.Center) }, deltaText)
                        } else {
                            text(value, secondaryFontSize, secondaryColor, TextAlign.Start)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (secondary != null && block.secondaryFirst) {
                            secondaryPart(secondary)
                            Spacer(Modifier.width(spacing))
                        }
                        text(content.primary, fontSize, style.textColor, TextAlign.End)
                        if (secondary != null && !block.secondaryFirst) {
                            Spacer(Modifier.width(spacing))
                            secondaryPart(secondary)
                        }
                    }
                }
            }
            is FloatingLayoutBlock.Secondary -> content.secondary?.let { secondary ->
                text(secondary, secondaryFontSize, secondaryColor, if (stacked) TextAlign.Center else TextAlign.Start)
            }
            is FloatingLayoutBlock.Arrow -> content.trend?.let { trend ->
                val arrowSize = if (stacked) (fontSize * 0.78f).coerceIn(10f, 34f) else (fontSize * 0.9f).coerceIn(12f, 42f)
                val arrow: @Composable () -> Unit = {
                    TrendIndicator(
                        trendResult = trend,
                        modifier = Modifier.size(arrowSize.dp),
                        color = style.textColor,
                        outlineColor = style.arrowOutlineColor,
                        shadowColor = style.arrowShadowColor
                    )
                }
                if (arrowDecorator != null) arrowDecorator(arrow) else arrow()
            }
            FloatingLayoutBlock.Delta -> Unit
        }
    }
    when {
        block == FloatingLayoutBlock.Delta -> deltaText()
        deltaUnderSecondary -> main()
        block.withDelta && delta != null -> DeltaBeneath(stackLimit, inlineSpacing, main, deltaText)
        else -> main()
    }
}

/**
 * [main] with [delta] centred under it, or beside it, [spacing] apart and centred on its
 * height, when the two stacked would stand taller than [limit].
 */
@Composable
private fun DeltaBeneath(
    limit: Dp,
    spacing: Dp,
    main: @Composable () -> Unit,
    delta: @Composable () -> Unit,
) {
    Layout(contents = listOf(main, delta)) { (mainMeasurables, deltaMeasurables), constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val top = mainMeasurables.firstOrNull()?.measure(loose)
        val bottom = deltaMeasurables.firstOrNull()?.measure(loose)
        val mainWidth = top?.width ?: 0
        val mainHeight = top?.height ?: 0
        val deltaWidth = bottom?.width ?: 0
        val deltaHeight = bottom?.height ?: 0
        val beside = limit.isSpecified && mainHeight + deltaHeight > limit.roundToPx()
        if (beside) {
            val gap = if (top != null && bottom != null) spacing.roundToPx() else 0
            val height = maxOf(mainHeight, deltaHeight)
            layout(mainWidth + gap + deltaWidth, height) {
                top?.place(0, (height - mainHeight) / 2)
                bottom?.place(mainWidth + gap, (height - deltaHeight) / 2)
            }
        } else {
            val width = maxOf(mainWidth, deltaWidth)
            layout(width, mainHeight + deltaHeight) {
                top?.place((width - mainWidth) / 2, 0)
                bottom?.place((width - deltaWidth) / 2, mainHeight)
            }
        }
    }
}

/**
 * What the glucose notification shows, for the reading on the pill: its time
 * and age, the Δ, the chart and the IOB/COB line (when the notification shows
 * one). Tapping it opens the app. Its background is [backgroundOpacity] opaque.
 * While open it follows the pill's reading, its age on the pill's freshness clock,
 * and a stale reading is dimmed as on the pill; see FloatingDetailsCardState.
 */
@Composable
fun FloatingDetailsCard(
    readingFlow: StateFlow<FloatingPillReading>,
    freshnessNowFlow: StateFlow<Long>,
    isDark: Boolean,
    onOpenApp: () -> Unit,
    // "+ Insulin" / "+ Food" under the details; null hides them (Quick log buttons or journal off).
    onQuickEntry: ((tk.glucodata.data.journal.JournalEntryType) -> Unit)? = null,
    backgroundOpacity: Float = FloatingSettingsRepository.DEFAULT_DETAILS_OPACITY,
) {
    val reading by readingFlow.collectAsState()
    val freshnessNow by freshnessNowFlow.collectAsState()
    val viewData = remember(reading.snapshot, reading.point, reading.sensorId) {
        Pair(floatingViewMode(reading.snapshot, reading.sensorId), Natives.getunit())
    }
    val state = FloatingDetailsCardState.of(reading, viewData.second == 1, viewData.first, freshnessNow) ?: return
    val request = state.request
    val context = LocalContext.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val cardWidth = FloatingDetailsCardWidth
    val chartHeight = 110.dp
    val chartWidthPx = with(density) { (cardWidth - 24.dp).roundToPx() }
    val chartHeightPx = with(density) { chartHeight.roundToPx() }
    // Reloaded for each new reading; the last one stays shown until the next is drawn.
    val details by produceState<tk.glucodata.FloatingDetailsSource.Details?>(null, request, isDark) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            tk.glucodata.FloatingDetailsSource.load(
                context, request.sensorId, request.isMmol, request.viewMode,
                chartWidthPx, chartHeightPx, isDark, request.displayGlucose,
            )
        }
    }
    val background = (if (isDark) FloatingDetailsPalette.darkSurface else FloatingDetailsPalette.lightSurface).copy(alpha = backgroundOpacity)
    val textColor = if (isDark) Color.White else FloatingDetailsPalette.lightInk
    val time = remember(state.readingTime) {
        android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(state.readingTime))
    }
    val header = buildList {
        add(time)
        add(context.getString(tk.glucodata.R.string.minutes_short_format, state.ageMinutes.toInt()))
        details?.delta?.takeIf { it.isNotEmpty() }?.let { add("Δ $it") }
    }.joinToString(" · ")
    val headerAlpha = FloatingStaleValue.alpha(hasValue = true, stale = state.stale)

    Surface(
        color = background,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier
            .width(cardWidth)
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onOpenApp),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(header, color = textColor.copy(alpha = headerAlpha), fontSize = 14.sp)
            val chart = details?.chart
            if (chart != null) {
                androidx.compose.foundation.Image(
                    bitmap = chart.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth().height(chartHeight),
                )
            } else {
                Spacer(Modifier.fillMaxWidth().height(chartHeight))
            }
            details?.iobLine?.let { Text(it, color = textColor.copy(alpha = 0.8f), fontSize = 13.sp) }
            onQuickEntry?.let { FloatingQuickEntryButtons(textColor = textColor, onLog = it) }
        }
    }
}

@Composable
internal fun FloatingStyledText(
    text: String, 
    fontSize: Float, 
    fontFamily: FontFamily, 
    fontWeight: FontWeight, 
    textColor: Color, 
    outlineColor: Color,
    shadow: Shadow,
    useOutline: Boolean,
    textAlign: TextAlign = TextAlign.Start
) {
    AnimatedContent(
        targetState = text,
        transitionSpec = {
            (fadeIn(tween(200)) + slideInVertically(tween(220)) { -it / 3 })
                .togetherWith(fadeOut(tween(140)) + slideOutVertically(tween(220)) { it / 3 })
        },
        label = "FloatingReadingValue"
    ) { shownText ->
        if (useOutline) {
            Box {
                Text(
                    text = shownText,
                    color = outlineColor,
                    fontSize = fontSize.sp,
                    fontFamily = fontFamily,
                    fontWeight = fontWeight,
                    textAlign = textAlign,
                    style = TextStyle.Default.copy(
                        drawStyle = Stroke(
                            miter = 10f,
                            width = 2.6f,
                            join = StrokeJoin.Round
                        )
                    )
                )
                Text(
                    text = shownText,
                    color = textColor,
                    fontSize = fontSize.sp,
                    fontFamily = fontFamily,
                    fontWeight = fontWeight,
                    textAlign = textAlign
                )
            }
        } else {
            Text(
                text = shownText,
                color = textColor,
                fontSize = fontSize.sp,
                fontFamily = fontFamily,
                fontWeight = fontWeight,
                textAlign = textAlign,
                style = TextStyle(shadow = shadow)
            )
        }
    }
}




@Composable
fun AsymmetricCenteringRow(
    modifier: Modifier = Modifier,
    gap: androidx.compose.ui.unit.Dp,
    verticalAlignment: Alignment.Vertical,
    backgroundContent: @Composable () -> Unit,
    content: @Composable () -> Unit
) {
    // Layout that takes:
    // 1. Background (as a composable)
    // 2. Left and Right content
    // Calculations:
    // - Measure L, R.
    // - SymmetricWidth = 2 * max(L, R) + Gap.
    // - ActualContentWidth = L + Gap + R.
    // - OffsetX = if (L < R) (SymmetricWidth - ActualContentWidth) / 2 else 0? 
    //   Wait. Layout places children.
    //   If L=50, R=100, Gap=10.
    //   Max=100. SymW = 210.
    //   Center of SymW = 105.
    //   Gap must start at Center - Gap/2 = 100.
    //   So Left must end at 100. Left Start = 100 - 50 = 50.
    //   Gap ends at 110. Right starts at 110. Right ends at 210.
    //   
    //   Offset Calculation:
    //   LeftX = max(L, R) - L.
    //   RightX = max(L, R) + Gap.
    //   BgX = LeftX. BgWidth = L + Gap + R.
    
    Layout(
        contents = listOf(backgroundContent, content),
        modifier = modifier
    ) { (bgMeasurables, contentMeasurables), constraints ->
        
        // Measure Content first
        val contentPlaceables = contentMeasurables.take(2).map { it.measure(constraints.copy(minWidth = 0)) }
        val left = contentPlaceables.getOrNull(0)
        val right = contentPlaceables.getOrNull(1)
        
        val leftW = left?.width ?: 0
        val rightW = right?.width ?: 0
        val leftH = left?.height ?: 0
        val rightH = right?.height ?: 0
        
        val gapPx = gap.roundToPx()
        val maxSideWidth = maxOf(leftW, rightW)
        
        // Report Symmetric Size
        val totalWidth = maxSideWidth * 2 + gapPx
        val totalHeight = maxOf(leftH, rightH)
        
        // Measure Background to fit TIGHTLY around content (L + G + R)
        val bgWidth = leftW + gapPx + rightW
        val bgPlaceable = bgMeasurables.firstOrNull()?.measure(Constraints.fixed(bgWidth, totalHeight))
        
        fun getY(height: Int): Int {
             return when (verticalAlignment) {
                 Alignment.CenterVertically -> (totalHeight - height) / 2
                 Alignment.Bottom -> totalHeight - height
                 else -> 0
             }
        }
        
        layout(totalWidth, totalHeight) {
            val startX = maxSideWidth - leftW
            
            // Place Background
            bgPlaceable?.place(x = startX, y = 0)
            
            // Place Left
            left?.place(x = startX, y = getY(leftH))
            
            // Place Right
            right?.place(x = maxSideWidth + gapPx, y = getY(rightH))
        }
    }
}

/**
 * Puts the pill [content] a gap in from the screen edge it is docked to ([edge]), so that
 * it is centred on the cutout [centre] from that edge (0 when not known), then moved by
 * [nudge] away from the edge. The gap depends on the pill's own thickness across the edge,
 * so it is worked out from the measured pill; see FloatingIsland.
 */
@Composable
private fun CutoutOffsetLayout(
    edge: CutoutEdge,
    centre: androidx.compose.ui.unit.Dp,
    nudge: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val pill = measurables.single().measure(constraints.copy(minWidth = 0, minHeight = 0))
        val gap = FloatingIsland.edgeGap(
            centre = centre.toPx(),
            nudge = nudge.toPx(),
            thickness = FloatingIsland.thickness(edge, pill.width, pill.height).toFloat(),
            noCutoutGap = FloatingIsland.NO_CUTOUT_GAP_DP.dp.toPx()
        ).roundToInt()
        val placement = FloatingIsland.place(edge, pill.width, pill.height, gap)
        layout(placement.width, placement.height) {
            pill.place(placement.pillX, placement.pillY)
        }
    }
}

@Composable
private fun AsymmetricCenteringColumn(
    modifier: Modifier = Modifier,
    gap: androidx.compose.ui.unit.Dp,
    horizontalAlignment: Alignment.Horizontal,
    backgroundContent: @Composable () -> Unit,
    content: @Composable () -> Unit
) {
    Layout(
        contents = listOf(backgroundContent, content),
        modifier = modifier
    ) { (bgMeasurables, contentMeasurables), constraints ->
        val contentPlaceables = contentMeasurables.take(2).map { it.measure(constraints.copy(minHeight = 0)) }
        val top = contentPlaceables.getOrNull(0)
        val bottom = contentPlaceables.getOrNull(1)

        val topW = top?.width ?: 0
        val bottomW = bottom?.width ?: 0
        val topH = top?.height ?: 0
        val bottomH = bottom?.height ?: 0

        val gapPx = gap.roundToPx()
        val maxSideHeight = maxOf(topH, bottomH)

        val totalWidth = maxOf(topW, bottomW)
        val totalHeight = maxSideHeight * 2 + gapPx

        val bgHeight = topH + gapPx + bottomH
        val bgPlaceable = bgMeasurables.firstOrNull()?.measure(Constraints.fixed(totalWidth, bgHeight))

        fun getX(width: Int): Int {
            return when (horizontalAlignment) {
                Alignment.CenterHorizontally -> (totalWidth - width) / 2
                Alignment.End -> totalWidth - width
                else -> 0
            }
        }

        layout(totalWidth, totalHeight) {
            val startY = maxSideHeight - topH

            bgPlaceable?.place(x = 0, y = startY)
            top?.place(x = getX(topW), y = startY)
            bottom?.place(x = getX(bottomW), y = maxSideHeight + gapPx)
        }
    }
}

private const val OVERLAY_FRESHNESS_POLL_MS = 15_000L

/**
 * How long to wait before re-checking whether the reading at [latestReadingMillis]
 * has gone stale, or null once it has (or there is none) and nothing is left to
 * watch. Capped at [OVERLAY_FRESHNESS_POLL_MS] because coroutine delays run on
 * uptime, which stops during deep sleep: one long wait would wake far too late.
 * While [ageShown] (the details card is open) a stale reading is still watched, at
 * that cap, so the age the card shows goes on.
 */
internal fun nextOverlayFreshnessCheckDelay(
    latestReadingMillis: Long,
    nowMillis: Long,
    freshnessWindowMillis: Long = Notify.glucosetimeout,
    ageShown: Boolean = false,
): Long? {
    if (latestReadingMillis <= 0L) return null
    val untilStale = latestReadingMillis + freshnessWindowMillis - nowMillis
    if (untilStale < 0L) return if (ageShown) OVERLAY_FRESHNESS_POLL_MS else null
    return (untilStale + 1L).coerceAtMost(OVERLAY_FRESHNESS_POLL_MS)
}

/** The view mode of [snapshot]'s reading, else [sensorId]'s, else 0. */
internal fun floatingViewMode(snapshot: CurrentDisplaySource.Snapshot?, sensorId: String?): Int {
    snapshot?.viewMode?.let { return it }
    if (sensorId.isNullOrEmpty()) return 0
    return runCatching {
        val sensor = Natives.getSensorUiSnapshot(sensorId)
        if (sensor != null && sensor.size >= 2) sensor[1].toInt() else 0
    }.getOrDefault(0)
}

/**
 * Whether the overlay's reading is current at [nowMillis]: within the widget/dashboard
 * freshness window, counted from the newer of [latestPoint] and the current value's
 * [snapshotMillis]. False without a reading. A reading that is not current stays on
 * the pill, dimmed (FloatingStaleValue), with a late next-reading indicator.
 */
internal fun overlayReadingIsFresh(
    latestPoint: GlucosePoint?,
    snapshotMillis: Long,
    nowMillis: Long,
    freshnessWindowMillis: Long = Notify.glucosetimeout
): Boolean {
    if (latestPoint == null) return false
    return DisplayDataState.resolve(
        sensorPresent = true,
        currentTimestampMillis = snapshotMillis,
        latestHistoryTimestampMillis = latestPoint.timestamp,
        freshnessWindowMillis = freshnessWindowMillis,
        nowMillis = nowMillis
    ).isFresh
}

/** [latestPoint] while current at [nowMillis], or null once stale (see [overlayReadingIsFresh]). */
internal fun overlayDisplayPoint(
    latestPoint: GlucosePoint?,
    snapshotMillis: Long,
    nowMillis: Long,
    freshnessWindowMillis: Long = Notify.glucosetimeout
): GlucosePoint? =
    if (overlayReadingIsFresh(latestPoint, snapshotMillis, nowMillis, freshnessWindowMillis)) latestPoint else null
