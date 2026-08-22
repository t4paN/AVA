package com.t4paN.AVA

import android.content.Context
import android.content.res.Configuration
import android.graphics.Rect
import android.util.DisplayMetrics
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Shared behaviour for the two screens only the caregiver ever reads.
 *
 * Both of them exist to be read *once*, during setup, by whoever is holding the phone
 * for the person who will use it — and that phone is already zoomed and enlarged for a
 * partially-sighted user. Everywhere else in AVA that zoom is the whole point; here it
 * is in the way. At 200% display size a paragraph of setup instructions turns into a
 * column of two-word lines that runs off the bottom of the screen, and the one person
 * we can safely assume has ordinary eyesight is the one being asked to read it.
 *
 * So these two screens, and only these two, render at the device's own density and at
 * a font scale of 1: what the display would look like with zoom and font size left
 * alone. The rest of AVA still honours both.
 */
abstract class CaregiverScreen : AppCompatActivity() {

    /**
     * Render at the device's stock density and font scale, ignoring the user's display
     * size and font size settings.
     *
     * `DENSITY_DEVICE_STABLE` is the phone's built-in density — the value the display
     * size setting scales *away* from — so this pins the screen to the hardware rather
     * than to whatever the setting happens to be.
     */
    override fun attachBaseContext(newBase: Context) {
        val config = Configuration(newBase.resources.configuration)
        config.fontScale = 1f
        config.densityDpi = DisplayMetrics.DENSITY_DEVICE_STABLE
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    /**
     * Keep [content] clear of the status bar, the navigation bar and any cutout.
     *
     * At `targetSdk 36` the window is edge to edge whether we ask for it or not, so
     * the black background is meant to fill the whole frame — but the text inside it
     * was running under the battery indicator at the top and the back/home buttons at
     * the bottom. Insets are *added* to whatever padding the layout already declares,
     * so the layout keeps owning its own margins.
     */
    protected fun padForSystemBars(content: View) {
        val base = Rect(
            content.paddingLeft, content.paddingTop,
            content.paddingRight, content.paddingBottom
        )
        ViewCompat.setOnApplyWindowInsetsListener(content) { view, windowInsets ->
            val bars = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(
                base.left + bars.left,
                base.top + bars.top,
                base.right + bars.right,
                base.bottom + bars.bottom
            )
            windowInsets
        }
        ViewCompat.requestApplyInsets(content)
    }
}
