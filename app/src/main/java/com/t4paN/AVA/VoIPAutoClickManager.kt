// VoIPAutoClickManager.kt

package com.t4paN.AVA

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log

/**
 * VoIPAutoClickManager - Coordinates auto-click gestures via AccessibilityService
 * 
 * Acts as bridge between VoIPManager and AVAAccessibilityService.
 * The AccessibilityService is passive - it only clicks when this manager tells it to.
 * 
 * Flow:
 * 1. VoIPManager calls performClick(x, y)
 * 2. This manager validates service is available
 * 3. Sends click coordinates to AVAAccessibilityService
 * 4. Service performs gesture, reports back
 * 5. This manager invokes success/failure callback
 */
object VoIPAutoClickManager {
    private const val TAG = "VoIPAutoClickManager"
    
    // Timeout for click confirmation
    private const val CLICK_TIMEOUT_MS = 1000L
    
    private val handler = Handler(Looper.getMainLooper())
    private var pendingCallback: ClickCallback? = null
    private var timeoutRunnable: Runnable? = null
    
    private data class ClickCallback(
        val onSuccess: (() -> Unit)?,
        val onFailure: ((String) -> Unit)?
    )
    
    /**
     * Perform a click at relative screen coordinates.
     * 
     * @param context Application context
     * @param x Relative X position (0.0 to 1.0)
     * @param y Relative Y position (0.0 to 1.0)
     * @param onSuccess Called when click is confirmed
     * @param onFailure Called on timeout or service unavailable
     */
    fun performClick(
        context: Context,
        x: Float,
        y: Float,
        onSuccess: (() -> Unit)? = null,
        onFailure: ((String) -> Unit)? = null
    ) {
        Log.d(TAG, "performClick requested at ($x, $y)")
        
        // Check if AccessibilityService is enabled
        if (!isServiceEnabled(context)) {
            Log.e(TAG, "AccessibilityService not enabled")
            onFailure?.invoke("Η υπηρεσία προσβασιμότητας δεν είναι ενεργή")
            return
        }
        
        // Check if service instance is available
        val service = AVAAccessibilityService.instance
        if (service == null) {
            Log.e(TAG, "AccessibilityService instance not available")
            onFailure?.invoke("Η υπηρεσία δεν είναι διαθέσιμη")
            return
        }
        
        // Cancel any pending click
        cancelPending()
        
        // Store callback for when service reports back
        pendingCallback = ClickCallback(onSuccess, onFailure)
        
        // Set timeout
        timeoutRunnable = Runnable {
            Log.w(TAG, "Click timed out")
            pendingCallback?.onFailure?.invoke("Το κλικ δεν ολοκληρώθηκε")
            pendingCallback = null
        }
        handler.postDelayed(timeoutRunnable!!, CLICK_TIMEOUT_MS)
        
        // Convert relative coords to absolute pixels
        val displayMetrics = context.resources.displayMetrics
        val absX = (x * displayMetrics.widthPixels).toInt()
        val absY = (y * displayMetrics.heightPixels).toInt()
        
        Log.d(TAG, "Requesting click at absolute ($absX, $absY)")
        
        // Request the click
        service.performClick(absX, absY) { success ->
            handler.post {
                handleClickResult(success)
            }
        }
    }
    
    /**
     * Handle result from AccessibilityService.
     */
    private fun handleClickResult(success: Boolean) {
        // Cancel timeout
        timeoutRunnable?.let { handler.removeCallbacks(it) }
        timeoutRunnable = null
        
        val callback = pendingCallback
        pendingCallback = null
        
        if (success) {
            Log.i(TAG, "Click successful")
            callback?.onSuccess?.invoke()
        } else {
            Log.w(TAG, "Click failed")
            callback?.onFailure?.invoke("Το κλικ απέτυχε")
        }
    }
    
    /**
     * Cancel any pending click operation.
     */
    private fun cancelPending() {
        timeoutRunnable?.let { handler.removeCallbacks(it) }
        timeoutRunnable = null
        pendingCallback = null
    }
    
    /**
     * Check if AVA's AccessibilityService is enabled in system settings.
     */
    fun isServiceEnabled(context: Context): Boolean {
        val serviceName = "${context.packageName}/${AVAAccessibilityService::class.java.canonicalName}"
        
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        
        return enabledServices.contains(serviceName) || 
               enabledServices.contains(AVAAccessibilityService::class.java.canonicalName ?: "")
    }
    
    /**
     * Check if auto-click is fully operational.
     * Returns true if service is both enabled AND instance is available.
     */
    fun isOperational(context: Context): Boolean {
        return isServiceEnabled(context) && AVAAccessibilityService.instance != null
    }
    
    /**
     * Open system Accessibility settings for user to enable the service.
     */
    fun openAccessibilitySettings(context: Context) {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
    
    /**
     * Get status message for UI display.
     */
    fun getStatusMessage(context: Context): String {
        return when {
            !isServiceEnabled(context) -> "Η υπηρεσία προσβασιμότητας δεν είναι ενεργή"
            AVAAccessibilityService.instance == null -> "Η υπηρεσία εκκινείται..."
            else -> "Έτοιμο για αυτόματο κλικ"
        }
    }
}
