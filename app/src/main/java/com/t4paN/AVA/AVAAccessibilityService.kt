// AVAAccessibilityService.kt

package com.t4paN.AVA

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * AVAAccessibilityService - Performs click gestures for VoIP auto-click feature
 * 
 * PASSIVE SERVICE: Does nothing unless explicitly commanded by VoIPAutoClickManager.
 * Does NOT read screen content, monitor apps, or collect any data.
 * 
 * Compliance notes:
 * - isAccessibilityTool="true" in config
 * - canRetrieveWindowContent="false" - no screen reading
 * - Only performs gestures when requested by AVA's call flow
 * - User must explicitly enable in Accessibility settings
 */
class AVAAccessibilityService : AccessibilityService() {
    
    companion object {
        private const val TAG = "AVAAccessibilityService"
        
        /**
         * Static reference for VoIPAutoClickManager to access.
         * Null when service is not running.
         */
        var instance: AVAAccessibilityService? = null
            private set
    }
    
    // ==================== Lifecycle ====================
    
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "Service connected - ready for click commands")
    }
    
    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.i(TAG, "Service destroyed")
    }
    
    override fun onInterrupt() {
        Log.w(TAG, "Service interrupted")
    }
    
    /**
     * We don't process accessibility events - passive service.
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Intentionally empty - we don't monitor or react to events
    }
    
    // ==================== Click API ====================
    
    /**
     * Perform a click gesture at absolute screen coordinates.
     * 
     * @param x Absolute X pixel coordinate
     * @param y Absolute Y pixel coordinate
     * @param callback Called with true on success, false on failure
     */
    fun performClick(x: Int, y: Int, callback: (Boolean) -> Unit) {
        Log.d(TAG, "performClick at ($x, $y)")
        
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.e(TAG, "Gesture API requires API 24+")
            callback(false)
            return
        }
        
        try {
            val path = Path().apply {
                moveTo(x.toFloat(), y.toFloat())
            }
            
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
                .build()
            
            val dispatched = dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        Log.d(TAG, "Gesture completed successfully")
                        callback(true)
                    }
                    
                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        Log.w(TAG, "Gesture cancelled")
                        callback(false)
                    }
                },
                null
            )
            
            if (!dispatched) {
                Log.e(TAG, "dispatchGesture returned false")
                callback(false)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error performing click", e)
            callback(false)
        }
    }
    
    /**
     * Perform a long press gesture at absolute screen coordinates.
     * Reserved for future use if needed.
     * 
     * @param x Absolute X pixel coordinate
     * @param y Absolute Y pixel coordinate
     * @param durationMs Duration of the press in milliseconds
     * @param callback Called with true on success, false on failure
     */
    fun performLongPress(x: Int, y: Int, durationMs: Long = 500, callback: (Boolean) -> Unit) {
        Log.d(TAG, "performLongPress at ($x, $y) for ${durationMs}ms")
        
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.e(TAG, "Gesture API requires API 24+")
            callback(false)
            return
        }
        
        try {
            val path = Path().apply {
                moveTo(x.toFloat(), y.toFloat())
            }
            
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
                .build()
            
            val dispatched = dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        Log.d(TAG, "Long press completed successfully")
                        callback(true)
                    }
                    
                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        Log.w(TAG, "Long press cancelled")
                        callback(false)
                    }
                },
                null
            )
            
            if (!dispatched) {
                Log.e(TAG, "dispatchGesture returned false")
                callback(false)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error performing long press", e)
            callback(false)
        }
    }
}
