package tk.glucodata.ui.overlay

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Uniform whole-pill press highlight for the floating overlay, driven by the pill's
 * own Compose frame clock.
 *
 * Material's `ripple(bounded = true)` delegates to Android's platform `RippleDrawable`,
 * which expands radially from the tap hotspot on one side of the camera cutout and relies
 * on Choreographer / RenderThread animation frames. On the first tap in a while with
 * "Details on tap" enabled, creating the details window on release (together with One UI's
 * background Choreographer throttling) can starve the ripple after its first few frames,
 * leaving only the tapped side of the cutout flashed grey.
 *
 * Drawing a uniform tint across the whole pill surface from the first press frame and
 * fading it out on the pill's `FloatingPillFrameClock`-backed recomposer keeps both sides
 * of the camera cutout lit together on every tap.
 */
internal data class FloatingPillPressIndication(val color: Color) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode =
        FloatingPillPressNode(interactionSource, color)

    companion object {
        /** Minimum time a completed tap stays lit before fading out, so quick taps still show. */
        const val MIN_HOLD_MS = 60L

        /** Fade-out duration after release or cancel. */
        const val FADE_OUT_MS = 160

        /** Remaining hold delay in ms before fading out a tap held for [heldMs]. */
        fun releaseHoldDelayMs(heldMs: Long): Long =
            (MIN_HOLD_MS - heldMs.coerceAtLeast(0L)).coerceAtLeast(0L)
    }
}

private class FloatingPillPressNode(
    private val interactionSource: InteractionSource,
    private val color: Color,
) : Modifier.Node(), DrawModifierNode {
    private val alpha = Animatable(0f)
    private var activePresses = 0
    private var pressedAtMs = 0L
    private var fadeJob: Job? = null

    override fun onAttach() {
        coroutineScope.launch {
            interactionSource.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> {
                        activePresses++
                        pressedAtMs = SystemClock.uptimeMillis()
                        fadeJob?.cancel()
                        fadeJob = null
                        alpha.snapTo(1f)
                        invalidateDraw()
                    }
                    is PressInteraction.Release -> {
                        activePresses = (activePresses - 1).coerceAtLeast(0)
                        if (activePresses == 0) {
                            val holdMs = FloatingPillPressIndication.releaseHoldDelayMs(
                                SystemClock.uptimeMillis() - pressedAtMs
                            )
                            startFadeOut(holdMs)
                        }
                    }
                    is PressInteraction.Cancel -> {
                        activePresses = (activePresses - 1).coerceAtLeast(0)
                        if (activePresses == 0) {
                            startFadeOut(holdMs = 0L)
                        }
                    }
                }
            }
        }
    }

    private fun startFadeOut(holdMs: Long) {
        fadeJob?.cancel()
        fadeJob = coroutineScope.launch {
            if (holdMs > 0L) delay(holdMs)
            alpha.animateTo(
                targetValue = 0f,
                animationSpec = tween(
                    durationMillis = FloatingPillPressIndication.FADE_OUT_MS,
                    easing = FastOutSlowInEasing,
                ),
            )
        }
    }

    override fun onDetach() {
        activePresses = 0
        fadeJob?.cancel()
        fadeJob = null
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        val progress = alpha.value
        if (progress > 0f) {
            drawRect(color = color.copy(alpha = color.alpha * progress))
        }
    }
}
