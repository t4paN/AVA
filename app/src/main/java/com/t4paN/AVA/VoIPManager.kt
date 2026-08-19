// VoIPManager.kt

package com.t4paN.AVA

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * VoIPManager - Handles all VoIP app calling logic
 *
 * Preferred strategy: VoIPDirectCall fires the contact's own Viber/WhatsApp/
 * Signal call row, which places the call with no UI driving at all.
 *
 * Fallback, when the messenger has not synced a usable row: deep link into the
 * chat screen and let the AccessibilityService click the call button.
 * - ConversationActivity detected → wait 300ms → click
 * - PhoneFragmentActivity detected → success, stop
 * - Left Viber → stop
 */
object VoIPManager {
    private const val TAG = "VoIPManager"

    // Preferences
    private const val PREFS_NAME = "voip_manager_prefs"
    private const val KEY_AUTO_CLICK_ENABLED = "auto_click_enabled"
    private const val KEY_DIRECT_CALL_ENABLED = "direct_call_enabled"

    // Direct-path timing. A cold messenger cannot service the row, so the app is
    // launched first and the row follows once it has had time to initialise.
    // Success is then confirmed by watching the audio mode, which is what the
    // AccessibilityService used to do by spotting the call screen.
    private const val WARMUP_DELAY_MS = 1400L
    private const val POLL_INTERVAL_MS = 500L
    private const val RETRY_AT_MS = 3500L
    private const val GIVE_UP_AT_MS = 8000L

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

    // Direct-path state. Kept separate from isActive so the AccessibilityService
    // callbacks stay inert while the direct path owns the call.
    private var appContext: Context? = null
    private var directPoll: Runnable? = null

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

        // Preferred path: the contact's own call row in the address book. When the
        // messenger has synced one, this places the call outright — no deep link,
        // no calibration, no AccessibilityService. Nothing below runs.
        if (isDirectCallEnabled(context) &&
            attemptDirectCall(context, packageName, phoneNumber, onSuccess, onFailure)
        ) {
            return
        }

        // Capture everything we need from context (no context stored).
        // Full display size, not the app-usable area — calibration fractions come
        // from a screenshot, which includes the system bars.
        val (w, h) = ScreenMetrics.realSize(context)
        screenWidth = w
        screenHeight = h
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
     * Try to place the call through the contact's own data row.
     *
     * Returns true once it has taken ownership of the call — the caller must not
     * fall through to the deep-link path, because the outcome is reported through
     * onSuccess/onFailure after the audio mode has been watched for a while.
     * Returns false only when there is no usable row at all, which is the one
     * case where the old auto-click route is still worth trying.
     */
    private fun attemptDirectCall(
        context: Context,
        packageName: String,
        phoneNumber: String,
        onSuccess: (() -> Unit)?,
        onFailure: ((String) -> Unit)?
    ): Boolean {
        val action = VoIPDirectCall.findAction(context, packageName, phoneNumber)
        if (action == null) {
            Log.i(TAG, "No usable call row - falling back to deep link + auto-click")
            return false
        }

        cancelDirect()
        // applicationContext only: this outlives the calling service.
        val ctx = context.applicationContext
        appContext = ctx
        val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

        val warmup = if (VoIPDirectCall.warmUp(ctx, packageName)) WARMUP_DELAY_MS else 0L
        handler.postDelayed({ VoIPDirectCall.place(ctx, action) }, warmup)

        var retried = false
        val poll = object : Runnable {
            var elapsed = warmup
            override fun run() {
                elapsed += POLL_INTERVAL_MS

                if (audio?.mode == AudioManager.MODE_IN_COMMUNICATION) {
                    Log.i(TAG, "Direct call live after ${elapsed}ms via ${action.mimeType}")
                    cancelDirect()
                    onSuccess?.invoke()
                    return
                }

                if (elapsed >= GIVE_UP_AT_MS) {
                    Log.w(TAG, "Direct call never reached MODE_IN_COMMUNICATION")
                    cancelDirect()
                    onFailure?.invoke("Η κλήση δεν ξεκίνησε")
                    return
                }

                // One second chance. A messenger that was cold can swallow the
                // first row while it is still starting up.
                if (!retried && elapsed >= RETRY_AT_MS) {
                    retried = true
                    Log.i(TAG, "No call at ${elapsed}ms - firing the row once more")
                    VoIPDirectCall.place(ctx, action)
                }

                handler.postDelayed(this, POLL_INTERVAL_MS)
            }
        }
        directPoll = poll
        handler.postDelayed(poll, warmup + POLL_INTERVAL_MS)
        return true
    }

    /**
     * Stop watching the direct path and drop its context.
     */
    private fun cancelDirect() {
        directPoll?.let { handler.removeCallbacks(it) }
        directPoll = null
        appContext = null
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
        cancelDirect()
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

    /**
     * Whether to try the contact-data row before the deep-link path. On by
     * default; turn it off to A/B the old auto-click route on a device.
     */
    fun isDirectCallEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_DIRECT_CALL_ENABLED, true)
    }

    fun setDirectCallEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_DIRECT_CALL_ENABLED, enabled).apply()
        Log.i(TAG, "Direct call ${if (enabled) "enabled" else "disabled"}")
    }

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