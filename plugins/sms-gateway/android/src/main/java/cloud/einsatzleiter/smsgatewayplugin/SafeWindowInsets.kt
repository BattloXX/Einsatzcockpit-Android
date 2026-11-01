package cloud.einsatzleiter.smsgatewayplugin

import android.view.Window
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/** One inset owner for the entire window, including action bar, WebView and refresh spinner. */
object SafeWindowInsets {
    @JvmStatic
    fun install(window: Window) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val decor = window.decorView
        val initial = SafePadding(decor.paddingLeft, decor.paddingTop, decor.paddingRight, decor.paddingBottom)
        val types = WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or
            WindowInsetsCompat.Type.ime()
        ViewCompat.setOnApplyWindowInsetsListener(decor) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val padding = safeWindowPadding(
                initial, SafePadding(bars.left, bars.top, bars.right, bars.bottom),
                SafePadding(ime.left, ime.top, ime.right, ime.bottom),
            )
            view.setPadding(padding.left, padding.top, padding.right, padding.bottom)
            // Descendants (including Chromium safe-area env values) must not apply them a second time.
            WindowInsetsCompat.Builder(insets).setInsets(types, Insets.NONE).build()
        }
        ViewCompat.requestApplyInsets(decor)
    }
}

internal data class SafePadding(val left: Int, val top: Int, val right: Int, val bottom: Int)

internal fun safeWindowPadding(initial: SafePadding, bars: SafePadding, ime: SafePadding) = SafePadding(
    initial.left + maxOf(bars.left, ime.left),
    initial.top + maxOf(bars.top, ime.top),
    initial.right + maxOf(bars.right, ime.right),
    initial.bottom + maxOf(bars.bottom, ime.bottom),
)
