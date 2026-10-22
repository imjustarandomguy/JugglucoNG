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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.emptyFlow
import kotlin.math.roundToInt
import tk.glucodata.data.settings.FloatingSettingsRepository
import tk.glucodata.ui.theme.MainFontFile
import tk.glucodata.ui.GlucosePaletteState
import tk.glucodata.ui.components.TrendIndicator
import tk.glucodata.DisplayedReading
import tk.glucodata.GlucoseValueTone
import tk.glucodata.Natives
import tk.glucodata.UiRefreshBus

/**
 * A stale reading on the pill: its last value stays, dimmed and without its arrow, beside
 * the next-reading ring's amber; "---" is for no value at all. Pure, see the tests.
 */
internal object FloatingStaleValue {
    /** Opacity of a stale value against a current one: clearly old, still readable. */
    const val DIMMED_ALPHA = 0.5f

    /**
     * The opacity factor for the value and its secondary value: [DIMMED_ALPHA] while
     * there is a value and it is [stale], the same flag the next-reading ring is late by;
     * 1 otherwise.
     */
    fun alpha(hasValue: Boolean, stale: Boolean): Float = if (hasValue && stale) DIMMED_ALPHA else 1f
}

/** Width of the details card; the service places its window by it. */
internal val FloatingDetailsCardWidth = 260.dp

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun FloatingGlucoseOverlay(
    repository: FloatingSettingsRepository,
    /**
     * The device's current reading, CurrentReadingHub's, as the service hands it: value,
     * arrow, freshness and the time the next one is due all come from it.
     */
    readingFlow: StateFlow<DisplayedReading>,
    /**
     * Called with the [DisplayedReading.revision] the pill composed, after each of its
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
    val showArrow by repository.showArrow.collectAsState(initial = true)
    val cornerRadius by repository.cornerRadius.collectAsState(initial = 28f)
    val opacity by repository.backgroundOpacity.collectAsState(initial = FloatingSettingsRepository.DEFAULT_BACKGROUND_OPACITY)
    val valueRangeColors by repository.valueRangeColors.collectAsState(initial = false)
    val isDynamicIsland by repository.isDynamicIslandEnabled.collectAsState(initial = false)
    val islandNudge by repository.islandNudge.collectAsState(initial = FloatingSettingsRepository.DEFAULT_ISLAND_NUDGE)
    val manualGap by repository.islandGap.collectAsState(initial = 0f)
    val useSubtleOutline by repository.useSubtleOutline.collectAsState(initial = false)
    val isMirrored by repository.isMirrored.collectAsState(initial = false)
    val showNextReading by repository.showNextReading.collectAsState(initial = true)

    // Metrics State (from Service WindowInsets)
    val cutoutData by cutoutDataFlow.collectAsState(initial = CutoutData(0.dp, CutoutEdge.NONE))
    val cutoutEdge = cutoutData.edge
    val cutoutSize = cutoutData.size

    val reading by readingFlow.collectAsState()
    val refreshRevision by UiRefreshBus.revision.collectAsState(initial = 0L)

    // Every layout (pill, side and top island) draws this: the hub's value, arrow and
    // freshness. A stale value is dimmed and loses its arrow and range colour, and the
    // time to the next reading turns late, all in the publish that made it stale.
    val look = FloatingPillLook.of(reading)

    // Reports what was composed, once applied: with no window (the screen just came on) it
    // is what the next window's first frame will draw.
    val composedRevision = look.revision
    SideEffect { onReadingComposed(composedRevision) }

    // The display arrow rate every surface draws, the dashboard's.
    val trendResult = remember(look.arrowRate) { FloatingPillLook.trend(look.arrowRate) }

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
        // ui-guardrails: allow hex_color - drawn straight onto other apps, outside the app theme
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
        if (toggle != null && reading.timeMillis > 0L) {
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

    // Range colours (the app-wide setting) only while the reading is current.
    // A filled pill is dark in either theme, so it takes the dark-theme shades.
    val paletteRevision = GlucosePaletteState.revision
    val primaryValue = look.value
    val isMmol = reading.isMmol
    // The value's range colour, whether or not the value is drawn in it: the time to the
    // next reading's ring fills in it too.
    val rangeColor = remember(
        primaryValue, isTransparent, isDarkTheme, isMmol, finalTextColor, refreshRevision, paletteRevision,
    ) {
        if (primaryValue == null) {
            finalTextColor
        } else {
            Color(
                GlucoseValueTone.valueColorArgb(
                    value = primaryValue,
                    isDark = !isTransparent || isDarkTheme,
                    isMmol = isMmol,
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
    val valueColor = if (look.stale || primaryValue == null || !valueRangeColors) finalTextColor else rangeColor

    // Time to the next reading: a ring around the trend arrow's place, in every layout
    // (the free pill and the island, upright or not), with the arrow on and a value
    // shown. Late in the range colours' amber, from the publish that makes the reading
    // stale; the arrow is gone then and the ring stays, alone in its place.
    val nextReadingLateColor = remember(isTransparent, isDarkTheme, paletteRevision) {
        Color(tk.glucodata.GlucoseRangeColors.valueBorderline(!isTransparent || isDarkTheme))
    }
    val hasArrow = showArrow && look.hasArrow
    val nextReading = nextReadingIndicator(
        enabled = showNextReading && showArrow && look.hasValue,
        readingTime = reading.timeMillis,
        nextExpectedAt = reading.nextExpectedAtMillis,
        late = look.stale,
        color = finalTextColor,
        rangeColor = rangeColor,
        lateColor = nextReadingLateColor,
        arrowSize = shownArrowSize,
    )
    val hasArrowSlot = hasArrow || nextReading.arrowSlot != null

    // A stale value is drawn dimmed: the value and its secondary value in their own
    // colours at reduced opacity, by the same stale flag as the ring's late state. Their
    // outline or shadow stays as it is, for legibility on any background.
    val valueAlpha = look.valueAlpha
    val shownValueColor = valueColor.copy(alpha = valueColor.alpha * valueAlpha)
    val secondaryValueColor = finalTextColor.copy(alpha = 0.7f * valueAlpha)

    val valueContent: @Composable () -> Unit = {
        if (look.hasValue) {
            val secondary = look.secondaryText?.takeIf { showSecondary }

            if (isDynamicIsland && isVerticalIsland) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    FloatingStyledText(
                        text = look.text,
                        fontSize = fontSize,
                        fontFamily = fontFamily,
                        fontWeight = fontWeight,
                        textColor = shownValueColor,
                        outlineColor = textOutlineColor,
                        shadow = textShadow,
                        useOutline = useSubtleOutline,
                        textAlign = TextAlign.Center
                    )
                    if (secondary != null) {
                        Spacer(Modifier.height(sideSecondarySpacing))
                        FloatingStyledText(
                            text = secondary,
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
                        text = look.text,
                        fontSize = fontSize,
                        fontFamily = fontFamily,
                        fontWeight = fontWeight,
                        textColor = shownValueColor,
                        outlineColor = textOutlineColor,
                        shadow = textShadow,
                        useOutline = useSubtleOutline,
                        textAlign = TextAlign.End
                    )
                    if (secondary != null) {
                        Spacer(Modifier.width(inlineSpacing))
                        FloatingStyledText(
                            text = secondary,
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
                text = look.text,
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
        if (hasArrowSlot) {
            val arrow: @Composable () -> Unit = {
                if (hasArrow) {
                    TrendIndicator(
                        trendResult = trendResult,
                        modifier = Modifier.size(shownArrowSize),
                        color = shownValueColor,
                        outlineColor = arrowOutlineColor,
                        shadowColor = arrowShadowColor
                    )
                }
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
            centre = cutoutData.centre,
            nudge = islandNudge.dp,
            modifier = Modifier
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
            modifier = Modifier
                .wrapContentSize()
                .then(dragModifier)
                .clip(finalShape)
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
                if (isMirrored && hasArrowSlot) arrowContent()
                valueContent()
                if (!isMirrored && hasArrowSlot) arrowContent()
            }
        }
    }
}

/**
 * What the glucose notification shows, for the reading on the pill: its time
 * and age, the Δ, the chart and the IOB/COB line (when the notification shows
 * one). Tapping it opens the app. Its background is [backgroundOpacity] opaque.
 * While open it follows the pill's reading, its age on the clock of [nowFlow], and a
 * stale reading is dimmed as on the pill; see FloatingDetailsCardState.
 */
@Composable
fun FloatingDetailsCard(
    readingFlow: StateFlow<DisplayedReading>,
    nowFlow: StateFlow<Long>,
    isDark: Boolean,
    onOpenApp: () -> Unit,
    // "+ Insulin" / "+ Food" under the details; null hides them (journal off).
    onQuickEntry: ((tk.glucodata.data.journal.JournalEntryType) -> Unit)? = null,
    backgroundOpacity: Float = FloatingSettingsRepository.DEFAULT_DETAILS_OPACITY,
) {
    val reading by readingFlow.collectAsState()
    val now by nowFlow.collectAsState()
    // The value's own view mode while it is shown; the sensor's for the chart of one gone.
    val viewMode = remember(reading.showsValue, reading.viewMode, reading.sensorId) {
        if (reading.showsValue) reading.viewMode else floatingViewMode(reading.sensorId)
    }
    val state = FloatingDetailsCardState.of(reading, Natives.getunit() == 1, viewMode, now) ?: return
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

/** The view mode of [sensorId], else 0. */
internal fun floatingViewMode(sensorId: String?): Int {
    if (sensorId.isNullOrEmpty()) return 0
    return runCatching {
        val sensor = Natives.getSensorUiSnapshot(sensorId)
        if (sensor != null && sensor.size >= 2) sensor[1].toInt() else 0
    }.getOrDefault(0)
}
