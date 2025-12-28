// HapticGuideManager.kt

package com.t4paN.AVA

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlin.math.hypot

/**
 * HapticGuideManager - Visual + haptic guide for blind/low-vision users
 * 
 * Shows pulsing purple dot over app buttons (e.g., Viber's "Free Call").
 * Haptic feedback intensifies as finger approaches target.
 * 
 * Positions are hardcoded defaults + caregiver x/y adjustments via prefs.
 */
object HapticGuideManager {
    private const val TAG = "HapticGuideManager"
    private const val PREFS_NAME = "ava_haptic_guide"
    
    // Hardcoded defaults (x%, y%) - measured from real devices
    private val defaults = mapOf(
        "com.viber.voip" to Pair(0.75f, 0.08f)
    )
    
    // Visual
    private const val DOT_RADIUS_DP = 25f
    private const val DOT_COLOR = 0xFF2aff00.toInt()       // Bright green
    private const val DOT_PULSE_COLOR = 0xFF2aff00.toInt()
    private const val BG_COLOR = 0xDD000000.toInt()        // 87% black
    private const val CANCEL_COLOR = 0xFFCC0000.toInt()
    
    // Haptic zones (dp from target)
    private const val ZONE_FAR = 200f
    private const val ZONE_NEAR = 120f
    private const val ZONE_CLOSE = 60f

    // Haptic timing
    private const val PULSE_FAR_ON = 50L
    private const val PULSE_FAR_OFF = 600L
    private const val PULSE_NEAR_ON = 60L
    private const val PULSE_NEAR_OFF = 400L
    private const val PULSE_CLOSE_ON = 40L
    private const val PULSE_CLOSE_OFF = 80L

    // Amplitudes
    private const val AMP_FAR = 30
    private const val AMP_NEAR = 60
    private const val AMP_CLOSE = 120
    private const val AMP_TARGET = 255
    
    // Adjustment step for caregiver controls
    const val ADJUST_STEP = 0.03f
    
    private var windowManager: WindowManager? = null
    private var vibrator: Vibrator? = null
    private var overlayView: GuideOverlayView? = null
    private var handler = Handler(Looper.getMainLooper())
    private var hapticRunnable: Runnable? = null
    private var currentZone = Zone.NONE
    
    private enum class Zone { NONE, FAR, NEAR, CLOSE, TARGET }
    
    // ==================== PUBLIC API ====================
    
    fun start(
        context: Context,
        packageName: String,
        onTapped: (() -> Unit)? = null,
        onCancelled: (() -> Unit)? = null
    ) {
        if (overlayView != null) {
            Log.w(TAG, "Already showing")
            return
        }
        
        val (x, y) = getPosition(context, packageName)
        if (x < 0) {
            Log.w(TAG, "No position for $packageName")
            return
        }
        
        Log.i(TAG, "Starting guide for $packageName at ($x, $y)")
        
        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        
        val density = context.resources.displayMetrics.density
        overlayView = GuideOverlayView(context, x, y, density, handler, onTapped, onCancelled)
        
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            
            // Allow drawing into notch/cutout area
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        
        try {
            windowManager?.addView(overlayView, params)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add overlay", e)
            overlayView = null
        }
    }
    
    fun stop() {
        stopHaptics()
        overlayView?.fadeOut {
            try {
                windowManager?.removeView(overlayView)
            } catch (e: Exception) {}
            overlayView = null
        }
    }
    
    fun forceStop() {
        stopHaptics()
        try {
            windowManager?.removeView(overlayView)
        } catch (e: Exception) {}
        overlayView = null
    }
    
    fun hasPosition(packageName: String) = defaults.containsKey(packageName)
    
    fun getPosition(context: Context, packageName: String): Pair<Float, Float> {
        val default = defaults[packageName] ?: return Pair(-1f, -1f)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val dx = prefs.getFloat("${packageName}_dx", 0f)
        val dy = prefs.getFloat("${packageName}_dy", 0f)
        return Pair(
            (default.first + dx).coerceIn(0f, 1f),
            (default.second + dy).coerceIn(0f, 1f)
        )
    }
    
    fun adjustX(context: Context, packageName: String, delta: Float) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = prefs.getFloat("${packageName}_dx", 0f)
        prefs.edit().putFloat("${packageName}_dx", current + delta).apply()
        Log.d(TAG, "$packageName dx now ${current + delta}")
    }
    
    fun adjustY(context: Context, packageName: String, delta: Float) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = prefs.getFloat("${packageName}_dy", 0f)
        prefs.edit().putFloat("${packageName}_dy", current + delta).apply()
        Log.d(TAG, "$packageName dy now ${current + delta}")
    }
    
    fun resetAdjustments(context: Context, packageName: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove("${packageName}_dx")
            .remove("${packageName}_dy")
            .apply()
    }
    
    // ==================== HAPTICS ====================
    
    private fun updateZone(zone: Zone) {
        if (zone == currentZone) return
        currentZone = zone
        stopHaptics()
        
        when (zone) {
            Zone.NONE -> {}
            Zone.FAR -> pulse(PULSE_FAR_ON, PULSE_FAR_OFF, AMP_FAR)
            Zone.NEAR -> pulse(PULSE_NEAR_ON, PULSE_NEAR_OFF, AMP_NEAR)
            Zone.CLOSE -> pulse(PULSE_CLOSE_ON, PULSE_CLOSE_OFF, AMP_CLOSE)
            Zone.TARGET -> constant(AMP_TARGET)
        }
    }
    
    private fun pulse(on: Long, off: Long, amp: Int) {
        hapticRunnable = object : Runnable {
            override fun run() {
                vibe(on, amp)
                handler.postDelayed(this, on + off)
            }
        }
        hapticRunnable?.run()
    }
    
    private fun constant(amp: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createOneShot(10000, amp))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(10000)
        }
    }
    
    private fun vibe(ms: Long, amp: Int) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(ms, amp))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(ms)
            }
        } catch (_: Exception) {}
    }
    
    private fun stopHaptics() {
        hapticRunnable?.let { handler.removeCallbacks(it) }
        hapticRunnable = null
        vibrator?.cancel()
        currentZone = Zone.NONE
    }
    
    private fun vibeShort() = vibe(50, 200)
    
    // ==================== OVERLAY VIEW ====================
    
    private class GuideOverlayView(
        context: Context,
        private val xPct: Float,
        private val yPct: Float,
        private val density: Float,
        private val handler: Handler,
        private val onTapped: (() -> Unit)?,
        private val onCancelled: (() -> Unit)?
    ) : View(context) {
        
        private val dotR = DOT_RADIUS_DP * density
        private val sw = resources.displayMetrics.widthPixels.toFloat()
        private val sh = resources.displayMetrics.heightPixels.toFloat()
        private val dotX = xPct * sw
        private val dotY = yPct * sh
        
        private val zoneFar = ZONE_FAR * density
        private val zoneNear = ZONE_NEAR * density
        private val zoneClose = ZONE_CLOSE * density
        
        private var pulse = 0f
        private var alpha = 1f
        private var fading = false
        private var passiveMode = false  // Dot visible but not interactive
        
        private val bgPaint = Paint().apply { color = BG_COLOR }
        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = DOT_COLOR }
        private val pulsePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = DOT_PULSE_COLOR
            style = Paint.Style.STROKE
            strokeWidth = 6 * density
        }
        private val cancelPaint = Paint().apply { color = CANCEL_COLOR }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 32 * density
            textAlign = Paint.Align.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        
        private val anim = object : Runnable {
            override fun run() {
                if (!fading) {
                    pulse = (pulse + 0.15f) % (2 * Math.PI.toFloat())
                    invalidate()
                    handler.postDelayed(this, 40)
                }
            }
        }
        
        init { handler.post(anim) }
        
        override fun onDraw(canvas: Canvas) {
            val a = (255 * alpha).toInt()
            
            // In passive mode, only draw the dot (no background, no cancel zone)
            if (passiveMode) {
                // Pulse rings
                val p1 = dotR + 15 * density * (1 + Math.sin(pulse.toDouble()).toFloat())
                val p2 = dotR + 30 * density * (1 + Math.sin((pulse - 0.5).toDouble()).toFloat())
                pulsePaint.alpha = ((80 * alpha) * (1 - Math.sin(pulse.toDouble()).toFloat() * 0.5f)).toInt()
                canvas.drawCircle(dotX, dotY, p2, pulsePaint)
                pulsePaint.alpha = ((120 * alpha) * (1 - Math.sin(pulse.toDouble()).toFloat() * 0.5f)).toInt()
                canvas.drawCircle(dotX, dotY, p1, pulsePaint)
                
                // Solid dot
                dotPaint.alpha = a
                canvas.drawCircle(dotX, dotY, dotR, dotPaint)
                return
            }
            
            // Full overlay mode
            bgPaint.alpha = a
            canvas.drawRect(0f, 0f, sw, sh, bgPaint)
            
            // Cancel zone
            val cancelTop = sh * 2f / 3f
            cancelPaint.alpha = a
            canvas.drawRect(0f, cancelTop, sw, sh, cancelPaint)
            textPaint.alpha = a
            canvas.drawText("ΑΚΥΡΟ", sw / 2f, cancelTop + (sh - cancelTop) / 2f + 12 * density, textPaint)
            
            // Pulse rings
            val p1 = dotR + 15 * density * (1 + Math.sin(pulse.toDouble()).toFloat())
            val p2 = dotR + 30 * density * (1 + Math.sin((pulse - 0.5).toDouble()).toFloat())
            pulsePaint.alpha = ((80 * alpha) * (1 - Math.sin(pulse.toDouble()).toFloat() * 0.5f)).toInt()
            canvas.drawCircle(dotX, dotY, p2, pulsePaint)
            pulsePaint.alpha = ((120 * alpha) * (1 - Math.sin(pulse.toDouble()).toFloat() * 0.5f)).toInt()
            canvas.drawCircle(dotX, dotY, p1, pulsePaint)
            
            // Solid dot
            dotPaint.alpha = a
            canvas.drawCircle(dotX, dotY, dotR, dotPaint)
        }
        
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (fading || passiveMode) return false
            
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (event.y > sh * 2f / 3f) {
                        vibeShort()
                        onCancelled?.invoke()
                        return true
                    }
                    updateZone(calcZone(event.x, event.y))
                    if (hypot(event.x - dotX, event.y - dotY) <= dotR * 1.5f) {
                        vibeShort()
                        stopHaptics()
                        passiveMode = true
                        invalidate()
                        
                        // Make overlay pass-through so taps go to Viber
                        try {
                            val params = layoutParams as? WindowManager.LayoutParams
                            params?.let {
                                it.flags = it.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                                windowManager?.updateViewLayout(this, it)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to update layout params", e)
                        }
                        
                        onTapped?.invoke()
                        return true
                    }
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    updateZone(calcZone(event.x, event.y))
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    stopHaptics()
                    return true
                }
            }
            return true
        }
        
        private fun calcZone(x: Float, y: Float): Zone {
            val d = hypot(x - dotX, y - dotY)
            return when {
                d <= dotR -> Zone.TARGET
                d <= zoneClose -> Zone.CLOSE
                d <= zoneNear -> Zone.NEAR
                d <= zoneFar -> Zone.FAR
                else -> Zone.NONE
            }
        }
        
        fun fadeOut(onDone: () -> Unit) {
            fading = true
            val fader = object : Runnable {
                override fun run() {
                    alpha -= 0.15f
                    invalidate()
                    if (alpha <= 0) onDone() else handler.postDelayed(this, 25)
                }
            }
            handler.post(fader)
        }
        
        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            handler.removeCallbacks(anim)
        }
    }
}
