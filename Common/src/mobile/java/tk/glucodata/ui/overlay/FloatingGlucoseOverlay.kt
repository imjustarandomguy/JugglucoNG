package tk.glucodata.ui.overlay

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
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.unit.Constraints
import tk.glucodata.service.FloatingGlucoseService.CutoutData
import tk.glucodata.service.FloatingGlucoseService.CutoutEdge
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.emptyFlow
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
import tk.glucodata.UiRefreshBus

/** How often a current reading is rechecked for staleness. */
private const val STALE_RECHECK_MS = 30_000L

/**
 * A stale reading on the pill: its last value stays, dimmed, beside the next-reading
 * bar's amber; "---" is only for no value at all. Pure, see the tests.
 */
internal object FloatingStaleValue {
    /** Opacity of a stale value against a current one: clearly old, still readable. */
    const val DIMMED_ALPHA = 0.5f

    /**
     * The opacity factor for the value, its trend arrow and its secondary value:
     * [DIMMED_ALPHA] while there is a value and it is [stale], the same flag the
     * next-reading bar is late by; 1 otherwise.
     */
    fun alpha(hasValue: Boolean, stale: Boolean): Float = if (hasValue && stale) DIMMED_ALPHA else 1f
}

/** The reading a details card is opened for. */
/** Width of the details card; the service places its window by it. */
internal val FloatingDetailsCardWidth = 260.dp

data class FloatingDetailsRequest(
    val point: GlucosePoint,
    val sensorId: String?,
    val isMmol: Boolean,
    val viewMode: Int,
    val displayGlucose: Float,
)

/**
 * The reading the pill shows, resolved by FloatingGlucoseService: the newest of the
 * readings it follows, and the current value as the notification resolves it.
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
    onToggleDetails: ((FloatingDetailsRequest) -> Unit)? = null,
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
    val showArrow by repository.showArrow.collectAsState(initial = true)
    val cornerRadius by repository.cornerRadius.collectAsState(initial = 28f)
    val opacity by repository.backgroundOpacity.collectAsState(initial = FloatingSettingsRepository.DEFAULT_BACKGROUND_OPACITY)
    val valueRangeColors by repository.valueRangeColors.collectAsState(initial = false)
    val isDynamicIsland by repository.isDynamicIslandEnabled.collectAsState(initial = false)
    val verticalOffset by repository.islandVerticalOffset.collectAsState(initial = FloatingSettingsRepository.DEFAULT_ISLAND_VERTICAL_OFFSET)
    val manualGap by repository.islandGap.collectAsState(initial = 0f)
    val useSubtleOutline by repository.useSubtleOutline.collectAsState(initial = false)
    val isMirrored by repository.isMirrored.collectAsState(initial = false)
    val showNextReading by repository.showNextReading.collectAsState(initial = true)
    val nextReadingStyle by repository.nextReadingStyle.collectAsState(initial = FloatingNextReadingStyle.CURRENT.name)

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
    // ever notice the last one aging out. Re-read the clock until it crosses the
    // same timeout the widget and dashboard use, then show the no-data state.
    val latestReadingMillis = maxOf(currentSnapshot?.timeMillis ?: 0L, glucosePoint?.timestamp ?: 0L)
    val freshnessNow by produceState(System.currentTimeMillis(), latestReadingMillis) {
        while (true) {
            value = System.currentTimeMillis()
            val wait = nextOverlayFreshnessCheckDelay(latestReadingMillis, value) ?: break
            delay(wait)
        }
    }
    // Every layout (pill, side and top island) reads value and arrow from this.
    val displayPoint = overlayDisplayPoint(glucosePoint, currentSnapshot?.timeMillis ?: 0L, freshnessNow)

    // View Mode & Calibration
    val viewData = remember(currentSnapshot, glucosePoint, currentSensorId) {
        val resolvedViewMode = currentSnapshot?.viewMode
        val viewMode = if (resolvedViewMode != null) {
            resolvedViewMode
        } else if (!currentSensorId.isNullOrEmpty()) {
            runCatching {
                val snapshot = Natives.getSensorUiSnapshot(currentSensorId)
                if (snapshot != null && snapshot.size >= 2) snapshot[1].toInt() else 0
            }.getOrDefault(0)
        } else 0
        Pair(viewMode, Natives.getunit())
    }
    val viewMode = viewData.first
    val unitInt = viewData.second
    
    // Same estimator, window and raw/smoothed choice as every other arrow; the
    // overlay used to omit useRaw, so in raw view modes it regressed over the
    // smoothed series and tilted differently from the hero it floats next to.
    // Over the same points too: the stored history plus the live reading Room
    // may not hold yet, newest-anchored, as the dashboard and notification use.
    val trendResult = remember(history, viewMode, unitInt, currentSnapshot, currentSensorId) {
        if (history.isNotEmpty()) {
            val stored = history.map { tk.glucodata.GlucosePoint(it.timestamp, it.value, it.rawValue) }
            TrendEngine.calculateTrend(
                tk.glucodata.DisplayTrendSource.resolveTrendPoints(stored, currentSnapshot, currentSensorId),
                useRaw = (viewMode == 1 || viewMode == 3),
                isMmol = (unitInt == 1)
            )
        } else {
            TrendEngine.TrendResult(TrendEngine.TrendState.Unknown, 0f, 0f, 0f, 0f)
        }
    }
    
    // Gap Calculation
    // Use manual gap if > 0, otherwise detected width. Default 70dp if all else fails.
    val isVerticalIsland = cutoutEdge == CutoutEdge.LEFT || cutoutEdge == CutoutEdge.RIGHT
    val finalGap = if (manualGap > 0f) {
        manualGap.dp
    } else if (cutoutSize > 0.dp) {
        cutoutSize
    } else {
        70.dp
    }
    // Styles
    val isDarkTheme = isSystemInDarkTheme()
    val finalBgColor = if (isTransparent) Color.Transparent else Color.Black.copy(alpha = opacity)
    val finalShape = RoundedCornerShape(cornerRadius.dp)
    val finalTextColor = if (isTransparent && !isDarkTheme) {
        Color(0xFF27231F)
    } else {
        Color.White
    }
    val textOutlineColor = when {
        isTransparent && !isDarkTheme -> Color.White.copy(alpha = 0.88f)
        isTransparent -> Color.Black.copy(alpha = 0.58f)
        else -> Color.Black.copy(alpha = 0.28f)
    }
    val textShadow = Shadow(
        color = when {
            isTransparent && !isDarkTheme -> Color.White.copy(alpha = 1.0f)
            isTransparent -> Color.Black.copy(alpha = 0.62f)
            else -> Color.Black.copy(alpha = 0.42f)
        },
        offset = Offset(0f, if (isTransparent && !isDarkTheme) 0f else if (isTransparent) 1.2f else 1.5f),
        blurRadius = if (isTransparent && !isDarkTheme) 7f else if (isTransparent) 4.5f else 5f
    )
    val arrowOutlineColor = if (isTransparent && useSubtleOutline) textOutlineColor else null
    val arrowShadowColor = if (isTransparent && !useSubtleOutline) textShadow.color else null
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

    // Font Logic
    val weightVal = when (fontWeightSetting) {
        "LIGHT" -> 300
        "MEDIUM" -> 500
        else -> 400
    }
    
    val fontFamily = if (fontSource == "APP") {
        try {
            FontFamily(
                Font(
                    MainFontFile,
                    variationSettings = FontVariation.Settings(FontVariation.weight(weightVal))
                )
            )
        } catch (th: Throwable) {
            android.util.Log.w("FloatingGlucoseOverlay", "Variable font fallback activated", th)
            FontFamily(Font(MainFontFile))
        }
    } else {
        remember(weightVal) {
             val familyName = if (weightVal >= 500) "google-sans-medium" else "google-sans"
             try {
                 FontFamily(Typeface.create(familyName, Typeface.NORMAL))
             } catch (e: Exception) { FontFamily.SansSerif }
        }
    }
    val fontWeight = FontWeight(weightVal)
    val inlineSpacing = (fontSize * 0.35f).coerceIn(4f, 10f).dp
    val pillHorizontalPadding = (fontSize * 0.85f).coerceIn(10f, 24f).dp
    val pillVerticalPadding = (fontSize * 0.45f).coerceIn(4f, 12f).dp
    val splitPadding = (fontSize * 0.28f).coerceIn(3f, 8f).dp
    val arrowSize = (fontSize * 0.9f).coerceIn(12f, 42f).dp
    val sideIslandHorizontalPadding = (fontSize * 0.55f).coerceIn(6f, 16f).dp
    val sideIslandVerticalPadding = (fontSize * 0.32f).coerceIn(3f, 8f).dp
    val sideIslandSplitPadding = (fontSize * 0.18f).coerceIn(2f, 6f).dp
    val sideIslandArrowSize = (fontSize * 0.78f).coerceIn(10f, 34f).dp
    val shownArrowSize = if (isDynamicIsland && isVerticalIsland) sideIslandArrowSize else arrowSize
    val sideSecondarySpacing = (fontSize * 0.08f).coerceIn(1f, 4f).dp
    val sideSecondaryFontSize = fontSize * 0.58f

    val onPillTap: () -> Unit = {
        val toggle = onToggleDetails?.takeIf { tapShowsDetails }
        if (toggle != null && glucosePoint != null) {
            toggle(
                FloatingDetailsRequest(
                    point = glucosePoint,
                    sensorId = currentSensorId,
                    isMmol = unitInt == 1,
                    viewMode = viewMode,
                    displayGlucose = currentSnapshot?.displayValues?.primaryValue ?: glucosePoint.value,
                )
            )
        } else {
            if (toggle != null) onTapNotOpened("no reading, opening the app")
            onOpenApp()
        }
    }
    val onPillLongPress: () -> Unit = {
        if (tapShowsDetails) onTapNotOpened("held, opening the app")
        onOpenApp()
    }

    val displayValues = displayPoint?.let { point ->
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
    val readingTime = glucosePoint?.timestamp ?: 0L
    var isFreshReading by remember(readingTime) {
        mutableStateOf(readingTime > 0L && System.currentTimeMillis() - readingTime <= Notify.glucosetimeout)
    }
    LaunchedEffect(readingTime) {
        // Short steps against the wall clock: delay() pauses while the device sleeps.
        while (isFreshReading) {
            val untilStale = readingTime + Notify.glucosetimeout - System.currentTimeMillis()
            if (untilStale <= 0L) {
                isFreshReading = false
            } else {
                kotlinx.coroutines.delay(minOf(untilStale, STALE_RECHECK_MS))
            }
        }
    }
    val paletteRevision = GlucosePaletteState.revision
    val primaryValue = displayValues?.primaryValue
    // The value's range colour, whether or not the value is drawn in it: the time to the
    // next reading fills in it too (in all its styles but the thin bar).
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
    val valueColor = if (!isFreshReading || primaryValue == null || !valueRangeColors) finalTextColor else rangeColor

    // Time to the next reading: a bar along the pill's bottom edge (its long inner side on
    // an upright island) or its outline, drawn on its clipped background, or a ring around
    // the arrow. Late in the range colours' amber, and late too while the pill is stale.
    val nextReadingLateColor = remember(isTransparent, isDarkTheme, paletteRevision) {
        Color(tk.glucodata.GlucoseRangeColors.valueBorderline(!isTransparent || isDarkTheme))
    }
    val isStale = !isFreshReading
    val hasArrow = showArrow && glucosePoint != null
    val nextReading = nextReadingIndicator(
        enabled = showNextReading,
        style = FloatingNextReadingStyle.fromKey(nextReadingStyle),
        side = FloatingNextReading.barSide(if (isDynamicIsland) cutoutEdge else null),
        readingTime = reading.readingTime,
        intervalMillis = reading.intervalMillis,
        stale = isStale,
        color = finalTextColor,
        rangeColor = rangeColor,
        lateColor = nextReadingLateColor,
        cornerRadius = cornerRadius.dp,
        arrowSize = if (hasArrow) shownArrowSize else null,
    )

    // A stale value is drawn dimmed: the value, its arrow and its secondary value in
    // their own colours at reduced opacity, by the same stale flag as the bar's late
    // state. Their outline or shadow stays as it is, for legibility on any background.
    val valueAlpha = FloatingStaleValue.alpha(hasValue = glucosePoint != null, stale = isStale)
    val shownValueColor = valueColor.copy(alpha = valueColor.alpha * valueAlpha)
    val secondaryValueColor = finalTextColor.copy(alpha = 0.7f * valueAlpha)

    val valueContent: @Composable () -> Unit = {
        if (displayValues != null) {
            val dvs = displayValues

            if (isDynamicIsland && isVerticalIsland) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    FloatingStyledText(
                        text = dvs.primaryStr,
                        fontSize = fontSize,
                        fontFamily = fontFamily,
                        fontWeight = fontWeight,
                        textColor = shownValueColor,
                        outlineColor = textOutlineColor,
                        shadow = textShadow,
                        useOutline = useSubtleOutline,
                        textAlign = TextAlign.Center
                    )
                    if (showSecondary && !dvs.secondaryStr.isNullOrEmpty()) {
                        Spacer(Modifier.height(sideSecondarySpacing))
                        FloatingStyledText(
                            text = dvs.secondaryStr!!,
                            fontSize = sideSecondaryFontSize,
                            fontFamily = fontFamily,
                            fontWeight = fontWeight,
                            textColor = secondaryValueColor,
                            outlineColor = textOutlineColor,
                            shadow = textShadow,
                            useOutline = useSubtleOutline,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FloatingStyledText(
                        text = dvs.primaryStr,
                        fontSize = fontSize,
                        fontFamily = fontFamily,
                        fontWeight = fontWeight,
                        textColor = shownValueColor,
                        outlineColor = textOutlineColor,
                        shadow = textShadow,
                        useOutline = useSubtleOutline,
                        textAlign = TextAlign.End
                    )
                    if (showSecondary && !dvs.secondaryStr.isNullOrEmpty()) {
                        Spacer(Modifier.width(inlineSpacing))
                        FloatingStyledText(
                            text = dvs.secondaryStr!!,
                            fontSize = fontSize * 0.7f,
                            fontFamily = fontFamily,
                            fontWeight = fontWeight,
                            textColor = secondaryValueColor,
                            outlineColor = textOutlineColor,
                            shadow = textShadow,
                            useOutline = useSubtleOutline
                        )
                    }
                }
            }
        } else {
            FloatingStyledText(
                text = "---",
                fontSize = fontSize,
                fontFamily = fontFamily,
                fontWeight = fontWeight,
                textColor = finalTextColor,
                outlineColor = textOutlineColor,
                shadow = textShadow,
                useOutline = useSubtleOutline
            )
        }
    }

    val arrowContent: @Composable () -> Unit = {
        if (hasArrow) {
            val arrow: @Composable () -> Unit = {
                TrendIndicator(
                    trendResult = trendResult,
                    modifier = Modifier.size(shownArrowSize),
                    color = shownValueColor,
                    outlineColor = arrowOutlineColor,
                    shadowColor = arrowShadowColor
                )
            }
            val ringSlot = nextReading.arrowSlot
            if (ringSlot != null) {
                // The ring around the arrow: a slot just larger than the arrow, which stays in its middle.
                Box(
                    modifier = Modifier.size(ringSlot).then(nextReading.arrowRing),
                    contentAlignment = Alignment.Center
                ) {
                    arrow()
                }
            } else {
                arrow()
            }
        } else {
            Spacer(Modifier.size(1.dp))
        }
    }
    
    // Mirrored: arrow first, then the value (beside the camera on an island).
    val firstContent = if (isMirrored) arrowContent else valueContent
    val secondContent = if (isMirrored) valueContent else arrowContent

    // ROOT LAYOUT CHANGE: Use Column just for Vertical Offset Spacer if Island
    // We don't use 'Surface' as root for Island anymore, because we want split layout.
    
    // ROOT LAYOUT: Column for vertical offset spacer
    if (isDynamicIsland) {
        // Clip and click the pill itself, not the offset wrapper: the wrapper is
        // taller by the offset spacer, so its corner radius clamps differently
        // from the pill's own Surface and its arc cut into the bottom corners.
        val pillModifier = Modifier
            .clip(finalShape)
            .combinedClickable(
                interactionSource = overlayInteractionSource,
                indication = null,
                onLongClick = onPillLongPress,
                onClick = onPillTap,
            )
        CutoutOffsetLayout(
            edge = cutoutEdge,
            offset = verticalOffset.dp,
            modifier = reportDrawn
                .wrapContentSize()
                .then(dragModifier)
        ) {
            if (isVerticalIsland) {
                AsymmetricCenteringColumn(
                    modifier = pillModifier,
                    gap = finalGap,
                    horizontalAlignment = Alignment.CenterHorizontally,
                    backgroundContent = {
                        Surface(
                            color = finalBgColor,
                            shape = finalShape,
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(finalShape)
                                .then(nextReading.background)
                                .indication(overlayInteractionSource, overlayIndication)
                        ) {}
                    }
                ) {
                    Box(
                        modifier = Modifier.padding(
                            start = sideIslandHorizontalPadding,
                            end = sideIslandHorizontalPadding,
                            top = sideIslandVerticalPadding + sideIslandSplitPadding,
                            bottom = sideIslandSplitPadding
                        )
                    ) {
                        firstContent()
                    }
                    Box(
                        modifier = Modifier.padding(
                            start = sideIslandHorizontalPadding,
                            end = sideIslandHorizontalPadding,
                            top = sideIslandSplitPadding,
                            bottom = sideIslandVerticalPadding + sideIslandSplitPadding
                        )
                    ) {
                        secondContent()
                    }
                }
            } else {
                AsymmetricCenteringRow(
                    modifier = pillModifier,
                    gap = finalGap,
                    verticalAlignment = Alignment.CenterVertically,
                    backgroundContent = {
                        Surface(
                            color = finalBgColor,
                            shape = finalShape,
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(finalShape)
                                .then(nextReading.background)
                                .indication(overlayInteractionSource, overlayIndication)
                        ) {}
                    }
                ) {
                    Box(
                        modifier = Modifier.padding(
                            start = pillHorizontalPadding,
                            top = pillVerticalPadding,
                            bottom = pillVerticalPadding
                        )
                    ) {
                        firstContent()
                    }
                    Box(
                        modifier = Modifier.padding(
                            end = pillHorizontalPadding,
                            top = pillVerticalPadding,
                            bottom = pillVerticalPadding
                        )
                    ) {
                        secondContent()
                    }
                }
            }
        }
    } else {
        // ORIGINAL FLOATING LAYOUT (Unified Pill)
        Surface(
            color = finalBgColor,
            shape = finalShape,
            modifier = reportDrawn
                .wrapContentSize()
                .then(dragModifier)
                .clip(finalShape)
                .then(nextReading.background)
                .combinedClickable(
                    interactionSource = overlayInteractionSource,
                    indication = overlayIndication,
                    // No long press: holding the free pill is how a drag starts.
                    onClick = onPillTap,
                )
        ) {
            Row(
                modifier = Modifier.padding(horizontal = pillHorizontalPadding, vertical = pillVerticalPadding),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(inlineSpacing)
            ) {
                // No placeholder for a hidden arrow here: spacedBy would add a gap for it.
                if (isMirrored && hasArrow) arrowContent()
                valueContent()
                if (!isMirrored && hasArrow) arrowContent()
            }
        }
    }
}

/**
 * What the glucose notification shows, for the reading on the pill: its time
 * and age, the Δ, the chart and the IOB/COB line (when the notification shows
 * one). Tapping it opens the app.
 */
@Composable
fun FloatingDetailsCard(
    request: FloatingDetailsRequest,
    isDark: Boolean,
    onOpenApp: () -> Unit,
) {
    val point = request.point
    val sensorId = request.sensorId
    val isMmol = request.isMmol
    val viewMode = request.viewMode
    val displayGlucose = request.displayGlucose
    val context = LocalContext.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val cardWidth = FloatingDetailsCardWidth
    val chartHeight = 110.dp
    val chartWidthPx = with(density) { (cardWidth - 24.dp).roundToPx() }
    val chartHeightPx = with(density) { chartHeight.roundToPx() }
    val details by produceState<tk.glucodata.FloatingDetailsSource.Details?>(null, point.timestamp, sensorId, viewMode, isDark) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            tk.glucodata.FloatingDetailsSource.load(
                context, sensorId, isMmol, viewMode, chartWidthPx, chartHeightPx, isDark, displayGlucose,
            )
        }
    }
    val background = if (isDark) Color(0xF2202124) else Color(0xF2F6F4F1)
    val textColor = if (isDark) Color.White else Color(0xFF27231F)
    val minutes = ((System.currentTimeMillis() - point.timestamp) / 60_000L).coerceAtLeast(0L)
    val time = remember(point.timestamp) {
        android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(point.timestamp))
    }
    val header = buildList {
        add(time)
        add(context.getString(tk.glucodata.R.string.minutes_short_format, minutes.toInt()))
        details?.delta?.takeIf { it.isNotEmpty() }?.let { add("Δ $it") }
    }.joinToString(" · ")

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
            Text(header, color = textColor, fontSize = 14.sp)
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
        }
    }
}

@Composable
private fun FloatingStyledText(
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
    if (useOutline) {
        Box {
            Text(
                text = text,
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
                text = text,
                color = textColor,
                fontSize = fontSize.sp,
                fontFamily = fontFamily,
                fontWeight = fontWeight,
                textAlign = textAlign
            )
        }
    } else {
        Text(
            text = text,
            color = textColor,
            fontSize = fontSize.sp,
            fontFamily = fontFamily,
            fontWeight = fontWeight,
            textAlign = textAlign,
            style = TextStyle(shadow = shadow)
        )
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

@Composable
private fun CutoutOffsetLayout(
    edge: CutoutEdge,
    offset: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    when (edge) {
        CutoutEdge.LEFT -> Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
            if (offset > 0.dp) {
                Spacer(modifier = Modifier.width(offset))
            }
            content()
        }
        CutoutEdge.RIGHT -> Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
            content()
            if (offset > 0.dp) {
                Spacer(modifier = Modifier.width(offset))
            }
        }
        CutoutEdge.BOTTOM -> Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
            content()
            if (offset > 0.dp) {
                Spacer(modifier = Modifier.height(offset))
            }
        }
        else -> Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
            if (offset > 0.dp) {
                Spacer(modifier = Modifier.height(offset))
            }
            content()
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
 */
internal fun nextOverlayFreshnessCheckDelay(
    latestReadingMillis: Long,
    nowMillis: Long,
    freshnessWindowMillis: Long = Notify.glucosetimeout
): Long? {
    if (latestReadingMillis <= 0L) return null
    val untilStale = latestReadingMillis + freshnessWindowMillis - nowMillis
    if (untilStale < 0L) return null
    return (untilStale + 1L).coerceAtMost(OVERLAY_FRESHNESS_POLL_MS)
}

/**
 * The point the overlay may show at [nowMillis]: the latest one while it is within
 * the widget/dashboard freshness window, null (the no-data state) once it is not.
 */
internal fun overlayDisplayPoint(
    latestPoint: GlucosePoint?,
    snapshotMillis: Long,
    nowMillis: Long,
    freshnessWindowMillis: Long = Notify.glucosetimeout
): GlucosePoint? {
    val isFresh = DisplayDataState.resolve(
        sensorPresent = true,
        currentTimestampMillis = snapshotMillis,
        latestHistoryTimestampMillis = latestPoint?.timestamp ?: 0L,
        freshnessWindowMillis = freshnessWindowMillis,
        nowMillis = nowMillis
    ).isFresh
    return latestPoint?.takeIf { isFresh }
}
