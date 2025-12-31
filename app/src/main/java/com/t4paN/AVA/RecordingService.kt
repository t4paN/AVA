//RecordingService.kt

package com.t4paN.AVA

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.greekvoiceassistant.whisper.engine.WhisperEngine
import com.greekvoiceassistant.whisper.engine.WhisperEngineJava
import java.io.File
import java.io.FileOutputStream
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import android.media.AudioManager
import android.media.ToneGenerator

class RecordingService : Service() {

    private var audioRecord: AudioRecord? = null
    private val handler = Handler(Looper.getMainLooper())
    private var isRecording = false
    private var isProcessing = false
    private var recordingThread: Thread? = null
    private var vadPipeline: VadAudioPipeline? = null

    // TTS for prompt
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    // Vibrator for haptic feedback
    private var vibrator: Vibrator? = null

    // Cancel overlay
    private var windowManager: WindowManager? = null
    private var cancelOverlay: View? = null
    private var isCancelled = false

    // Timeout runnable as a field so we can cancel it specifically
    private val timeoutRunnable = Runnable {
        Log.d(TAG, "4-second timeout reached")
        stopRecordingImmediately()
    }

    // Cache contacts in memory for fuzzy matching
    private var cachedContacts: List<Contact> = emptyList()

    // Broadcast receiver for contact refresh
    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "REFRESH_CONTACTS") {
                cachedContacts = ContactRepository.reloadContacts(this@RecordingService)
                Log.i(TAG, "Contacts refreshed: ${cachedContacts.size}")
            }
        }
    }

    companion object {
        private const val TAG = "RecordingService"
        private const val RECORDING_DURATION_MS = 4000L
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

        // SharedPreferences for persisting logs
        private const val PREFS_NAME = "ava_transcription_logs"
        private const val PREFS_KEY_LOGS = "logs_json"
        private const val MAX_STORED_LOGS = 50

        // Nuclear reset action
        const val ACTION_NUKE_APP = "NUKE_APP"

        // Colors
        private const val COLOR_RED = 0xFFCC0000.toInt()

        // Store transcription logs for display in FirstFragment
        private val transcriptionLogs = mutableListOf<TranscriptionLog>()
        private var logUpdateCallback: (() -> Unit)? = null

        // PERSISTENT WHISPER ENGINE - survives service destroy/recreate
        @Volatile
        private var sharedWhisperEngine: WhisperEngine? = null
        private val whisperLock = Any()

        fun getTranscriptionLogs(): List<TranscriptionLog> {
            return transcriptionLogs.reversed()
        }

        fun setLogUpdateCallback(callback: () -> Unit) {
            logUpdateCallback = callback
        }

        fun clearLogUpdateCallback() {
            logUpdateCallback = null
        }

        fun clearLogs(context: Context) {
            transcriptionLogs.clear()
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().remove(PREFS_KEY_LOGS).apply()
            logUpdateCallback?.invoke()
        }

        fun loadPersistedLogs(context: Context) {
            try {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val jsonString = prefs.getString(PREFS_KEY_LOGS, null) ?: return

                val jsonArray = JSONArray(jsonString)
                transcriptionLogs.clear()

                for (i in 0 until jsonArray.length()) {
                    val json = jsonArray.getJSONObject(i)

                    val ambiguousCandidates = if (json.has("ambiguousCandidates")) {
                        val candidatesArray = json.getJSONArray("ambiguousCandidates")
                        List(candidatesArray.length()) { idx ->
                            val candidate = candidatesArray.getJSONObject(idx)
                            Pair(
                                candidate.getString("name"),
                                candidate.getDouble("confidence")
                            )
                        }
                    } else null

                    val log = TranscriptionLog(
                        timestamp = json.getLong("timestamp"),
                        originalTranscript = json.getString("originalTranscript"),
                        fuzzifiedTranscript = json.getString("fuzzifiedTranscript"),
                        transcriptionTimeMs = json.getLong("transcriptionTimeMs"),
                        matchedContact = json.optString("matchedContact", null),
                        confidence = if (json.has("confidence")) json.getDouble("confidence") else null,
                        confidenceBreakdown = json.optString("confidenceBreakdown", null),
                        ambiguousCandidates = ambiguousCandidates,
                        noIntentDetected = json.optBoolean("noIntentDetected", false)
                    )

                    transcriptionLogs.add(log)
                }

                Log.i(TAG, "Loaded ${transcriptionLogs.size} persisted logs")

            } catch (e: Exception) {
                Log.e(TAG, "Error loading persisted logs", e)
            }
        }

        private fun saveLogsToPrefs(context: Context) {
            try {
                val jsonArray = JSONArray()
                val logsToSave = transcriptionLogs.takeLast(MAX_STORED_LOGS)

                for (log in logsToSave) {
                    val json = JSONObject().apply {
                        put("timestamp", log.timestamp)
                        put("originalTranscript", log.originalTranscript)
                        put("fuzzifiedTranscript", log.fuzzifiedTranscript)
                        put("transcriptionTimeMs", log.transcriptionTimeMs)
                        put("matchedContact", log.matchedContact)
                        put("confidence", log.confidence)
                        put("confidenceBreakdown", log.confidenceBreakdown)
                        put("noIntentDetected", log.noIntentDetected)

                        if (log.ambiguousCandidates != null) {
                            val candidatesArray = JSONArray()
                            for ((name, conf) in log.ambiguousCandidates) {
                                candidatesArray.put(JSONObject().apply {
                                    put("name", name)
                                    put("confidence", conf)
                                })
                            }
                            put("ambiguousCandidates", candidatesArray)
                        }
                    }
                    jsonArray.put(json)
                }

                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit().putString(PREFS_KEY_LOGS, jsonArray.toString()).apply()

                Log.d(TAG, "Saved ${logsToSave.size} logs to SharedPreferences")

            } catch (e: Exception) {
                Log.e(TAG, "Error saving logs to prefs", e)
            }
        }
    }

    private fun safeToast(msg: String) {
        try {
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.e("ToastFail", "System toast blocked. Falling back.", e)
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        startSilentNotification()

        // Initialize WindowManager
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        // Load persisted logs on startup
        loadPersistedLogs(this)

        // Load contacts once when service starts
        cachedContacts = ContactRepository.loadContacts(this)
        Log.i(TAG, "RecordingService created with ${cachedContacts.size} cached contacts")

        // Register broadcast receiver for contact refresh
        val filter = IntentFilter("REFRESH_CONTACTS")
        registerReceiver(refreshReceiver, filter, Context.RECEIVER_NOT_EXPORTED)

        // Initialize VAD pipeline
        vadPipeline = VadAudioPipeline(this)

        // Initialize vibrator
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        // Initialize TTS
        initTTS()
    }

    private fun initTTS() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale("el", "GR"))
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.w(TAG, "Greek TTS not available, using default")
                    tts?.setLanguage(Locale.getDefault())
                }
                ttsReady = true
                Log.d(TAG, "TTS initialized and ready")
            } else {
                Log.e(TAG, "TTS initialization failed")
                ttsReady = false
            }
        }

        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                Log.d(TAG, "TTS started: $utteranceId")
            }

            override fun onDone(utteranceId: String?) {
                Log.d(TAG, "TTS done: $utteranceId")
                if (utteranceId == "prompt") {
                    // TTS finished, now vibrate and start recording
                    handler.post {
                        if (!isCancelled) {
                            vibrateAndStartRecording()
                        }
                    }
                }
            }

            override fun onError(utteranceId: String?) {
                Log.e(TAG, "TTS error: $utteranceId")
                if (utteranceId == "prompt") {
                    // Even if TTS fails, proceed with recording
                    handler.post {
                        if (!isCancelled) {
                            vibrateAndStartRecording()
                        }
                    }
                }
            }
        })
    }

    private fun startSilentNotification() {
        val channelId = "voice_assistant_channel"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(
                channelId, "Voice Assistant",
                NotificationManager.IMPORTANCE_LOW
            )
            chan.setSound(null, null)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(chan)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("AVA Standing By")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSound(null)
            .build()

        startForeground(1, notification)
    }

    private fun playPrompt() {
        if (isCancelled) return

        Log.d(TAG, "Playing TTS prompt...")

        initializeWhisperIfNeeded()

        if (ttsReady && tts != null) {
            tts?.speak("Πείτε όνομα", TextToSpeech.QUEUE_FLUSH, null, "prompt")
        } else {
            // TTS not ready, wait a bit and try again or skip
            Log.w(TAG, "TTS not ready, waiting 200ms...")
            handler.postDelayed({
                if (!isCancelled) {
                    if (ttsReady && tts != null) {
                        tts?.speak("Πείτε όνομα", TextToSpeech.QUEUE_FLUSH, null, "prompt")
                    } else {
                        Log.w(TAG, "TTS still not ready, skipping prompt")
                        vibrateAndStartRecording()
                    }
                }
            }, 100)
        }
    }

    /**
     * Play beep, vibrate, then start recording
     */
    private fun vibrateAndStartRecording() {
        if (isCancelled) return

        Log.d(TAG, "Beep + vibrate before recording...")

        // Play beep
        try {
            val toneGen = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
            toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
            handler.postDelayed({
                toneGen.release()
            }, 200)
        } catch (e: Exception) {
            Log.e(TAG, "Beep error", e)
        }

        // Vibrate
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

        // Start recording after beep finishes
        handler.postDelayed({
            if (!isCancelled) {
                prepareRecorder()
            }
        }, 200)
    }

    /**
     * Initialize Whisper only if not already loaded.
     * Uses persistent sharedWhisperEngine to avoid reinitializing on every recording.
     */
    private fun initializeWhisperIfNeeded() {
        synchronized(whisperLock) {
            if (sharedWhisperEngine != null) {
                Log.d(TAG, "Reusing existing Whisper engine (saves ~740ms)")
                return
            }

            Log.d(TAG, "First-time Whisper initialization...")
            try {
                Log.d(TAG, "Copying model from assets...")

                val modelPath = File(filesDir, "whisper-base.TOP_WORLD.tflite").absolutePath
                assets.open("whisper-base.TOP_WORLD.tflite").use { input ->
                    FileOutputStream(modelPath).use { output ->
                        input.copyTo(output)
                    }
                }

                Log.d(TAG, "Copying filters_vocab from assets...")
                val filtersVocabPath = File(filesDir, "filters_vocab_multilingual.bin").absolutePath
                assets.open("filters_vocab_multilingual.bin").use { input ->
                    FileOutputStream(filtersVocabPath).use { output ->
                        input.copyTo(output)
                    }
                }

                val engine = WhisperEngineJava(this)
                engine.initialize(modelPath, filtersVocabPath, true)

                sharedWhisperEngine = engine
                Log.d(TAG, "Whisper initialized and cached")
            } catch (e: Exception) {
                Log.e(TAG, "Whisper init error", e)
            }
        }
    }

    private fun prepareRecorder() {
        if (isRecording || isCancelled) return

        // Check permission
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.RECORD_AUDIO
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "RECORD_AUDIO permission not granted")
            safeToast("Microphone permission required")
            return
        }

        try {
            Log.d(TAG, "Preparing AudioRecord...")
            isRecording = true

            val bufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT
            )

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord initialization failed")
                safeToast("Recording init failed")
                stopSelf()
                return
            }

            audioRecord?.startRecording()
            Log.d(TAG, "Recording started!")

            recordingThread = Thread {
                recordAudio(bufferSize)
            }
            recordingThread?.start()

            // Schedule timeout - use the field runnable so we can cancel it
            handler.postDelayed(timeoutRunnable, RECORDING_DURATION_MS)

        } catch (e: Exception) {
            Log.e(TAG, "Recording error", e)
            isRecording = false
            safeToast("Recording error: ${e.message}")
            stopSelf()
        }
    }

    private fun recordAudio(bufferSize: Int) {
        val frameSize = VadAudioPipeline.FRAME_SIZE_SAMPLES
        val frameBuffer = ShortArray(frameSize)
        var totalSamplesRead = 0
        val maxSamples = (RECORDING_DURATION_MS * SAMPLE_RATE / 1000).toInt() // 64000 samples for 4 sec

        Log.d(TAG, "Recording up to $maxSamples samples (${RECORDING_DURATION_MS}ms)")

        // Reset VAD pipeline for new recording
        vadPipeline?.reset()

        var speechEndDetected = false

        try {
            while (isRecording && totalSamplesRead < maxSamples && !speechEndDetected && !isCancelled) {
                // Read exactly one frame worth of samples
                val shortsRead = audioRecord?.read(frameBuffer, 0, frameSize) ?: 0

                if (shortsRead == frameSize) {
                    totalSamplesRead += shortsRead

                    // Feed frame to VAD
                    val result = vadPipeline?.processFrame(frameBuffer)

                    if (result == VadAudioPipeline.ProcessResult.SPEECH_END) {
                        val durationMs = (totalSamplesRead * 1000) / SAMPLE_RATE
                        Log.d(TAG, "VAD detected end of speech at ${durationMs}ms")
                        speechEndDetected = true
                    }
                } else if (shortsRead < 0) {
                    Log.e(TAG, "AudioRecord read error: $shortsRead")
                    break
                }
            }

            val finalDurationMs = (totalSamplesRead * 1000) / SAMPLE_RATE
            Log.d(TAG, "Recording loop finished.")
            Log.d(TAG, "  Total samples: $totalSamplesRead (${finalDurationMs}ms)")
            Log.d(TAG, "  Speech detected: ${vadPipeline?.hasSpeechBeenDetected()}")
            Log.d(TAG, "  Speech end triggered early: $speechEndDetected")

            // Signal that recording is done
            handler.post {
                if (isRecording && !isCancelled) {
                    stopRecordingImmediately()
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error during recording", e)
            handler.post {
                if (isRecording && !isCancelled) {
                    stopRecordingImmediately()
                }
            }
        }
    }

    /**
     * Stop recording immediately and go straight to transcription.
     */
    private fun stopRecordingImmediately() {
        if (!isRecording || isCancelled) {
            Log.d(TAG, "stopRecordingImmediately called but already stopped or cancelled, ignoring")
            return
        }

        try {
            Log.d(TAG, "Stopping recorder...")
            isRecording = false

            // Cancel ONLY the 4-second timeout
            handler.removeCallbacks(timeoutRunnable)

            // Wait for recording thread to finish cleanly
            recordingThread?.join(1000)

            if (recordingThread?.isAlive == true) {
                Log.w(TAG, "Recording thread still alive after 1 second timeout")
            }

            try {
                audioRecord?.stop()
                Log.d(TAG, "AudioRecord stopped")
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping AudioRecord", e)
            }

            try {
                audioRecord?.release()
                Log.d(TAG, "AudioRecord released")
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing AudioRecord", e)
            }

            audioRecord = null

            Log.d(TAG, "Going directly to transcription")
            if (!isProcessing && !isCancelled) {
                transcribeAudio()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Stop recording error", e)
            if (!isProcessing && !isCancelled) {
                handler.postDelayed({ transcribeAudio() }, 100)
            }
        }
    }

    private fun transcribeAudio() {
        if (isCancelled) return

        Thread {
            try {
                isProcessing = true

                Log.d(TAG, "=== Starting transcription ===")

                // Check if we have speech to transcribe
                if (vadPipeline?.hasSpeechBeenDetected() != true) {
                    Log.w(TAG, "No speech detected in recording")
                    val logEntry = TranscriptionLog(
                        originalTranscript = "(no speech detected)",
                        fuzzifiedTranscript = "(no speech detected)",
                        transcriptionTimeMs = 0,
                        matchedContact = null,
                        confidence = null,
                        confidenceBreakdown = null
                    )
                    addLogEntry(logEntry)

                    handler.post {
                        safeToast("No speech detected")
                        hideCancelOverlay()
                    }
                    return@Thread
                }

                // Get accumulated audio as float array
                val audioSamples = vadPipeline?.getAccumulatedAudioFloat() ?: FloatArray(0)
                val audioDurationMs = (audioSamples.size * 1000) / SAMPLE_RATE
                Log.d(TAG, "Audio samples: ${audioSamples.size} (${audioDurationMs}ms)")

                // Optional: Log audio statistics for debugging
                if (audioSamples.isNotEmpty()) {
                    val maxAmp = audioSamples.maxOrNull() ?: 0f
                    val minAmp = audioSamples.minOrNull() ?: 0f
                    Log.d(TAG, "Audio amplitude range: [$minAmp, $maxAmp]")
                }

                val startTime = System.currentTimeMillis()
                val transcription = sharedWhisperEngine?.transcribeBuffer(audioSamples) ?: ""
                val transcriptionTime = System.currentTimeMillis() - startTime

                Log.d(TAG, "Transcription took ${transcriptionTime}ms")
                Log.d(TAG, "Transcription result: '$transcription'")

                if (transcription.isNotEmpty()) {
                    handleTranscriptionComplete(transcription, transcriptionTime)
                } else {
                    val logEntry = TranscriptionLog(
                        originalTranscript = "(empty)",
                        fuzzifiedTranscript = "(empty)",
                        transcriptionTimeMs = transcriptionTime,
                        matchedContact = null,
                        confidence = null,
                        confidenceBreakdown = null
                    )
                    addLogEntry(logEntry)

                    handler.post {
                        safeToast("Transcription was empty!")
                        hideCancelOverlay()
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Transcription error", e)
                handler.post {
                    safeToast("Error: ${e.message}")
                    hideCancelOverlay()
                }
            } finally {
                isProcessing = false
                handler.post {
                    hideCancelOverlay()
                }
                Log.d(TAG, "Transcription complete, service staying alive for next recording")
            }
        }.start()
    }

    /**
     * Handle transcription with intent detection and routing
     */
    private fun handleTranscriptionComplete(transcriptionText: String, transcriptionTime: Long) {
        Log.i(TAG, "=== Processing Transcription ===")
        Log.i(TAG, "Transcription: '$transcriptionText'")

        // Clean and detect intent
        val cleaned = SuperFuzzyContactMatcher.cleanTranscription(transcriptionText)
        val (intent, stripped) = SuperFuzzyContactMatcher.detectAndStripIntent(cleaned)

        val fuzzifiedTranscript = cleaned

        when (intent) {
            SuperFuzzyContactMatcher.Intent.CALL -> {
                Log.i(TAG, "CALL intent detected, processing contact match...")
                handleCallIntent(transcriptionText, fuzzifiedTranscript, transcriptionTime)
            }

            SuperFuzzyContactMatcher.Intent.FLASHLIGHT -> {
                Log.i(TAG, "FLASHLIGHT intent detected")

                val logEntry = TranscriptionLog(
                    originalTranscript = transcriptionText,
                    fuzzifiedTranscript = fuzzifiedTranscript,
                    transcriptionTimeMs = transcriptionTime,
                    matchedContact = "FLASHLIGHT",
                    confidence = 1.0,
                    confidenceBreakdown = "Flashlight command recognized"
                )
                addLogEntry(logEntry)

                handler.post {
                    safeToast("Flashlight detected!")
                }

                // TODO: Implement flashlight toggle
            }

            SuperFuzzyContactMatcher.Intent.RADIO -> {
                Log.i(TAG, "RADIO intent detected")

                val logEntry = TranscriptionLog(
                    originalTranscript = transcriptionText,
                    fuzzifiedTranscript = fuzzifiedTranscript,
                    transcriptionTimeMs = transcriptionTime,
                    matchedContact = "RADIO",
                    confidence = 1.0,
                    confidenceBreakdown = "Radio command recognized"
                )
                addLogEntry(logEntry)

                handler.post {
                    safeToast("Radio detected!")
                }

                // TODO: Implement radio playback
            }

            null -> {
                Log.w(TAG, "No intent detected")

                val logEntry = TranscriptionLog(
                    originalTranscript = transcriptionText,
                    fuzzifiedTranscript = fuzzifiedTranscript,
                    transcriptionTimeMs = transcriptionTime,
                    matchedContact = null,
                    confidence = null,
                    confidenceBreakdown = null,
                    noIntentDetected = true
                )
                addLogEntry(logEntry)

                handler.post {
                    safeToast("No command detected")
                    tts?.speak("Δεν αναγνωρίστηκε εντολή", TextToSpeech.QUEUE_FLUSH, null, "no_intent")
                }
            }
        }
    }

    /**
     * Handle CALL intent - match contact and hand off to CallManagerService
     */
    private fun handleCallIntent(originalTranscript: String, fuzzifiedTranscript: String, transcriptionTime: Long) {
        val matchResult = SuperFuzzyContactMatcher.findBestMatch(
            transcription = originalTranscript,
            contacts = cachedContacts
        )

        if (matchResult != null) {
            Log.i(TAG, "✓ MATCHED: ${matchResult.contact.displayName}")
            Log.i(TAG, "  Confidence: ${String.format("%.2f", matchResult.confidence)}")
            Log.i(TAG, "  Phone: ${matchResult.contact.phoneNumber}")
            Log.d(TAG, "  Breakdown: ${matchResult.breakdown}")

            val logEntry = TranscriptionLog(
                originalTranscript = originalTranscript,
                fuzzifiedTranscript = fuzzifiedTranscript,
                transcriptionTimeMs = transcriptionTime,
                matchedContact = matchResult.contact.displayName,
                confidence = matchResult.confidence,
                confidenceBreakdown = matchResult.breakdown
            )
            addLogEntry(logEntry)

            handler.post {
                safeToast("Match: ${matchResult.contact.displayName} (${String.format("%.2f", matchResult.confidence)})")
            }

            // Hand off to CallManagerService for SINGLE_MATCH
            val callIntent = Intent(this, CallManagerService::class.java).apply {
                action = CallManagerService.ACTION_SINGLE_MATCH
                putExtra(CallManagerService.EXTRA_CONTACT_NAME, matchResult.contact.displayName)
                putExtra(CallManagerService.EXTRA_PHONE_NUMBER, matchResult.contact.phoneNumber)
                putExtra(CallManagerService.EXTRA_ROUTING, matchResult.contact.routing)
            }
            try {
                startForegroundService(callIntent)
            } catch (e: SecurityException) {
                Log.e(TAG, "Permission denied starting CallManagerService", e)
                handler.post { safeToast("Permission error starting call") }
            }

        } else {
            // Check if ambiguous or no match
            val ambiguousCandidates = SuperFuzzyContactMatcher.getLastAmbiguousCandidates()

            if (ambiguousCandidates != null && ambiguousCandidates.isNotEmpty()) {
                Log.w(TAG, "✗ AMBIGUOUS MATCH")

                val logEntry = TranscriptionLog(
                    originalTranscript = originalTranscript,
                    fuzzifiedTranscript = fuzzifiedTranscript,
                    transcriptionTimeMs = transcriptionTime,
                    matchedContact = null,
                    confidence = null,
                    confidenceBreakdown = null,
                    ambiguousCandidates = ambiguousCandidates.map {
                        Pair(it.contact.displayName, it.confidence)
                    }
                )
                addLogEntry(logEntry)

                handler.post {
                    safeToast("Ambiguous: ${ambiguousCandidates[0].contact.displayName} vs ${ambiguousCandidates[1].contact.displayName}")
                }

                // Hand off to CallManagerService for AMBIGUOUS_MATCH
                val callIntent = Intent(this, CallManagerService::class.java).apply {
                    action = CallManagerService.ACTION_AMBIGUOUS_MATCH
                    putStringArrayListExtra(
                        CallManagerService.EXTRA_NAMES,
                        ArrayList(ambiguousCandidates.map { it.contact.displayName })
                    )
                    putStringArrayListExtra(
                        CallManagerService.EXTRA_NUMBERS,
                        ArrayList(ambiguousCandidates.map { it.contact.phoneNumber })
                    )
                    putStringArrayListExtra(
                        CallManagerService.EXTRA_ROUTINGS,
                        ArrayList(ambiguousCandidates.map { it.contact.routing })
                    )
                }
                try {
                    startForegroundService(callIntent)
                } catch (e: SecurityException) {
                    Log.e(TAG, "Permission denied starting CallManagerService", e)
                    handler.post { safeToast("Permission error starting call") }
                }

            } else {
                Log.w(TAG, "✗ NO MATCH found")

                val logEntry = TranscriptionLog(
                    originalTranscript = originalTranscript,
                    fuzzifiedTranscript = fuzzifiedTranscript,
                    transcriptionTimeMs = transcriptionTime,
                    matchedContact = null,
                    confidence = null,
                    confidenceBreakdown = null
                )
                addLogEntry(logEntry)

                handler.post {
                    safeToast("No contact match found")
                }

                // Speak "not found" in Greek using existing TTS
                handler.post {
                    tts?.speak("Δεν βρέθηκε επαφή", TextToSpeech.QUEUE_FLUSH, null, "not_found")
                }
            }
        }

        SuperFuzzyContactMatcher.clearAmbiguousCandidates()
    }


    private fun addLogEntry(log: TranscriptionLog) {
        transcriptionLogs.add(log)

        if (transcriptionLogs.size > MAX_STORED_LOGS) {
            transcriptionLogs.removeAt(0)
        }

        Log.d(TAG, "Added log entry. Total logs: ${transcriptionLogs.size}")

        saveLogsToPrefs(this)

        handler.post {
            logUpdateCallback?.invoke()
        }
    }

    /**
     * Nuclear option: kill the entire process.
     */
    private fun nukeAppProcess() {
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()

            val relaunchIntent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            startActivity(relaunchIntent)

            android.os.Process.killProcess(android.os.Process.myPid())
            kotlin.system.exitProcess(0)

        } catch (e: Exception) {
            Log.e(TAG, "Error during nuke", e)
            android.os.Process.killProcess(android.os.Process.myPid())
        }
    }

    /**
     * Show red cancel button with rounded corners
     */
    private fun showCancelOverlay() {
        if (cancelOverlay != null) {
            Log.w(TAG, "Cancel overlay already showing")
            return
        }

        Log.d(TAG, "Showing cancel overlay")

        val displayMetrics = resources.displayMetrics
        val screenHeight = displayMetrics.heightPixels
        val screenWidth = displayMetrics.widthPixels
        val density = displayMetrics.density

        val marginPx = (24 * density).toInt()
        val cornerRadiusPx = (24 * density)  // Nice rounded corners

        val container = FrameLayout(this).apply {
            // Create rounded background
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

        container.addView(cancelText, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        cancelOverlay = container

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
            windowManager?.addView(cancelOverlay, params)
            Log.d(TAG, "Cancel overlay added successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add cancel overlay", e)
        }
    }

    /**
     * Hide cancel overlay
     */
    private fun hideCancelOverlay() {
        cancelOverlay?.let { view ->
            try {
                windowManager?.removeView(view)
                Log.d(TAG, "Cancel overlay removed")
            } catch (e: Exception) {
                Log.e(TAG, "Error removing cancel overlay", e)
            }
        }
        cancelOverlay = null
    }

    /**
     * Handle cancel button tap - buzz, beep, cleanup
     */
    private fun handleCancelTap() {
        if (isCancelled) {
            Log.d(TAG, "Already cancelled, ignoring duplicate tap")
            return
        }

        Log.i(TAG, "Cancel tapped - stopping recording")
        isCancelled = true

        // Short vibration
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

        // Short beep
        try {
            val toneGen = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
            toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
            handler.postDelayed({
                toneGen.release()
            }, 200)
        } catch (e: Exception) {
            Log.e(TAG, "Beep error", e)
        }

        // Stop everything
        stopEverything()

        // Cleanup and stop service
        handler.postDelayed({
            hideCancelOverlay()
            stopSelf()
        }, 300)  // Give beep/buzz time to complete
    }

    /**
     * Emergency stop for all recording/processing
     */
    private fun stopEverything() {
        Log.d(TAG, "Emergency stop - cancelling all operations")

        // Stop TTS
        tts?.stop()

        // Stop recording
        isRecording = false
        isProcessing = false

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping AudioRecord", e)
        }
        audioRecord = null

        // Cancel ALL pending callbacks
        handler.removeCallbacksAndMessages(null)

        // Reset VAD
        vadPipeline?.reset()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // NUKE handler - must be first
        if (intent?.action == ACTION_NUKE_APP) {
            Log.w(TAG, "☢️ NUKE_APP received - killing process")
            nukeAppProcess()
            return START_NOT_STICKY
        }

        if (intent?.action == "PRELOAD_WHISPER") {
            Log.d(TAG, "Preloading Whisper...")
            initializeWhisperIfNeeded()
            return START_STICKY
        }

        // Check if already busy
        if (isRecording || isProcessing) {
            Log.w(TAG, "Service already busy, ignoring duplicate start request")
            return START_STICKY
        }

        // Reset cancellation flag
        isCancelled = false

        // Show cancel button FIRST
        showCancelOverlay()

        // Then start recording session
        Log.d(TAG, "Starting new recording session")
        handler.postDelayed({
            if (!isCancelled) {
                playPrompt()
            }
        }, 100)

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()

        try {
            unregisterReceiver(refreshReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering receiver", e)
        }

        handler.removeCallbacksAndMessages(null)

        isRecording = false

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up AudioRecord", e)
        }

        try {
            tts?.stop()
            tts?.shutdown()
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up TTS", e)
        }

        try {
            vadPipeline?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up VAD pipeline", e)
        }

        // Hide cancel overlay
        hideCancelOverlay()

        audioRecord = null
        tts = null
        vadPipeline = null

        Log.d(TAG, "RecordingService destroyed (WhisperEngine kept alive)")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}