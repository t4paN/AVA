//CallOverlayController.kt

package com.t4paN.AVA

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.TextViewCompat

/**
 * CallOverlayController - Unified overlay manager for AVA
 *
 * Owns all overlay UI: recording cancel, call confirmation, ambiguous selection.
 * Services call methods here instead of managing their own overlays.
 *
 * State machine:
 *   HIDDEN → RECORDING → (CANCELLED | ANNOUNCING/SELECTING) → CALLING → HIDDEN
 */

@SuppressLint("StaticFieldLeak")
object CallOverlayController {

    private const val TAG = "CallOverlayController"

    // Colors
    private const val COLOR_BLACK = 0xFF000000.toInt()
    private const val COLOR_RED = 0xFFCC0000.toInt()
    private const val COLOR_GREEN = 0xFF00AA00.toInt()
    private const val COLOR_ORANGE = 0xFFFF8C00.toInt()
    private const val COLOR_BLUE = 0xFF4169E1.toInt()

    // Layout constants
    private const val CORNER_RADIUS_DP = 24f
    private const val MARGIN_DP = 24f
    private const val GAP_DP = 16f
    private const val CANCEL_HEIGHT_PERCENT = 0.42f

    enum class State { HIDDEN, RECORDING, ANNOUNCING, SELECTING, CALLING, CANCELLED }

    private var appContext: Context? = null
    private var windowManager: WindowManager? = null
    private var currentOverlay: View? = null
    private var currentState = State.HIDDEN

    // Callbacks for current overlay
    private var onCancelCallback: (() -> Unit)? = null
    private var onConfirmCallback: (() -> Unit)? = null
    private var onSelectCallback: ((Int) -> Unit)? = null

    /**
     * Initialize with Application context. Call once from Application.onCreate().
     */
    fun init(applicationContext: Context) {
        appContext = applicationContext
        windowManager = applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        Log.d(TAG, "CallOverlayController initialized")
    }

    /**
     * Get system bar insets (status bar and nav bar heights)
     */
    private fun getSystemBarInsets(ctx: Context): Pair<Int, Int> {
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = wm.currentWindowMetrics
        val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
            WindowInsets.Type.systemBars()
        )
        return Pair(insets.top, insets.bottom)
    }

    /**
     * Show recording cancel overlay.
     * Big red "ΑΚΥΡΩΣΗ" button, bottom 42% of screen.
     */
    fun showRecording(onCancel: () -> Unit) {
        Log.d(TAG, "showRecording()")
        dismissInternal()

        val ctx = appContext ?: run {
            Log.e(TAG, "Not initialized")
            return
        }

        onCancelCallback = onCancel
        currentState = State.RECORDING

        val metrics = ctx.resources.displayMetrics
        val screenHeight = metrics.heightPixels
        val screenWidth = metrics.widthPixels
        val density = metrics.density

        val (statusBar, navBar) = getSystemBarInsets(ctx)

        val marginPx = (MARGIN_DP * density).toInt()
        val cornerRadiusPx = CORNER_RADIUS_DP * density
        val cancelHeight = (screenHeight * CANCEL_HEIGHT_PERCENT).toInt()

        // Black fullscreen container
        val container = FrameLayout(ctx).apply {
            setBackgroundColor(COLOR_BLACK)
        }

        // Red cancel button
        val cancelButton = createButton(
            ctx = ctx,
            color = COLOR_RED,
            cornerRadius = cornerRadiusPx,
            text = "ΑΚΥΡΩΣΗ",
            textSizeRange = Pair(24, 48)
        ) {
            handleCancel()
        }

        val buttonParams = FrameLayout.LayoutParams(
            screenWidth - (marginPx * 2),
            cancelHeight - marginPx
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = marginPx + navBar
        }
        container.addView(cancelButton, buttonParams)

        showOverlay(container)
    }

    /**
     * Show call announcement overlay.
     *
     * @param autoCall true = red button with name only (auto-dials after TTS)
     *                 false = green call button + red cancel button
     */
    fun showAnnouncing(
        contactName: String,
        autoCall: Boolean,
        onConfirm: (() -> Unit)?,
        onCancel: () -> Unit
    ) {
        Log.d(TAG, "showAnnouncing(name=$contactName, autoCall=$autoCall)")
        dismissInternal()

        val ctx = appContext ?: run {
            Log.e(TAG, "Not initialized")
            return
        }

        onConfirmCallback = onConfirm
        onCancelCallback = onCancel
        currentState = State.ANNOUNCING

        val metrics = ctx.resources.displayMetrics
        val screenHeight = metrics.heightPixels
        val screenWidth = metrics.widthPixels
        val density = metrics.density

        val (statusBar, navBar) = getSystemBarInsets(ctx)

        val marginPx = (MARGIN_DP * density).toInt()
        val gapPx = (GAP_DP * density).toInt()
        val cornerRadiusPx = CORNER_RADIUS_DP * density
        val cancelHeight = (screenHeight * CANCEL_HEIGHT_PERCENT).toInt()

        val formattedName = formatForDisplay(contactName)

        // Black fullscreen container
        val container = FrameLayout(ctx).apply {
            setBackgroundColor(COLOR_BLACK)
        }

        if (autoCall) {
            // Auto-call mode: single red button with name
            val cancelButton = createButton(
                ctx = ctx,
                color = COLOR_RED,
                cornerRadius = cornerRadiusPx,
                text = formattedName,
                textSizeRange = Pair(24, 72)
            ) {
                handleCancel()
            }

            val buttonParams = FrameLayout.LayoutParams(
                screenWidth - (marginPx * 2),
                cancelHeight - marginPx
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = marginPx + navBar
            }
            container.addView(cancelButton, buttonParams)

        } else {
            // Manual mode: green confirm + red cancel stacked vertically
            val safeHeight = screenHeight - statusBar - navBar
            val redHeight = cancelHeight - marginPx
            val greenHeight = safeHeight - redHeight - gapPx - (marginPx * 2)

            val buttonContainer = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            }

            // Green confirm button
            val greenButton = createButton(
                ctx = ctx,
                color = COLOR_GREEN,
                cornerRadius = cornerRadiusPx,
                text = formattedName,
                textSizeRange = Pair(24, 72)
            ) {
                handleConfirm()
            }
            val greenParams = LinearLayout.LayoutParams(
                screenWidth - (marginPx * 2),
                greenHeight
            ).apply {
                bottomMargin = gapPx
            }
            buttonContainer.addView(greenButton, greenParams)

            // Red cancel button
            val redButton = createButton(
                ctx = ctx,
                color = COLOR_RED,
                cornerRadius = cornerRadiusPx,
                text = "ΑΚΥΡΟ",
                textSizeRange = Pair(24, 48)
            ) {
                handleCancel()
            }
            val redParams = LinearLayout.LayoutParams(
                screenWidth - (marginPx * 2),
                redHeight
            )
            buttonContainer.addView(redButton, redParams)

            val containerParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM
                bottomMargin = marginPx + navBar
            }
            container.addView(buttonContainer, containerParams)
        }

        showOverlay(container)
    }

    /**
     * Show ambiguous match selection overlay.
     * Orange box (left) + blue box (right) with initials, red cancel zone at bottom.
     */
    fun showSelection(
        name1: String,
        name2: String,
        onSelect: (index: Int) -> Unit,
        onCancel: () -> Unit
    ) {
        Log.d(TAG, "showSelection(name1=$name1, name2=$name2)")
        dismissInternal()

        val ctx = appContext ?: run {
            Log.e(TAG, "Not initialized")
            return
        }

        onSelectCallback = onSelect
        onCancelCallback = onCancel
        currentState = State.SELECTING

        val metrics = ctx.resources.displayMetrics
        val screenHeight = metrics.heightPixels
        val screenWidth = metrics.widthPixels
        val density = metrics.density

        val (statusBar, navBar) = getSystemBarInsets(ctx)

        val marginPx = (MARGIN_DP * density).toInt()
        val gapPx = (GAP_DP * density).toInt()
        val cornerRadiusPx = CORNER_RADIUS_DP * density
        val cancelHeight = (screenHeight * CANCEL_HEIGHT_PERCENT).toInt()

        val initials1 = formatForSelection(name1)
        val initials2 = formatForSelection(name2)

        // Black fullscreen container
        val container = FrameLayout(ctx).apply {
            setBackgroundColor(COLOR_BLACK)
        }

        // Selection boxes area - account for status bar at top and cancel zone at bottom
        val safeHeight = screenHeight - statusBar - navBar
        val boxesHeight = safeHeight - cancelHeight - marginPx - gapPx
        val boxWidth = (screenWidth - (marginPx * 2) - gapPx) / 2

        // Orange box (left) - index 0
        val orangeBox = createButton(
            ctx = ctx,
            color = COLOR_ORANGE,
            cornerRadius = cornerRadiusPx,
            text = initials1,
            textSizeRange = Pair(48, 144)
        ) {
            handleSelect(0)
        }
        val orangeParams = FrameLayout.LayoutParams(boxWidth, boxesHeight).apply {
            gravity = Gravity.TOP or Gravity.START
            topMargin = marginPx + statusBar
            leftMargin = marginPx
        }
        container.addView(orangeBox, orangeParams)

        // Blue box (right) - index 1
        val blueBox = createButton(
            ctx = ctx,
            color = COLOR_BLUE,
            cornerRadius = cornerRadiusPx,
            text = initials2,
            textSizeRange = Pair(48, 144)
        ) {
            handleSelect(1)
        }
        val blueParams = FrameLayout.LayoutParams(boxWidth, boxesHeight).apply {
            gravity = Gravity.TOP or Gravity.END
            topMargin = marginPx + statusBar
            rightMargin = marginPx
        }
        container.addView(blueBox, blueParams)

        // Red cancel zone at bottom (rounded top corners only)
        val cancelZone = FrameLayout(ctx).apply {
            background = GradientDrawable().apply {
                setColor(COLOR_RED)
                cornerRadii = floatArrayOf(
                    cornerRadiusPx, cornerRadiusPx,  // top-left
                    cornerRadiusPx, cornerRadiusPx,  // top-right
                    0f, 0f,                          // bottom-right
                    0f, 0f                           // bottom-left
                )
            }
            setOnTouchListener { v, event ->
                if (event.action == MotionEvent.ACTION_DOWN) {
                    v.performClick()
                    handleCancel()
                }
                true
            }
        }

        val cancelText = TextView(ctx).apply {
            text = "ΑΚΥΡΟ"
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
            cancelText, 24, 48, 2, TypedValue.COMPLEX_UNIT_SP
        )
        cancelZone.addView(cancelText, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        val cancelParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            cancelHeight
        ).apply {
            gravity = Gravity.BOTTOM
            bottomMargin = navBar
        }
        container.addView(cancelZone, cancelParams)

        showOverlay(container)
    }

    /**
     * Force dismiss from any state. Safe to call multiple times.
     */
    fun dismiss() {
        Log.d(TAG, "dismiss() called, state=$currentState")
        dismissInternal()
    }

    /**
     * Get current state for debugging/logging.
     */
    fun currentState(): State = currentState

    // ==================== PRIVATE HELPERS ====================

    private fun dismissInternal() {
        currentOverlay?.let { view ->
            try {
                windowManager?.removeView(view)
                Log.d(TAG, "Overlay removed")
            } catch (e: Exception) {
                Log.e(TAG, "Error removing overlay", e)
            }
        }
        currentOverlay = null
        currentState = State.HIDDEN
        onCancelCallback = null
        onConfirmCallback = null
        onSelectCallback = null
    }

    private fun showOverlay(view: View) {
        val ctx = appContext ?: return

        // Get real screen size including system bars
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val bounds = wm.maximumWindowMetrics.bounds

        val params = WindowManager.LayoutParams(
            bounds.width(),
            bounds.height(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        try {
            windowManager?.addView(view, params)
            currentOverlay = view
            Log.d(TAG, "Overlay shown, state=$currentState")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add overlay", e)
        }
    }

    private fun createButton(
        ctx: Context,
        color: Int,
        cornerRadius: Float,
        text: String,
        textSizeRange: Pair<Int, Int>,
        onClick: () -> Unit
    ): FrameLayout {
        val density = ctx.resources.displayMetrics.density
        val paddingPx = (16 * density).toInt()

        return FrameLayout(ctx).apply {
            background = GradientDrawable().apply {
                setColor(color)
                this.cornerRadius = cornerRadius
            }
            setOnTouchListener { v, event ->
                if (event.action == MotionEvent.ACTION_DOWN) {
                    v.performClick()
                    onClick()
                }
                true
            }

            val textView = TextView(ctx).apply {
                this.text = text
                setTextColor(Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setPadding(paddingPx, paddingPx, paddingPx, paddingPx)
            }
            TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                textView,
                textSizeRange.first,
                textSizeRange.second,
                2,
                TypedValue.COMPLEX_UNIT_SP
            )

            addView(textView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
        }
    }

    private fun handleCancel() {
        Log.d(TAG, "handleCancel(), state=$currentState")
        val callback = onCancelCallback
        currentState = State.CANCELLED
        // Don't dismiss here - let the callback handle it
        callback?.invoke()
    }

    private fun handleConfirm() {
        Log.d(TAG, "handleConfirm(), state=$currentState")
        val callback = onConfirmCallback
        currentState = State.CALLING
        callback?.invoke()
    }

    private fun handleSelect(index: Int) {
        Log.d(TAG, "handleSelect(index=$index), state=$currentState")
        val callback = onSelectCallback
        // Don't change state here - caller will show announcing overlay
        callback?.invoke(index)
    }

    // ==================== TEXT FORMATTING ====================

    private fun stripRoutingSuffix(name: String): String {
        val suffixes = listOf("viber", "whatsapp", "signal")
        val parts = name.trim().split("\\s+".toRegex())
        return if (parts.isNotEmpty() && parts.last().lowercase() in suffixes) {
            parts.dropLast(1).joinToString(" ")
        } else {
            name
        }
    }

    private fun stripTonos(text: String): String {
        return text
            .replace('ά', 'α').replace('Ά', 'Α')
            .replace('έ', 'ε').replace('Έ', 'Ε')
            .replace('ή', 'η').replace('Ή', 'Η')
            .replace('ί', 'ι').replace('Ί', 'Ι')
            .replace('ό', 'ο').replace('Ό', 'Ο')
            .replace('ύ', 'υ').replace('Ύ', 'Υ')
            .replace('ώ', 'ω').replace('Ώ', 'Ω')
            .replace('ϊ', 'ι').replace('ϋ', 'υ')
            .replace('ΐ', 'ι').replace('ΰ', 'υ')
    }

    private fun formatForDisplay(name: String): String {
        return stripRoutingSuffix(name)
            .uppercase()
            .let { stripTonos(it) }
            .split("\\s+".toRegex())
            .joinToString("\n")
    }

    private fun formatForSelection(name: String): String {
        return stripRoutingSuffix(name)
            .uppercase()
            .let { stripTonos(it) }
            .split("\\s+".toRegex())
            .mapNotNull { it.firstOrNull()?.toString() }
            .joinToString("\n")
    }
}
