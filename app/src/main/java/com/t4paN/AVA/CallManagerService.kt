//CallManagerService.kt

package com.t4paN.AVA

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.UtteranceProgressListener
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * CallManagerService - Owns the entire post-match call flow
 *
 * Handles call announcement, confirmation, and placement.
 * Overlays are delegated to CallOverlayController.
 * VoIP calls are delegated to VoIPManager.
 */
class CallManagerService : Service() {

    companion object {
        private const val TAG = "CallManagerService"
        private const val CHANNEL_ID = "call_manager_channel"
        private const val NOTIFICATION_ID = 2

        // Actions
        const val ACTION_SINGLE_MATCH = "SINGLE_MATCH"
        const val ACTION_AMBIGUOUS_MATCH = "AMBIGUOUS_MATCH"
        const val ACTION_CANCEL = "CANCEL"

        // Extras
        const val EXTRA_CONTACT_NAME = "contact_name"
        const val EXTRA_PHONE_NUMBER = "phone_number"
        const val EXTRA_ROUTING = "routing"
        const val EXTRA_NAMES = "names"
        const val EXTRA_NUMBERS = "numbers"
        const val EXTRA_ROUTINGS = "routings"

        // Timing
        private const val POST_TTS_BUFFER_MS = 500L

        // TTS Speech rates
        private const val TTS_RATE_NORMAL = 1.0f
        private const val TTS_RATE_SLOW = 0.75f

        // VoIP package names for routing detection
        private const val VIBER_PACKAGE = "com.viber.voip"
        private const val WHATSAPP_PACKAGE = "com.whatsapp"
        private const val SIGNAL_PACKAGE = "org.thoughtcrime.securesms"
    }

    // State
    private enum class CallPhase {
        IDLE,
        ANNOUNCING,       // TTS playing, tap = abort
        CALLING,          // Call placed
        SELECTION         // Ambiguous match, waiting for selection
    }

    private var currentPhase = CallPhase.IDLE
    private var pendingNumber: String? = null
    private var pendingName: String? = null
    private var pendingRouting: String? = null

    // Ambiguous match state
    private var ambiguousNames: List<String>? = null
    private var ambiguousNumbers: List<String>? = null
    private var ambiguousRoutings: List<String>? = null

    // System services
    private var vibrator: Vibrator? = null
    private var telephonyManager: TelephonyManager? = null
    private var telecomManager: TelecomManager? = null

    // Handler for delays
    private val handler = Handler(Looper.getMainLooper())

    // Service lifecycle flag
    private var isServiceAlive = true

    // Auto-call setting
    private val autoCallEnabled: Boolean
        get() {
            val prefs = getSharedPreferences("ava_settings", Context.MODE_PRIVATE)
            return prefs.getBoolean("auto_call_enabled", true)
        }

    // ==================== LIFECYCLE ====================

    override fun onCreate() {
        super.onCreate()
        isServiceAlive = true
        Log.d(TAG, "CallManagerService created")

        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        telecomManager = getSystemService(Context.TELECOM_SERVICE) as TelecomManager

        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        // Initialize TTS via TtsManager and set up our listener
        TtsManager.initialize(this)
        setupTtsListener()

        startForegroundNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: action=${intent?.action}")

        when (intent?.action) {
            ACTION_SINGLE_MATCH -> {
                val name = intent.getStringExtra(EXTRA_CONTACT_NAME) ?: return START_NOT_STICKY
                val number = intent.getStringExtra(EXTRA_PHONE_NUMBER) ?: return START_NOT_STICKY
                val routing = intent.getStringExtra(EXTRA_ROUTING) ?: ""
                handleSingleMatch(name, number, routing)
            }

            ACTION_AMBIGUOUS_MATCH -> {
                val names = intent.getStringArrayListExtra(EXTRA_NAMES) ?: return START_NOT_STICKY
                val numbers = intent.getStringArrayListExtra(EXTRA_NUMBERS) ?: return START_NOT_STICKY
                val routings = intent.getStringArrayListExtra(EXTRA_ROUTINGS) ?: arrayListOf()
                handleAmbiguousMatch(names, numbers, routings)
            }

            ACTION_CANCEL -> {
                handleCancelTap()
            }
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        isServiceAlive = false
        super.onDestroy()
        Log.d(TAG, "CallManagerService destroyed")
        cleanup()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ==================== INITIALIZATION ====================

    private fun startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Call Manager",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setSound(null, null)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AVA Call")
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSound(null)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun setupTtsListener() {
        TtsManager.setUtteranceListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                if (!isServiceAlive) return
                Log.d(TAG, "TTS started: $utteranceId")
            }

            override fun onDone(utteranceId: String?) {
                if (!isServiceAlive) return
                Log.d(TAG, "TTS done: $utteranceId")
                if (utteranceId == "call_announcement") {
                    handler.postDelayed({
                        if (isServiceAlive) {
                            onAnnouncementComplete()
                        }
                    }, POST_TTS_BUFFER_MS)
                }
            }

            override fun onError(utteranceId: String?) {
                if (!isServiceAlive) return
                Log.e(TAG, "TTS error: $utteranceId")
                if (utteranceId == "call_announcement") {
                    handler.post {
                        if (isServiceAlive) {
                            onAnnouncementComplete()
                        }
                    }
                }
            }
        })
    }

    // ==================== AUDIO FEEDBACK ====================

    /**
     * Play a short beep using system tones
     */
    private fun playBeep() {
        try {
            val toneGen = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
            toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
            handler.postDelayed({
                toneGen.release()
            }, 200)
        } catch (e: Exception) {
            Log.e(TAG, "Beep error", e)
        }
    }

    // ==================== SINGLE MATCH FLOW ====================

    private fun handleSingleMatch(name: String, number: String, routing: String) {
        Log.i(TAG, "Single match: $name -> $number (routing: $routing)")

        pendingName = name
        pendingNumber = number
        pendingRouting = routing

        currentPhase = CallPhase.ANNOUNCING
        handler.postDelayed({
            playBeep()
            announceCall(name)
            handler.postDelayed({
                // Show overlay via controller
                CallOverlayController.showAnnouncing(
                    contactName = name,
                    autoCall = autoCallEnabled,
                    onConfirm = if (!autoCallEnabled) { { handleCallTap() } } else null,
                    onCancel = { handleCancelTap() }
                )
            }, 300)
        }, 400)
    }

    private fun announceCall(name: String) {
        // Strip routing suffix from TTS announcement
        val cleanName = stripRoutingSuffix(name)
        val text = "Καλώ $cleanName"
        Log.d(TAG, "Announcing: $text")
        TtsManager.setSpeechRate(TTS_RATE_NORMAL)
        TtsManager.speak(text, "call_announcement")
    }

    private fun onAnnouncementComplete() {
        Log.d(TAG, "Announcement complete, phase=$currentPhase, autoCall=$autoCallEnabled")

        if (currentPhase != CallPhase.ANNOUNCING) {
            Log.d(TAG, "Call was cancelled during announcement")
            return
        }

        // Check auto-call setting
        if (autoCallEnabled) {
            Log.d(TAG, "Auto-call enabled, placing call immediately")
            currentPhase = CallPhase.CALLING
            placeCall()
        } else {
            Log.d(TAG, "Auto-call disabled, confirmation overlay already showing - waiting for user tap")
            // Green+red overlay is already showing, just wait for user to tap
        }
    }

    // ==================== CALL PLACEMENT ====================

    private fun placeCall() {
        val number = pendingNumber ?: return
        val routing = pendingRouting ?: ""
        Log.i(TAG, "Placing call to $number with routing '$routing'")
        vibrateShort()

        // Determine package name from routing
        val voipPackage = when {
            routing.contains("VIBER", ignoreCase = true) -> VIBER_PACKAGE
            routing.contains("WHATSAPP", ignoreCase = true) -> WHATSAPP_PACKAGE
            routing.contains("SIGNAL", ignoreCase = true) -> SIGNAL_PACKAGE
            else -> null
        }

        if (voipPackage != null) {
            // Delegate to VoIPManager - it handles its own timing
            placeVoIPCall(voipPackage, number)
        } else {
            // Regular phone call
            placeRegularCall(number)
            handler.postDelayed({
                cleanup()
            }, 800)
        }
    }

    /**
     * Place a VoIP call via VoIPManager.
     * Handles auto-click vs haptic guide based on settings and calibration.
     */
    private fun placeVoIPCall(packageName: String, number: String) {
        Log.d(TAG, "Delegating VoIP call to VoIPManager: $packageName -> $number")

        // Check if app is available
        if (!VoIPManager.isAppAvailable(this, packageName)) {
            val appName = when (packageName) {
                VIBER_PACKAGE -> "Viber"
                WHATSAPP_PACKAGE -> "WhatsApp"
                SIGNAL_PACKAGE -> "Signal"
                else -> "Η εφαρμογή"
            }
            Log.w(TAG, "$appName not installed, falling back to regular call")
            speakError("$appName δεν είναι εγκατεστημένο")
            placeRegularCall(number)
            return
        }

        // Dismiss overlay before launching VoIP app
        CallOverlayController.dismiss()

        VoIPManager.placeCall(
            context = this,
            packageName = packageName,
            phoneNumber = number,
            onSuccess = {
                Log.i(TAG, "VoIP call initiated successfully")
                // Just stop the service quietly - don't cancel polling
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            },
            onFailure = { errorMessage ->
                Log.w(TAG, "VoIP call failed: $errorMessage")
                playErrorFeedback()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        )
    }

    private fun placeRegularCall(number: String) {
        Log.d(TAG, "Placing regular call to $number")

        try {
            val callIntent = Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:${sanitizeNumber(number)}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }

            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CALL_PHONE)
                == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                startActivity(callIntent)
            } else {
                Log.e(TAG, "CALL_PHONE permission not granted")
                speakError("Δεν έχω άδεια για κλήσεις")
                cleanup()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error placing call", e)
            speakError("Σφάλμα κλήσης")
            cleanup()
        }
    }

    /**
     * Play error feedback (same as "δεν βρέθηκε επαφή" pattern).
     */
    private fun playErrorFeedback() {
        vibrateShort()
        playBeep()
    }

    // ==================== AMBIGUOUS MATCH FLOW ====================

    private fun handleAmbiguousMatch(names: List<String>, numbers: List<String>, routings: List<String>) {
        Log.i(TAG, "Ambiguous match: ${names.joinToString(" vs ")}")

        ambiguousNames = names
        ambiguousNumbers = numbers
        ambiguousRoutings = routings

        currentPhase = CallPhase.SELECTION

        playBeep()

        // Show selection overlay via controller
        CallOverlayController.showSelection(
            name1 = names.getOrNull(0) ?: "?",
            name2 = names.getOrNull(1) ?: "?",
            onSelect = { index -> onSelectionMade(index) },
            onCancel = { handleCancelTap() }
        )

        // Announce options with slower speech
        handler.postDelayed({
            val name1 = stripRoutingSuffix(names.getOrNull(0) ?: "?")
            val name2 = stripRoutingSuffix(names.getOrNull(1) ?: "?")
            val prompt = "Επιλογή: $name1 ή $name2"
            Log.d(TAG, "Announcing: $prompt")
            TtsManager.setSpeechRate(TTS_RATE_SLOW)
            TtsManager.speak(prompt, "selection_prompt")
        }, 300)
    }

    private fun onSelectionMade(index: Int) {
        Log.i(TAG, "Selection made: index=$index")

        vibrateShort()

        val name = ambiguousNames?.getOrNull(index)
        val number = ambiguousNumbers?.getOrNull(index)
        val routing = ambiguousRoutings?.getOrNull(index) ?: ""

        if (name != null && number != null) {
            TtsManager.stop()

            pendingName = name
            pendingNumber = number
            pendingRouting = routing

            currentPhase = CallPhase.ANNOUNCING
            announceCall(name)

            // Show announcing overlay (always auto-call after selection)
            CallOverlayController.showAnnouncing(
                contactName = name,
                autoCall = true,
                onConfirm = null,
                onCancel = { handleCancelTap() }
            )
        } else {
            Log.e(TAG, "Invalid selection index: $index")
            cleanup()
        }
    }

    // ==================== CANCEL HANDLING ====================

    /**
     * Handle green call button tap
     */
    private fun handleCallTap() {
        Log.i(TAG, "Call button tapped, placing call")

        vibrateShort()

        currentPhase = CallPhase.CALLING
        placeCall()
    }

    private fun handleCancelTap() {
        Log.i(TAG, "Cancel tap received, phase=$currentPhase")

        vibrateShort()

        when (currentPhase) {
            CallPhase.ANNOUNCING -> {
                Log.i(TAG, "Aborting call before dial")
                TtsManager.stop()
                currentPhase = CallPhase.IDLE
                TtsManager.setSpeechRate(TTS_RATE_NORMAL)
                TtsManager.speak("Ακυρώθηκε", "cancelled")

                handler.postDelayed({
                    cleanup()
                }, 1500)
            }

            CallPhase.CALLING -> {
                Log.i(TAG, "Call already placed, cleaning up")
                cleanup()
            }

            CallPhase.SELECTION -> {
                Log.i(TAG, "Cancelling selection")
                TtsManager.stop()
                currentPhase = CallPhase.IDLE
                TtsManager.setSpeechRate(TTS_RATE_NORMAL)
                TtsManager.speak("Ακυρώθηκε", "cancelled")

                handler.postDelayed({
                    cleanup()
                }, 1500)
            }

            CallPhase.IDLE -> {
                cleanup()
            }
        }
    }

    // ==================== UTILITIES ====================

    /**
     * Strip routing suffix (VIBER, WHATSAPP, SIGNAL) from display name
     */
    private fun stripRoutingSuffix(name: String): String {
        val suffixes = listOf("viber", "whatsapp", "signal")
        val parts = name.trim().split("\\s+".toRegex())

        return if (parts.isNotEmpty() && parts.last().lowercase() in suffixes) {
            parts.dropLast(1).joinToString(" ")
        } else {
            name
        }
    }

    private fun sanitizeNumber(number: String): String {
        return number.replace(Regex("[\\s\\-().]"), "")
    }

    private fun vibrateShort() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(100)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Vibration error", e)
        }
    }

    private fun speakError(message: String) {
        TtsManager.setSpeechRate(TTS_RATE_NORMAL)
        TtsManager.speak(message, "error")
    }

    private fun cleanup() {
        Log.d(TAG, "Cleaning up CallManagerService")

        currentPhase = CallPhase.IDLE

        pendingName = null
        pendingNumber = null
        pendingRouting = null
        ambiguousNames = null
        ambiguousNumbers = null
        ambiguousRoutings = null

        // Dismiss overlay via controller
        CallOverlayController.dismiss()

        // Cancel any VoIP polling
        VoIPManager.cancelPolling()

        TtsManager.stop()
        TtsManager.setSpeechRate(TTS_RATE_NORMAL)

        handler.removeCallbacksAndMessages(null)

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
}
