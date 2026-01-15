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
 * Simplified: No foreground detection, just timed launch + click.
 * The calibration wait time controls how long to wait before clicking.
 *
 * Responsibilities:
 * - Launch VoIP apps with appropriate deep links
 * - Handle cold start (two-stage launch)
 * - Execute auto-click after configured delay
 */
object VoIPManager {
    private const val TAG = "VoIPManager"

    // Preferences
    private const val PREFS_NAME = "voip_manager_prefs"
    private const val KEY_AUTO_CLICK_ENABLED = "auto_click_enabled"

    private val handler = Handler(Looper.getMainLooper())
    private var pendingRunnable: Runnable? = null

    /**
     * Place a call via VoIP app.
     *
     * Flow:
     * 1. Get app config (with calibration data)
     * 2. Launch app (two-stage for reliability)
     * 3. Wait configured time for UI to settle
     * 4. Execute auto-click or show haptic guide
     *
     * @param context Application context
     * @param packageName VoIP app package name
     * @param phoneNumber Phone number to call
     * @param onSuccess Called when call flow completes successfully
     * @param onFailure Called on error, with message for TTS
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

        // Always use two-stage launch for reliability
        twoStageLaunch(context, config, phoneNumber, onSuccess, onFailure)
    }

    /**
     * Two-stage launch for reliable app startup.
     *
     * Stage 1: Wake up the app with launch intent
     * Stage 2: After delay, send the actual deep link
     * Stage 3: After configured wait, execute click
     */
    private fun twoStageLaunch(
        context: Context,
        config: VoIPAppConfig,
        phoneNumber: String,
        onSuccess: (() -> Unit)?,
        onFailure: ((String) -> Unit)?
    ) {
        // Cancel any pending action
        cancelPending()

        // Stage 1: Wake up the app
        val launchIntent = context.packageManager.getLaunchIntentForPackage(config.packageName)
        if (launchIntent == null) {
            Log.e(TAG, "Cannot get launch intent for ${config.packageName}")
            onFailure?.invoke("Δεν μπόρεσα να ανοίξω την εφαρμογή")
            return
        }

        Log.d(TAG, "${config.displayName} two-stage launch starting")
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launchIntent)

        // Stage 2: After brief delay, send deep link
        handler.postDelayed({
            launchWithDeepLink(context, config, phoneNumber)
            
            // Stage 3: After configured wait, execute action
            scheduleAction(context, config, onSuccess, onFailure)
        }, 500) // 500ms for app to initialize
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
            // Fallback: try without package restriction
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

    /**
     * Schedule auto-click or haptic guide after configured delay.
     */
    private fun scheduleAction(
        context: Context,
        config: VoIPAppConfig,
        onSuccess: (() -> Unit)?,
        onFailure: ((String) -> Unit)?
    ) {
        val waitTime = config.waitTimeMs
        Log.d(TAG, "Waiting ${waitTime}ms for ${config.displayName} UI to settle")

        pendingRunnable = Runnable {
            if (isAutoClickEnabled(context) && config.isCalibrated) {
                executeAutoClick(context, config, onSuccess, onFailure)
            } else if (config.isCalibrated) {
                showHapticGuide(context, config, onSuccess)
            } else {
                // Not calibrated - just leave user in the app
                Log.w(TAG, "${config.displayName} not calibrated, no guidance available")
                onSuccess?.invoke()
            }
        }

        handler.postDelayed(pendingRunnable!!, waitTime)
    }

    /**
     * Execute auto-click via AccessibilityService.
     */
    private fun executeAutoClick(
        context: Context,
        config: VoIPAppConfig,
        onSuccess: (() -> Unit)?,
        onFailure: ((String) -> Unit)?
    ) {
        Log.d(TAG, "Executing auto-click at (${config.clickX}, ${config.clickY})")

        VoIPAutoClickManager.performClick(
            context = context,
            x = config.clickX,
            y = config.clickY,
            onSuccess = {
                Log.i(TAG, "Auto-click successful")
                onSuccess?.invoke()
            },
            onFailure = { reason ->
                Log.w(TAG, "Auto-click failed: $reason")
                onFailure?.invoke("Δεν μπόρεσα να πατήσω το κουμπί")
            }
        )
    }

    /**
     * Show haptic guide overlay for manual button finding.
     */
    private fun showHapticGuide(
        context: Context,
        config: VoIPAppConfig,
        onSuccess: (() -> Unit)?
    ) {
        Log.d(TAG, "Showing haptic guide at (${config.clickX}, ${config.clickY})")

        HapticGuideManager.start(
            context = context,
            packageName = config.packageName,
            onTapped = {
                Log.i(TAG, "Haptic guide: button tapped")
                onSuccess?.invoke()
            },
            onCancelled = {
                Log.i(TAG, "Haptic guide: cancelled")
                HapticGuideManager.forceStop()
            }
        )
    }

    /**
     * Cancel any pending action.
     */
    fun cancelPending() {
        pendingRunnable?.let {
            handler.removeCallbacks(it)
            pendingRunnable = null
        }
    }

    // Keep old name for compatibility with CallManagerService
    fun cancelPolling() = cancelPending()

    // ==================== Settings ====================

    /**
     * Check if auto-click mode is enabled.
     * When false, haptic guide is used instead.
     */
    fun isAutoClickEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_AUTO_CLICK_ENABLED, true) // Default to auto-click
    }

    /**
     * Enable or disable auto-click mode.
     */
    fun setAutoClickEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_AUTO_CLICK_ENABLED, enabled).apply()
        Log.i(TAG, "Auto-click ${if (enabled) "enabled" else "disabled"}")
    }

    // ==================== Utility ====================

    /**
     * Check if a VoIP app is installed and we know how to use it.
     */
    fun isAppAvailable(context: Context, packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            VoIPAppRegistry.isKnownApp(packageName)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Get list of available VoIP apps for calling.
     */
    fun getAvailableApps(context: Context): List<VoIPAppConfig> {
        return VoIPAppRegistry.getAvailableApps(context)
    }
}
