// VoIPManager.kt

package com.t4paN.AVA

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * VoIPManager - Handles all VoIP app calling logic
 *
 * Click strategy: AccessibilityService detects Viber screens and triggers clicks.
 * - ConversationActivity detected → wait 300ms → click
 * - PhoneFragmentActivity detected → success, stop
 * - Left Viber → stop
 */
object VoIPManager {
    private const val TAG = "VoIPManager"

    // Preferences
    private const val PREFS_NAME = "voip_manager_prefs"
    private const val KEY_AUTO_CLICK_ENABLED = "auto_click_enabled"

    private val handler = Handler(Looper.getMainLooper())
    private var pendingRunnable: Runnable? = null
    private var timeoutRunnable: Runnable? = null

    // Current call state (no Context stored - just primitives)
    private var clickX: Float = 0f
    private var clickY: Float = 0f
    private var screenWidth: Int = 0
    private var screenHeight: Int = 0
    private var autoClickEnabled: Boolean = false
    private var isCalibrated: Boolean = false
    private var successCallback: (() -> Unit)? = null
    private var failureCallback: ((String) -> Unit)? = null
    private var isActive = false

    /**
     * Place a call via VoIP app.
     */
    fun placeCall(
        context: Context,
        packageName: String,
        phoneNumber: String,
        onSuccess: (() -> Unit)? = null,
        onFailure: ((String) -> Unit)? = null
    ) {
        val config = VoIPAppRegistry.getConfig(context, packageName)

        if (config == null) {
            Log.e(TAG, "Unknown VoIP app: $packageName")
            onFailure?.invoke("Άγνωστη εφαρμογή")
            return
        }

        Log.i(TAG, "Placing call via ${config.displayName} to $phoneNumber")

        // Capture everything we need from context (no context stored)
        val displayMetrics = context.resources.displayMetrics
        screenWidth = displayMetrics.widthPixels
        screenHeight = displayMetrics.heightPixels
        autoClickEnabled = isAutoClickEnabled(context)

        // Store config values
        clickX = config.clickX
        clickY = config.clickY
        isCalibrated = config.isCalibrated
        successCallback = onSuccess
        failureCallback = onFailure
        isActive = true

        // Set timeout based on config
        timeoutRunnable = Runnable {
            if (isActive) {
                Log.w(TAG, "Timeout waiting for call screen")
                val callback = failureCallback
                cleanup()
                callback?.invoke("Η κλήση δεν ξεκίνησε εγκαίρως")
            }
        }
        handler.postDelayed(timeoutRunnable!!, config.waitTimeMs + 2000) // Extra buffer

        // Launch the app
        twoStageLaunch(context, config, phoneNumber)
    }

    /**
     * Two-stage launch for reliable app startup.
     */
    private fun twoStageLaunch(
        context: Context,
        config: VoIPAppConfig,
        phoneNumber: String
    ) {
        cancelPending()

        val launchIntent = context.packageManager.getLaunchIntentForPackage(config.packageName)
        if (launchIntent == null) {
            Log.e(TAG, "Cannot get launch intent for ${config.packageName}")
            val callback = failureCallback
            cleanup()
            callback?.invoke("Δεν μπόρεσα να ανοίξω την εφαρμογή")
            return
        }

        Log.d(TAG, "${config.displayName} two-stage launch starting")
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launchIntent)

        // Stage 2: After brief delay, send deep link
        handler.postDelayed({
            launchWithDeepLink(context, config, phoneNumber)
        }, 500)
    }

    /**
     * Launch app with deep link to specific contact/call screen.
     */
    private fun launchWithDeepLink(context: Context, config: VoIPAppConfig, phoneNumber: String) {
        val deepLink = config.buildDeepLink(phoneNumber)
        Log.d(TAG, "Launching with deep link: $deepLink")

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(deepLink)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            setPackage(config.packageName)
        }

        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch deep link", e)
            try {
                val fallbackIntent = Intent(Intent.ACTION_VIEW, Uri.parse(deepLink)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallbackIntent)
            } catch (e2: Exception) {
                Log.e(TAG, "Fallback launch also failed", e2)
            }
        }
    }

    // ==================== AccessibilityService Callbacks ====================

    /**
     * Called when Viber chat screen (ConversationActivity) is detected.
     * Schedule a click after 300ms.
     */
    fun onChatScreenDetected() {
        if (!isActive) return
        if (!isCalibrated || !autoClickEnabled) return

        Log.d(TAG, "Chat screen detected, scheduling click in 300ms")

        cancelPending()
        pendingRunnable = Runnable {
            if (isActive) {
                executeAutoClick()
            }
        }
        handler.postDelayed(pendingRunnable!!, 300)
    }

    /**
     * Called when Viber call screen (PhoneFragmentActivity) is detected.
     * Success - stop everything.
     */
    fun onCallScreenDetected() {
        if (!isActive) return

        Log.i(TAG, "Call screen detected - success!")
        val callback = successCallback
        cleanup()
        callback?.invoke()
    }

    /**
     * Called when user left Viber (went to home, another app, etc.)
     */
    fun onLeftApp() {
        if (!isActive) return

        Log.d(TAG, "Left Viber - stopping")
        cleanup()
    }

    /**
     * Execute auto-click via AccessibilityService.
     * Uses stored screen dimensions - no context needed.
     */
    private fun executeAutoClick() {
        // Convert relative coordinates to absolute pixels
        val absoluteX = (clickX * screenWidth).toInt()
        val absoluteY = (clickY * screenHeight).toInt()

        Log.d(TAG, "Executing auto-click at ($clickX, $clickY) -> ($absoluteX, $absoluteY)")

        val service = AVAAccessibilityService.instance
        if (service == null) {
            Log.e(TAG, "AccessibilityService not available")
            return
        }

        service.performClick(absoluteX, absoluteY) { success ->
            if (success) {
                Log.i(TAG, "Click dispatched, waiting for screen change...")
            } else {
                Log.w(TAG, "Click failed, will retry on next chat screen event")
            }
        }
    }

    /**
     * Cancel pending click.
     */
    fun cancelPending() {
        pendingRunnable?.let {
            handler.removeCallbacks(it)
            pendingRunnable = null
        }
    }

    /**
     * Full cleanup.
     */
    private fun cleanup() {
        isActive = false
        cancelPending()
        timeoutRunnable?.let {
            handler.removeCallbacks(it)
            timeoutRunnable = null
        }
        clickX = 0f
        clickY = 0f
        screenWidth = 0
        screenHeight = 0
        autoClickEnabled = false
        isCalibrated = false
        successCallback = null
        failureCallback = null
    }

    // Keep old name for compatibility
    fun cancelPolling() = cancelPending()

    // ==================== Settings ====================

    fun isAutoClickEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_AUTO_CLICK_ENABLED, true)
    }

    fun setAutoClickEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_AUTO_CLICK_ENABLED, enabled).apply()
        Log.i(TAG, "Auto-click ${if (enabled) "enabled" else "disabled"}")
    }

    // ==================== Utility ====================

    fun isAppAvailable(context: Context, packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            VoIPAppRegistry.isKnownApp(packageName)
        } catch (e: Exception) {
            false
        }
    }

    fun isActive(): Boolean = isActive

    fun getAvailableApps(context: Context): List<VoIPAppConfig> {
        return VoIPAppRegistry.getAvailableApps(context)
    }
}