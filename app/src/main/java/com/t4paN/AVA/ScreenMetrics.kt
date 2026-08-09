// ScreenMetrics.kt

package com.t4paN.AVA

import android.content.Context
import android.view.WindowManager

/**
 * Full display size, including the status and navigation bars.
 *
 * Calibration records tap positions as a fraction of a *screenshot*, and a
 * screenshot covers the whole screen. Accessibility gestures are dispatched in
 * whole-screen coordinates too. But `resources.displayMetrics` reports the
 * app-usable area, which excludes the system bars — so multiplying a screenshot
 * fraction by it produced a vertical error that grew toward the bottom of the
 * screen, and varied with navigation mode (gesture vs 3-button) and OEM skin.
 */
object ScreenMetrics {

    /** Width/height in pixels of the whole display. */
    fun realSize(context: Context): Pair<Int, Int> {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val bounds = wm.currentWindowMetrics.bounds
        return bounds.width() to bounds.height()
    }
}
