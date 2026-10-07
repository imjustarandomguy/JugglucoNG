package tk.glucodata.ui.journal

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties

// Darker than Material's 0.32 modal scrim: at 0.32 the dashboard's chart and colours
// still competed with the menu labels.
private const val JournalFabMenuScrimAlpha = 0.55f

/**
 * Dims the page behind the open menu so the labels do not blend into it.
 * The focusable popup already turns a tap outside the menu into a dismiss;
 * the scrim does the same on its own, so the page below is never reachable.
 * A pointer handler rather than clickable(): the scrim must not take focus or
 * be announced; the FAB stays the accessible way to close the menu.
 */
@Composable
internal fun JournalFabMenuScrim(
    menuProgress: Float,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier
) {
    val color = MaterialTheme.colorScheme.scrim
    // menuProgress changes every animation frame: read the latest callback
    // instead of restarting the gesture detector with it.
    val dismiss by rememberUpdatedState(onDismissRequest)
    Canvas(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures { dismiss() } }
    ) {
        drawRect(color = color, alpha = JournalFabMenuScrimAlpha * menuProgress.coerceIn(0f, 1f))
    }
}

/** Anchors the menu above the FAB and consumes taps used to dismiss it. */
@Composable
internal fun JournalFabMenuPopup(
    menuProgress: Float,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val gapPx = with(LocalDensity.current) { 10.dp.roundToPx() }
    // This zero-size anchor sits at the FAB's top end. The popup grows upward
    // from it, so its position does not depend on the button or menu height.
    Box(modifier = modifier) {
        Popup(
            alignment = Alignment.BottomEnd,
            offset = IntOffset(0, -gapPx),
            onDismissRequest = onDismissRequest,
            properties = PopupProperties(focusable = true)
        ) {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .graphicsLayer {
                        alpha = menuProgress.coerceIn(0f, 1f)
                        translationY = 8.dp.toPx() * (1f - menuProgress)
                    }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismissRequest
                    ),
                content = content
            )
        }
    }
}
