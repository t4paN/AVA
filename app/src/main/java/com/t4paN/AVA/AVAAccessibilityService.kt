// AVAAccessibilityService.kt

package com.t4paN.AVA

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * AVAAccessibilityService - Performs click gestures for VoIP auto-click feature
 *
 * Also listens for window changes to detect when VoIP call screen appears,
 * allowing us to stop click-spam once the call connects.
 */
class AVAAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "AVAAccessibilityService"

        var instance: AVAAccessibilityService? = null
            private set
    }

    // ==================== Lifecycle ====================

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "Service connected - ready for click commands")

        // Configure to receive window state events
        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 100
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.i(TAG, "Service destroyed")
    }

    override fun onInterrupt() {
        Log.w(TAG, "Service interrupted")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val packageName = event.packageName?.toString() ?: return
            val className = event.className?.toString() ?: ""

            Log.d(TAG, "Window changed: $packageName / $className")

            when {
                // Viber call screen - success!
                packageName == "com.viber.voip" && className.contains("PhoneFragment") -> {
                    Log.i(TAG, "Viber call screen detected - success!")
                    VoIPManager.onCallScreenDetected()
                }

                // Viber chat screen - need to click
                packageName == "com.viber.voip" && className.contains("ConversationActivity") -> {
                    Log.d(TAG, "Viber chat screen - scheduling click")
                    VoIPManager.onChatScreenDetected()
                }

                // Left Viber
                packageName != "com.viber.voip" && !className.contains("FrameLayout") -> {
                    VoIPManager.onLeftApp()
                }
            }
        }
    }
    // ==================== Click API ====================

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