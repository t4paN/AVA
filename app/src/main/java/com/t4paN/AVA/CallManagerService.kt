// CallManagerService.kt

package com.t4paN.AVA

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
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
import androidx.core.widget.TextViewCompat

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

        // TTS Speech rates
        private const val TTS_RATE_NORMAL = 1.0f
        private const val TTS_RATE_SLOW = 0.75f

        // Colors
        private const val COLOR_RED = 0xFFCC0000.toInt()
        private const val COLOR_ORANGE = 0xFFFF8C00.toInt()
        private const val COLOR_BLUE = 0xFF4169E1.toInt()
        private const val COLOR_GREEN = 0xFF00AA00.toInt()
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
    private var vibrator: Vibrator? = null
    private var telephonyManager: TelephonyManager? = null
    private var telecomManager: TelecomManager? = null

    // Overlays
    private var overlayView: View? = null
    private var selectionOverlay: View? = null

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
                // Check auto-call setting to decide which overlay to show
                if (autoCallEnabled) {
                    // Show red cancel overlay (will auto-call after TTS)
                    showCancelOverlay(name)
                } else {
                    // Show green+red confirmation overlay immediately
                    showCallConfirmationOverlay()
                }
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
            // No need to hide/show anything
        }
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
            val uri = Uri.parse("viber://chat?number=${sanitizeNumber(number)}")
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                setPackage("com.viber.voip")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }

            if (packageManager.getLaunchIntentForPackage("com.viber.voip") != null) {
                startActivity(intent)

                // Show haptic guide after Viber opens
                handler.postDelayed({
                    HapticGuideManager.start(
                        context = this,
                        packageName = "com.viber.voip",
                        onTapped = {
                            Log.i(TAG, "User tapped Viber call button")
                            handler.postDelayed({
                                HapticGuideManager.stop()
                            }, 2000)
                        },

                        onCancelled = {
                            Log.i(TAG, "User cancelled Viber guide")
                            HapticGuideManager.stop()
                        }
                    )
                }, 800)  // Let Viber load first

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

            if (packageManager.getLaunchIntentForPackage("com.whatsapp") != null) {
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

        playBeep()

        // Show selection overlay with boxes above, cancel below
        showSelectionOverlay(
            names.getOrNull(0) ?: "?",
            names.getOrNull(1) ?: "?"
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
            TtsManager.setSpeechRate(TTS_RATE_NORMAL)
            announceCall(pendingName!!)
        }, 300)
    }

    // ==================== SELECTION OVERLAY (Orange/Blue boxes above, Cancel below) ====================

    private fun showSelectionOverlay(name1: String, name2: String) {
        if (selectionOverlay != null) {
            Log.w(TAG, "Selection overlay already showing")
            return
        }

        Log.d(TAG, "Showing selection overlay: $name1 vs $name2")

        val displayMetrics = resources.displayMetrics
        val screenHeight = displayMetrics.heightPixels
        val screenWidth = displayMetrics.widthPixels
        val density = displayMetrics.density

        // Smaller side margins, bigger gap between boxes
        val sideMarginPx = (12 * density).toInt()
        val boxGapPx = (20 * density).toInt()
        val bottomMarginPx = (24 * density).toInt()
        val cornerRadiusPx = (24 * density)

        // Heights
        val cancelHeight = (screenHeight * 0.40).toInt()
        val selectionHeight = (screenHeight * 0.45).toInt()
        val gapBetweenSections = (16 * density).toInt()

        val totalWidth = screenWidth - (sideMarginPx * 2)
        val boxWidth = (totalWidth - boxGapPx) / 2

        // === CANCEL BUTTON (bottom) ===
        val cancelButton = FrameLayout(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(COLOR_RED)
                cornerRadius = cornerRadiusPx
            }
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN) {
                    handleCancelTap()
                }
                true
            }
        }

        val cancelText = TextView(this).apply {
            text = "ΑΚΥΡΩΣΗ"
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 48f)
        }
        cancelButton.addView(cancelText, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        // === SELECTION CONTAINER (above cancel) ===
        val selectionContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        // Left box (Orange)
        val leftBox = FrameLayout(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(COLOR_ORANGE)
                cornerRadius = cornerRadiusPx
            }
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN) {
                    onSelectionMade(0)
                }
                true
            }
        }

        val leftText = TextView(this).apply {
            text = formatNameForButton(name1)
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding((8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt())
        }
        TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
            leftText, 16, 48, 2, TypedValue.COMPLEX_UNIT_SP
        )
        leftBox.addView(leftText, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        // Right box (Blue)
        val rightBox = FrameLayout(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(COLOR_BLUE)
                cornerRadius = cornerRadiusPx
            }
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN) {
                    onSelectionMade(1)
                }
                true
            }
        }

        val rightText = TextView(this).apply {
            text = formatNameForButton(name2)
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding((8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt())
        }
        TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
            rightText, 16, 48, 2, TypedValue.COMPLEX_UNIT_SP
        )
        rightBox.addView(rightText, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        // Add boxes to selection container
        val leftParams = LinearLayout.LayoutParams(boxWidth, LinearLayout.LayoutParams.MATCH_PARENT)
        leftParams.marginEnd = boxGapPx / 2
        selectionContainer.addView(leftBox, leftParams)

        val rightParams = LinearLayout.LayoutParams(boxWidth, LinearLayout.LayoutParams.MATCH_PARENT)
        rightParams.marginStart = boxGapPx / 2
        selectionContainer.addView(rightBox, rightParams)

        // === MAIN CONTAINER ===
        val mainContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        }

        val selectionParams = LinearLayout.LayoutParams(totalWidth, selectionHeight)
        selectionParams.bottomMargin = gapBetweenSections
        mainContainer.addView(selectionContainer, selectionParams)

        val cancelParams = LinearLayout.LayoutParams(totalWidth, cancelHeight)
        mainContainer.addView(cancelButton, cancelParams)

        selectionOverlay = mainContainer

        val params = WindowManager.LayoutParams(
            screenWidth,
            selectionHeight + cancelHeight + gapBetweenSections + bottomMarginPx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = bottomMarginPx
            x = 0
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

    // ==================== CALL CONFIRMATION OVERLAY (Green call + Red cancel) ====================

    private fun showCallConfirmationOverlay() {
        if (overlayView != null) {
            Log.w(TAG, "Overlay already showing")
            return
        }

        val name = pendingName ?: return
        Log.d(TAG, "Showing call confirmation overlay for: $name")

        val displayMetrics = resources.displayMetrics
        val screenHeight = displayMetrics.heightPixels
        val screenWidth = displayMetrics.widthPixels
        val density = displayMetrics.density

        val marginPx = (24 * density).toInt()
        val gapPx = (16 * density).toInt()
        val cornerRadiusPx = (24 * density)

        // Calculate heights
        val greenHeight = (screenHeight * 0.50).toInt()
        val redHeight = (screenHeight * 0.35).toInt()

        // Main container
        val mainContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        }

        // GREEN CALL BUTTON (top)
        val greenButton = FrameLayout(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(COLOR_GREEN)
                cornerRadius = cornerRadiusPx
            }
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN) {
                    handleCallTap()
                }
                true
            }
        }

        val nameText = TextView(this).apply {
            text = formatNameForButton(stripRoutingSuffix(name))
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding((16 * density).toInt(), (16 * density).toInt(), (16 * density).toInt(), (16 * density).toInt())
        }
        TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
            nameText, 24, 72, 2, TypedValue.COMPLEX_UNIT_SP
        )
        greenButton.addView(nameText, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        // RED CANCEL BUTTON (bottom)
        val redButton = FrameLayout(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(COLOR_RED)
                cornerRadius = cornerRadiusPx
            }
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN) {
                    handleCancelTap()
                }
                true
            }
        }

        val cancelText = TextView(this).apply {
            text = "ΑΚΥΡΩΣΗ"
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 42f)
        }
        redButton.addView(cancelText, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        // Add buttons to container
        val greenParams = LinearLayout.LayoutParams(screenWidth - (marginPx * 2), greenHeight)
        greenParams.bottomMargin = gapPx
        mainContainer.addView(greenButton, greenParams)

        val redParams = LinearLayout.LayoutParams(screenWidth - (marginPx * 2), redHeight)
        mainContainer.addView(redButton, redParams)

        overlayView = mainContainer

        val params = WindowManager.LayoutParams(
            screenWidth,
            greenHeight + redHeight + gapPx + marginPx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = marginPx
            x = 0
        }

        try {
            windowManager?.addView(overlayView, params)
            Log.d(TAG, "Call confirmation overlay added successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add call confirmation overlay", e)
        }
    }

    /**
     * Handle green call button tap
     */
    private fun handleCallTap() {
        Log.i(TAG, "Call button tapped, placing call")

        vibrateShort()

        currentPhase = CallPhase.CALLING
        placeCall()
    }

    // ==================== CANCEL OVERLAY (Red button with name) ====================

    private fun showCancelOverlay(contactName: String) {
        if (overlayView != null) {
            Log.w(TAG, "Overlay already showing")
            return
        }

        Log.d(TAG, "Showing cancel overlay for: $contactName")

        val displayMetrics = resources.displayMetrics
        val screenHeight = displayMetrics.heightPixels
        val screenWidth = displayMetrics.widthPixels
        val density = displayMetrics.density

        val marginPx = (24 * density).toInt()
        val cornerRadiusPx = (24 * density)

        val container = FrameLayout(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(COLOR_RED)
                cornerRadius = cornerRadiusPx
            }
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN) {
                    handleCancelTap()
                }
                true
            }
        }

        // Big text with contact name - no brackets, words on separate lines
        val nameText = TextView(this).apply {
            text = formatNameForButton(stripRoutingSuffix(contactName))
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding((16 * density).toInt(), (16 * density).toInt(), (16 * density).toInt(), (16 * density).toInt())
        }
        TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
            nameText, 24, 72, 2, TypedValue.COMPLEX_UNIT_SP
        )
        container.addView(nameText, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        overlayView = container

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
     * Format name for button display:
     * - No brackets
     * - Each word on new line for max size
     * - Uppercase for readability
     */
    private fun formatNameForButton(name: String): String {
        return name.uppercase().split(" ").joinToString("\n")
    }

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

        hideCancelOverlay()
        hideSelectionOverlay()

        TtsManager.stop()
        TtsManager.setSpeechRate(TTS_RATE_NORMAL)

        handler.removeCallbacksAndMessages(null)

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
}