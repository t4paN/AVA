// CallManagerService.kt

package com.example.greekvoiceassistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.telecom.TelecomManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.Locale

/**
 * CallManagerService - Owns the entire post-match call flow
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
        
        // Colors
        private const val COLOR_RED = 0xFFCC0000.toInt()
        private const val COLOR_ORANGE = 0xFFFF8C00.toInt()
        private const val COLOR_BLUE = 0xFF4169E1.toInt()
        
        // Max characters before truncation
        private const val MAX_NAME_LENGTH = 12
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
    private var windowManager: WindowManager? = null
    private var tts: TextToSpeech? = null
    private var vibrator: Vibrator? = null
    private var telephonyManager: TelephonyManager? = null
    private var telecomManager: TelecomManager? = null

    // Overlays
    private var overlayView: View? = null
    private var selectionOverlay: View? = null

    // Handler for delays
    private val handler = Handler(Looper.getMainLooper())

    // ==================== LIFECYCLE ====================

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "CallManagerService created")

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        telecomManager = getSystemService(Context.TELECOM_SERVICE) as TelecomManager

        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        initTTS()
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

    private fun initTTS() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale("el", "GR"))
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.e(TAG, "Greek TTS not supported, falling back to default")
                    tts?.setLanguage(Locale.getDefault())
                }
                Log.d(TAG, "TTS initialized")
            } else {
                Log.e(TAG, "TTS initialization failed")
            }
        }

        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                Log.d(TAG, "TTS started: $utteranceId")
            }

            override fun onDone(utteranceId: String?) {
                Log.d(TAG, "TTS done: $utteranceId")
                if (utteranceId == "call_announcement") {
                    handler.postDelayed({
                        onAnnouncementComplete()
                    }, POST_TTS_BUFFER_MS)
                }
            }

            override fun onError(utteranceId: String?) {
                Log.e(TAG, "TTS error: $utteranceId")
                if (utteranceId == "call_announcement") {
                    handler.post { onAnnouncementComplete() }
                }
            }
        })
    }

    // ==================== SINGLE MATCH FLOW ====================

    private fun handleSingleMatch(name: String, number: String, routing: String) {
        Log.i(TAG, "Single match: $name -> $number (routing: $routing)")

        pendingName = name
        pendingNumber = number
        pendingRouting = routing

        currentPhase = CallPhase.ANNOUNCING
        handler.postDelayed({
            announceCall(name)
            handler.postDelayed({
                showCancelOverlay(name)
            }, 300)
        }, 400)
    }

    private fun announceCall(name: String) {
        val text = "Καλώ $name"
        Log.d(TAG, "Announcing: $text")
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "call_announcement")
    }

    private fun onAnnouncementComplete() {
        Log.d(TAG, "Announcement complete, phase=$currentPhase")

        if (currentPhase != CallPhase.ANNOUNCING) {
            Log.d(TAG, "Call was cancelled during announcement")
            return
        }

        // Proceed to place call
        currentPhase = CallPhase.CALLING
        placeCall()
    }


    private fun placeCall() {
        val number = pendingNumber ?: return
        val routing = pendingRouting ?: ""

        Log.i(TAG, "Placing call to $number with routing '$routing'")

        vibrateShort()

        when {
            routing.contains("VIBER", ignoreCase = true) -> placeViberCall(number)
            routing.contains("WHATSAPP", ignoreCase = true) -> placeWhatsAppCall(number)
            else -> placeRegularCall(number)
        }

        handler.postDelayed({
            cleanup()
        }, 800)
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

    private fun placeViberCall(number: String) {
        Log.d(TAG, "Placing Viber call to $number")

        try {
            val uri = Uri.parse("viber://call?number=${sanitizeNumber(number)}")
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }

            if (intent.resolveActivity(packageManager) != null) {
                startActivity(intent)
            } else {
                Log.w(TAG, "Viber not installed, falling back to regular call")
                speakError("Το Viber δεν είναι εγκατεστημένο")
                placeRegularCall(number)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error placing Viber call", e)
            placeRegularCall(number)
        }
    }

    private fun placeWhatsAppCall(number: String) {
        Log.d(TAG, "Placing WhatsApp call to $number")

        try {
            val intlNumber = formatForWhatsApp(number)
            val uri = Uri.parse("https://wa.me/$intlNumber?voice_call=1")
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                setPackage("com.whatsapp")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }

            if (intent.resolveActivity(packageManager) != null) {
                startActivity(intent)
            } else {
                Log.w(TAG, "WhatsApp not installed, falling back to regular call")
                speakError("Το WhatsApp δεν είναι εγκατεστημένο")
                placeRegularCall(number)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error placing WhatsApp call", e)
            placeRegularCall(number)
        }
    }

    // ==================== AMBIGUOUS MATCH FLOW ====================

    private fun handleAmbiguousMatch(names: List<String>, numbers: List<String>, routings: List<String>) {
        Log.i(TAG, "Ambiguous match: ${names.joinToString(" vs ")}")

        ambiguousNames = names
        ambiguousNumbers = numbers
        ambiguousRoutings = routings

        currentPhase = CallPhase.SELECTION

        // Show selection overlay with two boxes
        showSelectionOverlay(
            names.getOrNull(0) ?: "?",
            names.getOrNull(1) ?: "?"
        )

        // Announce options
        handler.postDelayed({
            val prompt = "Επιλογή: ${names.getOrNull(0)} ή ${names.getOrNull(1)}"
            Log.d(TAG, "Announcing: $prompt")
            tts?.speak(prompt, TextToSpeech.QUEUE_FLUSH, null, "selection_prompt")
        }, 300)
    }

    private fun onSelectionMade(index: Int) {
        Log.i(TAG, "Selection made: index=$index")

        val names = ambiguousNames ?: return
        val numbers = ambiguousNumbers ?: return
        val routings = ambiguousRoutings ?: emptyList()

        if (index >= names.size) return

        vibrateShort()

        // Hide selection overlay
        hideSelectionOverlay()

        // Set up the selected contact
        pendingName = names[index]
        pendingNumber = numbers[index]
        pendingRouting = routings.getOrNull(index) ?: ""

        // Show cancel overlay with selected name
        showCancelOverlay(pendingName!!)

        // Announce and proceed to call
        currentPhase = CallPhase.ANNOUNCING
        handler.postDelayed({
            announceCall(pendingName!!)
        }, 300)
    }

    // ==================== SELECTION OVERLAY (Orange/Blue boxes) ====================

    private fun showSelectionOverlay(name1: String, name2: String) {
        if (selectionOverlay != null) {
            Log.w(TAG, "Selection overlay already showing")
            return
        }

        Log.d(TAG, "Showing selection overlay: $name1 vs $name2")

        val displayMetrics = resources.displayMetrics
        val screenHeight = displayMetrics.heightPixels
        val screenWidth = displayMetrics.widthPixels

        val marginDp = 24
        val marginPx = (marginDp * displayMetrics.density).toInt()
        val gapPx = (10 * displayMetrics.density).toInt() // Gap between boxes

        val overlayWidth = screenWidth - (marginPx * 2)
        val overlayHeight = (screenHeight / 2) - marginPx
        val boxWidth = ((overlayWidth - gapPx) * 0.45).toInt()

        // Container
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        // Left box (Orange) - First contact
        val leftBox = FrameLayout(this).apply {
            setBackgroundColor(COLOR_ORANGE)
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN) {
                    onSelectionMade(0)
                }
                true
            }
        }

        val leftText = TextView(this).apply {
            text = truncateName(name1)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 32f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        leftBox.addView(leftText, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        // Right box (Blue) - Second contact
        val rightBox = FrameLayout(this).apply {
            setBackgroundColor(COLOR_BLUE)
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN) {
                    onSelectionMade(1)
                }
                true
            }
        }

        val rightText = TextView(this).apply {
            text = truncateName(name2)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 32f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        rightBox.addView(rightText, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        // Add boxes to container
        val leftParams = LinearLayout.LayoutParams(boxWidth, LinearLayout.LayoutParams.MATCH_PARENT)
        leftParams.marginEnd = gapPx / 2
        container.addView(leftBox, leftParams)

        val rightParams = LinearLayout.LayoutParams(boxWidth, LinearLayout.LayoutParams.MATCH_PARENT)
        rightParams.marginStart = gapPx / 2
        container.addView(rightBox, rightParams)

        selectionOverlay = container

        val params = WindowManager.LayoutParams(
            overlayWidth,
            overlayHeight,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = marginPx
        }

        try {
            windowManager?.addView(selectionOverlay, params)
            Log.d(TAG, "Selection overlay added successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add selection overlay", e)
        }
    }

    private fun hideSelectionOverlay() {
        selectionOverlay?.let { view ->
            try {
                windowManager?.removeView(view)
                Log.d(TAG, "Selection overlay removed")
            } catch (e: Exception) {
                Log.e(TAG, "Error removing selection overlay", e)
            }
        }
        selectionOverlay = null
    }

    // ==================== CANCEL OVERLAY (Red button with name) ====================

    private fun showCancelOverlay(contactName: String) {
        if (overlayView != null) {
            Log.w(TAG, "Overlay already showing")
            return
        }

        Log.d(TAG, "Showing cancel overlay for: $contactName")

        val container = FrameLayout(this).apply {
            setBackgroundColor(COLOR_RED)
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN) {
                    handleCancelTap()
                }
                true
            }
        }

        // Big ass text with contact name
        val nameText = TextView(this).apply {
            text = "[${contactName.uppercase()}]"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 40f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        container.addView(nameText, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        overlayView = container

        val displayMetrics = resources.displayMetrics
        val screenHeight = displayMetrics.heightPixels
        val screenWidth = displayMetrics.widthPixels

        val marginDp = 24
        val marginPx = (marginDp * displayMetrics.density).toInt()

        val params = WindowManager.LayoutParams(
            screenWidth - (marginPx * 2),
            (screenHeight / 2) - marginPx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = marginPx
        }

        try {
            windowManager?.addView(overlayView, params)
            Log.d(TAG, "Overlay added successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add overlay", e)
        }
    }

    private fun hideCancelOverlay() {
        overlayView?.let { view ->
            try {
                windowManager?.removeView(view)
                Log.d(TAG, "Overlay removed")
            } catch (e: Exception) {
                Log.e(TAG, "Error removing overlay", e)
            }
        }
        overlayView = null
    }

    private fun handleCancelTap() {
        Log.i(TAG, "Cancel tap received, phase=$currentPhase")

        vibrateShort()

        when (currentPhase) {
            CallPhase.ANNOUNCING -> {
                Log.i(TAG, "Aborting call before dial")
                tts?.stop()
                currentPhase = CallPhase.IDLE
                tts?.speak("Ακυρώθηκε", TextToSpeech.QUEUE_FLUSH, null, "cancelled")

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
                tts?.stop()
                currentPhase = CallPhase.IDLE
                tts?.speak("Ακυρώθηκε", TextToSpeech.QUEUE_FLUSH, null, "cancelled")

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

    private fun truncateName(name: String): String {
        return if (name.length > MAX_NAME_LENGTH) {
            name.take(MAX_NAME_LENGTH - 3) + "..."
        } else {
            name
        }
    }

    private fun sanitizeNumber(number: String): String {
        return number.replace(Regex("[\\s\\-().]"), "")
    }

    private fun formatForWhatsApp(number: String): String {
        var clean = sanitizeNumber(number)

        if (clean.startsWith("+")) {
            clean = clean.substring(1)
        }

        if (clean.startsWith("69") && clean.length == 10) {
            clean = "30$clean"
        }

        return clean
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
        tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "error")
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

        hideCancelOverlay()
        hideSelectionOverlay()

        tts?.stop()

        handler.removeCallbacksAndMessages(null)

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
}
