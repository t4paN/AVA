// AVAAccessibilityService.kt

package com.t4paN.AVA

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * AVAAccessibilityService - Performs click gestures for VoIP auto-click feature
 *
 * Also listens for window changes to detect when VoIP call screen appears,
 * allowing us to stop click-spam once the call connects.
 */
class AVAAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "AVAAccessibilityService"
        private const val MISSED_CALLS_UTTERANCE_ID = "missed_calls_tts"

        // Dialer packages we recognize
        private val dialerPackages = listOf(
            "com.google.android.dialer",
            "com.android.dialer",
            "com.samsung.android.dialer",
            "com.motorola.dialer"
        )

        var instance: AVAAccessibilityService? = null
            private set

        // Dialer navigation state
        @Volatile
        var waitingForDialer = false
    }

    private enum class DialerStage { IDLE, CLICKING_STEPS, WAITING_FOR_UI }
    private var dialerStage = DialerStage.IDLE
    private var dialerSteps: List<CalibrationStep> = emptyList()
    private var currentStepIndex = 0
    private var isExecutingStep = false  // Prevents race condition during step clicks

    // Parsed missed calls
    data class MissedCallEntry(
        val contactOrNumber: String,
        val timeInfo: String
    )

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
        if (event == null) return

        // Handle dialer window detection for missed calls
        if (waitingForDialer && event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val packageName = event.packageName?.toString() ?: return

            if (packageName in dialerPackages) {
                when (dialerStage) {
                    DialerStage.CLICKING_STEPS -> {
                        // Prevent race condition from rapid window events
                        if (isExecutingStep) {
                            Log.d(TAG, "📞 Already executing step, ignoring event")
                            return
                        }
                        
                        if (currentStepIndex < dialerSteps.size) {
                            isExecutingStep = true
                            val step = dialerSteps[currentStepIndex]
                            val stepNum = currentStepIndex + 1
                            val totalSteps = dialerSteps.size
                            Log.d(TAG, "📞 Preparing step $stepNum/$totalSteps")
                            
                            val displayMetrics = resources.displayMetrics
                            val clickX = (step.clickX * displayMetrics.widthPixels).toInt()
                            val clickY = (step.clickY * displayMetrics.heightPixels).toInt()
                            
                            // Wait for dialer content before first click
                            if (currentStepIndex == 0) {
                                waitForDialerContent {
                                    // Fixed 800ms delay for first step
                                    Handler(Looper.getMainLooper()).postDelayed({
                                        executeClick(clickX, clickY, stepNum, totalSteps)
                                    }, 800)
                                }
                            } else {
                                // Fixed 500ms delay for subsequent steps
                                Handler(Looper.getMainLooper()).postDelayed({
                                    executeClick(clickX, clickY, stepNum, totalSteps)
                                }, 500)
                            }
                        }
                    }
                    
                    DialerStage.WAITING_FOR_UI -> {
                        // Ignore all events while waiting for UI to settle
                        Log.d(TAG, "📞 Waiting for UI, ignoring event")
                        return
                    }
                    
                    DialerStage.IDLE -> resetDialerState()
                }
            }
        }

        // Only process VoIP events when VoIPManager is active OR waiting for dialer
        if (!VoIPManager.isActive() && !waitingForDialer) return

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
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

    /**
     * Execute a click and handle completion
     */
    private fun executeClick(clickX: Int, clickY: Int, stepNum: Int, totalSteps: Int) {
        performClick(clickX, clickY) { success ->
            if (success) {
                Log.d(TAG, "📞 Completed step $stepNum at ($clickX, $clickY)")
                currentStepIndex++
                
                if (currentStepIndex >= dialerSteps.size) {
                    // All steps done - enter waiting state to ignore events
                    dialerStage = DialerStage.WAITING_FOR_UI
                    Log.d(TAG, "📞 All steps done, waiting 1s for UI to settle...")
                    
                    // Scrape directly after delay
                    Handler(Looper.getMainLooper()).postDelayed({
                        Log.d(TAG, "📞 Now scraping...")
                        performScrapeAndAnnounce()
                    }, 1000)
                }
            } else {
                Log.e(TAG, "📞 Step $stepNum failed")
                resetDialerState()
            }
            isExecutingStep = false
        }
    }

    /**
     * Wait for dialer to be the active window before proceeding.
     * Polls rootInActiveWindow until it matches a dialer package.
     */
    private fun waitForDialerContent(attempts: Int = 10, onReady: () -> Unit) {
        val root = rootInActiveWindow
        val packageName = root?.packageName?.toString()
        
        if (packageName != null && packageName in dialerPackages) {
            Log.d(TAG, "📞 Dialer content ready (package=$packageName)")
            root.recycle()
            onReady()
        } else {
            root?.recycle()
            if (attempts > 0) {
                Log.d(TAG, "📞 Waiting for dialer content... ($attempts attempts left)")
                Handler(Looper.getMainLooper()).postDelayed({
                    waitForDialerContent(attempts - 1, onReady)
                }, 200)
            } else {
                Log.e(TAG, "📞 Dialer content never loaded, aborting")
                resetDialerState()
            }
        }
    }

    /**
     * Scrape missed calls, show overlay, and announce via TTS
     */
    private fun performScrapeAndAnnounce() {
        val missedCalls = scrapeMissedCalls()
        val ttsOutput = buildMissedCallsTts(missedCalls)
        Log.i(TAG, "📞 TTS Output: $ttsOutput")
        
        // Show cancel overlay
        CallOverlayController.showRecording {
            // On cancel tap: stop TTS, dismiss, go home
            Log.d(TAG, "📞 User cancelled missed calls announcement")
            TtsManager.stop()
            CallOverlayController.dismiss()
            goHome()
            resetDialerState()
        }
        
        // Set up TTS completion listener
        TtsManager.setUtteranceListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                Log.d(TAG, "📞 TTS started: $utteranceId")
            }
            
            override fun onDone(utteranceId: String?) {
                if (utteranceId == MISSED_CALLS_UTTERANCE_ID) {
                    Log.d(TAG, "📞 TTS completed, dismissing overlay")
                    Handler(Looper.getMainLooper()).post {
                        CallOverlayController.dismiss()
                        goHome()
                        resetDialerState()
                    }
                }
            }
            
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                Log.e(TAG, "📞 TTS error: $utteranceId")
                Handler(Looper.getMainLooper()).post {
                    CallOverlayController.dismiss()
                    resetDialerState()
                }
            }
        })
        
        // Speak with utterance ID
        TtsManager.speak(ttsOutput, MISSED_CALLS_UTTERANCE_ID)
    }

    /**
     * Go to home screen
     */
    private fun goHome() {
        try {
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(homeIntent)
            Log.d(TAG, "📞 Sent home intent")
        } catch (e: Exception) {
            Log.e(TAG, "📞 Failed to go home", e)
        }
    }
    
    // ==================== Missed Calls ====================
    
    fun launchDialerAndScrape(context: Context) {
        val config = VoIPAppRegistry.getConfig(context, VoIPAppRegistry.DIALER_CALIBRATION_KEY)
        val steps = config?.getAllSteps() ?: emptyList()
        
        if (steps.isEmpty()) {
            Log.e(TAG, "📞 Dialer not calibrated")
            TtsManager.speak("Το τηλέφωνο δεν έχει ρυθμιστεί")
            return
        }
        
        waitingForDialer = true
        dialerSteps = steps
        currentStepIndex = 0
        isExecutingStep = false
        dialerStage = DialerStage.CLICKING_STEPS

        try {
            val intent = Intent(Intent.ACTION_DIAL).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Log.d(TAG, "📞 Launched dialer, ${steps.size} steps to execute...")
        } catch (e: Exception) {
            Log.e(TAG, "📞 Failed to launch dialer", e)
            TtsManager.speak("Αποτυχία ανοίγματος τηλεφώνου")
            resetDialerState()
        }
    }
    
    private fun resetDialerState() {
        waitingForDialer = false
        dialerStage = DialerStage.IDLE
        dialerSteps = emptyList()
        currentStepIndex = 0
        isExecutingStep = false
    }
    
    private fun scrapeMissedCalls(): List<MissedCallEntry> {
        val rootNode = rootInActiveWindow
        if (rootNode == null) {
            Log.w(TAG, "📞 No root node available for scraping")
            return emptyList()
        }

        Log.d(TAG, "📞 ========== SCRAPING MISSED CALLS ==========")
        val missedCalls = mutableListOf<MissedCallEntry>()
        findMissedCallNodes(rootNode, missedCalls)
        Log.d(TAG, "📞 Found ${missedCalls.size} missed calls")
        Log.d(TAG, "📞 ========== END SCRAPING ==========")

        rootNode.recycle()
        return missedCalls
    }

    private fun findMissedCallNodes(node: AccessibilityNodeInfo?, results: MutableList<MissedCallEntry>) {
        if (node == null) return

        val contentDesc = node.contentDescription?.toString()
        
        // Log CardView nodes for debugging
        val className = node.className?.toString() ?: ""
        if (className.contains("CardView") && contentDesc != null) {
            Log.d(TAG, "📞 [CardView] desc=\"$contentDesc\"")
        }

        // Parse contentDescription for missed calls
        // Format: "‪{number}‬, {call_type}, {time}, {location}."
        // Look for "missed" (English) or "αναπάντητη" (Greek)
        if (contentDesc != null) {
            val descLower = contentDesc.lowercase()
            if (descLower.contains("missed") || descLower.contains("αναπάντητη")) {
                Log.i(TAG, "📞 🎯 MISSED CALL FOUND: $contentDesc")
                
                val entry = parseMissedCallDescription(contentDesc)
                if (entry != null) {
                    results.add(entry)
                }
            }
        }

        // Recurse into children
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                findMissedCallNodes(child, results)
                child.recycle()
            }
        }
    }

    private fun parseMissedCallDescription(desc: String): MissedCallEntry? {
        try {
            val parts = desc.split(",").map { it.trim() }
            if (parts.size < 4) {
                Log.w(TAG, "📞 Unexpected format: $desc")
                return null
            }

            // First part: name or number
            val contactOrNumber = parts[0]
                .replace("‪", "")
                .replace("‬", "")
                .trim()

            // Find the time part - it's the one after "missed call"
            val missedIndex = parts.indexOfFirst {
                it.lowercase().contains("missed") || it.lowercase().contains("αναπάντητη")
            }
            val timeInfo = if (missedIndex >= 0 && missedIndex + 1 < parts.size) {
                parts[missedIndex + 1].trim()
            } else {
                parts[2].trim()
            }

            return MissedCallEntry(
                contactOrNumber = contactOrNumber,
                timeInfo = timeInfo
            )
        } catch (e: Exception) {
            Log.e(TAG, "📞 Failed to parse: $desc", e)
            return null
        }
    }

    private fun buildMissedCallsTts(calls: List<MissedCallEntry>): String {
        if (calls.isEmpty()) {
            return "Δεν βρέθηκαν αναπάντητες κλήσεις"
        }

        val intro = when (calls.size) {
            1 -> "Έχετε μία αναπάντητη κλήση"
            else -> "Έχετε ${calls.size} αναπάντητες κλήσεις"
        }

        val details = calls.mapIndexed { index, call ->
            if (calls.size == 1) {
                "από ${call.contactOrNumber}, ${call.timeInfo}"
            } else {
                "${index + 1}. Από ${call.contactOrNumber}, ${call.timeInfo}"
            }
        }.joinToString(". ")

        return "$intro. $details"
    }

    // Legacy dump for debugging
    private fun dumpDialerNodes() {
        val rootNode = rootInActiveWindow
        if (rootNode == null) {
            Log.w(TAG, "📞 No root node available")
            return
        }

        Log.d(TAG, "📞 ========== CALL LOG DUMP ==========")
        dumpNodeRecursive(rootNode, 0)
        Log.d(TAG, "📞 ========== END DUMP ==========")

        rootNode.recycle()
    }

    private fun dumpNodeRecursive(node: AccessibilityNodeInfo?, depth: Int) {
        if (node == null) return

        val indent = "  ".repeat(depth)
        val className = node.className?.toString()?.substringAfterLast('.') ?: "?"
        val text = node.text?.toString()
        val contentDesc = node.contentDescription?.toString()

        if (text != null || contentDesc != null) {
            Log.d(TAG, "📞 $indent[$className] text=\"$text\" desc=\"$contentDesc\"")
        }

        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                dumpNodeRecursive(child, depth + 1)
                child.recycle()
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
