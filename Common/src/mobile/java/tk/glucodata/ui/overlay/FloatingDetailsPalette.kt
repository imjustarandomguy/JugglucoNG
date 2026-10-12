package tk.glucodata.ui.overlay

import androidx.compose.ui.graphics.Color

/**
 * The floating details card's colours. The card is a window of its own drawn over other
 * apps, outside the app theme, so its surface and ink are named here instead of taken from
 * theme roles.
 */
object FloatingDetailsPalette {
    val darkSurface = Color(0xFF202124)
    val lightSurface = Color(0xFFF6F4F1)

    /** Text on [lightSurface]; on [darkSurface] it is white. */
    val lightInk = Color(0xFF27231F)
}
