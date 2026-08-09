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
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.core.app.NotificationCompat
import com.greekvoiceassistant.whisper.engine.WhisperEngine
import com.greekvoiceassistant.whisper.engine.WhisperEngineJava
import org.json.JSONArray
import org.json.JSONObject
import android.media.AudioManager
import android.media.ToneGenerator

class RecordingService : Service() {

    private var audioRecord: AudioRecord? = null
    private val handler = Handler(Looper.getMainLooper())
    private var isRecording = false
    private var isProcessing = false
    private var sessionInProgress = false
    private var recordingThread: Thread? = null
    private var vadPipeline: VadAudioPipeline? = null

    // Service lifecycle flag
    private var isServiceAlive = true

    // Vibrator for haptic feedback
    private var vibrator: Vibrator? = null

    // Cancel flag
    private var isCancelled = false

    // Online (Google) recognition — optional, opt-in, one engine per session
    private var speechRecognizer: SpeechRecognizer? = null
    private var onlineStartMs = 0L
    private var onlineBusyRetried = false

    // Set when the Whisper model is absent entirely (not bundled, not downloaded),
    // so the failure can be reported as "download the model" rather than a generic error.
    private var modelMissing = false

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

        fun resetWhisperEngine() {
            synchronized(whisperLock) {
                Log.w(TAG, "Force-resetting Whisper engine")
                try {
                    sharedWhisperEngine?.deinitialize()
                } catch (e: Exception) {
                    Log.e(TAG, "Error deinitializing Whisper", e)
                }
                sharedWhisperEngine = null
            }
        }
    }

    private fun safeToast(msg: String) {
        try {
            android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.e("ToastFail", "System toast blocked. Falling back.", e)
            Handler(Looper.getMainLooper()).post {
                android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        isServiceAlive = true
        startSilentNotification()

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

        // Initialize TTS via TtsManager
        TtsManager.initialize(this)
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

        // Set up utterance listener for this session
        TtsManager.setUtteranceListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                if (!isServiceAlive) return
                Log.d(TAG, "TTS started: $utteranceId")
            }

            override fun onDone(utteranceId: String?) {
                if (!isServiceAlive) return
                Log.d(TAG, "TTS done: $utteranceId")
                if (utteranceId == "prompt") {
                    handler.post {
                        if (!isCancelled && isServiceAlive) {
                            chooseEngineAndStart()
                        }
                    }
                }
            }

            override fun onError(utteranceId: String?) {
                if (!isServiceAlive) return
                Log.e(TAG, "TTS error: $utteranceId")
                if (utteranceId == "prompt") {
                    handler.post {
                        if (!isCancelled && isServiceAlive) {
                            chooseEngineAndStart()
                        }
                    }
                }
            }
        })

        if (TtsManager.isReady) {
            TtsManager.speak("Πείτε όνομα", "prompt")
        } else {
            Log.w(TAG, "TTS not ready, skipping prompt")
            chooseEngineAndStart()
        }
    }

    /**
     * Play beep, vibrate, then start recording
     */
    private fun vibrateAndStartRecording() {
        if (isCancelled) return

        Log.d(TAG, "Beep + vibrate before recording...")

        // Play beep
        playBeep()

        // Vibrate
        vibrateShort()

        // Start recording after beep finishes
        handler.postDelayed({
            if (!isCancelled) {
                prepareRecorder()
            }
        }, 50)
    }

    // ============================================================================
    // Online (Google) recognition path — opt-in, network-backed, Whisper fallback.
    // Strategy switch at session start: pick ONE engine. Never overlap AudioRecord
    // and SpeechRecognizer (mic contention).
    // ============================================================================

    /**
     * Called once the TTS prompt has finished. Picks the engine for THIS session.
     */
    private fun chooseEngineAndStart() {
        onlineBusyRetried = false
        if (shouldUseOnline()) {
            startOnlineRecognition()
        } else {
            vibrateAndStartRecording()
        }
    }

    private fun shouldUseOnline(): Boolean {
        val enabled = getSharedPreferences("ava_settings", MODE_PRIVATE)
            .getBoolean("online_recognition_enabled", false)
        return enabled && isNetworkAvailable() && SpeechRecognizer.isRecognitionAvailable(this)
    }

    private fun isNetworkAvailable(): Boolean {
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } catch (e: Exception) {
            Log.e(TAG, "Network check failed", e)
            false
        }
    }

    /**
     * Start Google's SpeechRecognizer. MUST run on the main thread (it is — called
     * from the TTS onDone handler.post / handler callbacks). The recognizer takes
     * over the mic and does its own capture + endpointing; it plays its own
     * start/stop tones, so we drop the manual beep here (haptic cue only).
     */
    private fun startOnlineRecognition() {
        if (isCancelled || !isServiceAlive) return
        Log.d(TAG, "Starting ONLINE recognition (Google SpeechRecognizer, el-GR)")

        vibrateShort()

        try {
            destroyRecognizer()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(onlineListener)
            }
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "el-GR")
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            onlineStartMs = System.currentTimeMillis()
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start online recognition, falling back to Whisper", e)
            recueAndFallbackToWhisper()
        }
    }

    private val onlineListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onResults(results: Bundle?) {
            if (isCancelled || !isServiceAlive) return
            val elapsed = System.currentTimeMillis() - onlineStartMs
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.trim()
            destroyRecognizer()
            Log.d(TAG, "Online result: '$text' (${elapsed}ms)")

            if (!text.isNullOrEmpty()) {
                // Converge on the exact same downstream path as Whisper.
                handleTranscriptionComplete(text, elapsed)
                sessionInProgress = false
            } else {
                logOnlineTerminal("(empty)", elapsed)
                handler.post {
                    safeToast("Transcription was empty!")
                    CallOverlayController.dismiss()
                }
            }
        }

        override fun onError(error: Int) {
            if (isCancelled || !isServiceAlive) return
            Log.w(TAG, "Online recognition error: $error")
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    // No speech — treat like the Whisper "(no speech detected)" path.
                    destroyRecognizer()
                    logOnlineTerminal("(no speech detected)", 0)
                    handler.post {
                        safeToast("No speech detected")
                        CallOverlayController.dismiss()
                    }
                }
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                    // Recreate once; if it recurs, fall back to Whisper.
                    if (!onlineBusyRetried) {
                        onlineBusyRetried = true
                        destroyRecognizer()
                        handler.postDelayed({
                            if (!isCancelled && isServiceAlive) startOnlineRecognition()
                        }, 150)
                    } else {
                        recueAndFallbackToWhisper()
                    }
                }
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    // RECORD_AUDIO missing — same handling as prepareRecorder().
                    destroyRecognizer()
                    sessionInProgress = false
                    handler.post {
                        safeToast("Microphone permission required")
                        CallOverlayController.dismiss()
                    }
                }
                else -> {
                    // NETWORK / NETWORK_TIMEOUT / SERVER / SERVER_DISCONNECTED /
                    // CLIENT / AUDIO / LANGUAGE_UNAVAILABLE / LANGUAGE_NOT_SUPPORTED
                    // → fall back to Whisper for this session (re-record).
                    recueAndFallbackToWhisper()
                }
            }
        }
    }

    /**
     * Mid-session fallback: the recognizer gives us no raw audio, so the user must
     * speak again. vibrateAndStartRecording() re-cues (beep + haptic) and runs the
     * unchanged Whisper capture path.
     */
    private fun recueAndFallbackToWhisper() {
        destroyRecognizer()
        if (isCancelled || !isServiceAlive) return
        Log.i(TAG, "Falling back to Whisper capture for this session")
        handler.post {
            if (!isCancelled && isServiceAlive) {
                vibrateAndStartRecording()
            }
        }
    }

    private fun logOnlineTerminal(label: String, elapsed: Long) {
        addLogEntry(
            TranscriptionLog(
                originalTranscript = label,
                fuzzifiedTranscript = label,
                transcriptionTimeMs = elapsed,
                matchedContact = null,
                confidence = null,
                confidenceBreakdown = null
            )
        )
        sessionInProgress = false
    }

    /** Destroy the SpeechRecognizer on the main thread and null it out. */
    private fun destroyRecognizer() {
        try {
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        } catch (e: Exception) {
            Log.e(TAG, "Error destroying SpeechRecognizer", e)
        }
        speechRecognizer = null
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
                val modelPath = ModelManager.getModelPath(this)
                if (modelPath == null) {
                    // Builds that don't bundle the model (CI / Play Store) until the
                    // caregiver downloads it once from the settings screen. Say so out
                    // loud — this used to fail silently and the user just got nothing.
                    Log.e(TAG, "No Whisper model available — cannot transcribe offline")
                    modelMissing = true
                    return
                }
                modelMissing = false
                val vocabPath = ModelManager.getVocabPath(this)

                Log.d(TAG, "Using model: $modelPath")

                val engine = WhisperEngineJava(this)
                engine.initialize(modelPath, vocabPath, true)

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
                        CallOverlayController.dismiss()
                    }
                    return@Thread
                }

                // Get accumulated audio as float array
                val audioSamples = vadPipeline?.getAccumulatedAudioFloat() ?: FloatArray(0)
                val audioDurationMs = (audioSamples.size * 1000) / SAMPLE_RATE
                Log.d(TAG, "Audio samples: ${audioSamples.size} (${audioDurationMs}ms)")

                // Guard against too-short audio crashing Whisper
                if (audioSamples.size < 3200) {  // 200ms minimum @ 16kHz
                    Log.w(TAG, "Audio too short for Whisper: ${audioSamples.size} samples (${audioDurationMs}ms)")
                    val logEntry = TranscriptionLog(
                        originalTranscript = "(audio too short)",
                        fuzzifiedTranscript = "(audio too short)",
                        transcriptionTimeMs = 0,
                        matchedContact = null,
                        confidence = null,
                        confidenceBreakdown = null
                    )
                    addLogEntry(logEntry)

                    handler.post {
                        TtsManager.speak("Δεν άκουσα τίποτα", "too_short")
                        CallOverlayController.dismiss()
                    }
                    return@Thread
                }

                // Optional: Log audio statistics for debugging
                if (audioSamples.isNotEmpty()) {
                    val maxAmp = audioSamples.maxOrNull() ?: 0f
                    val minAmp = audioSamples.minOrNull() ?: 0f
                    Log.d(TAG, "Audio amplitude range: [$minAmp, $maxAmp]")
                }

                // Without an engine every path below yields "" and the session ends
                // with no feedback at all — indistinguishable from "I wasn't heard".
                // Builds that don't bundle the model land here until it's downloaded.
                if (sharedWhisperEngine == null) {
                    Log.e(TAG, "No Whisper engine available, aborting transcription")
                    addLogEntry(
                        TranscriptionLog(
                            originalTranscript = if (modelMissing) "(model missing)" else "(engine unavailable)",
                            fuzzifiedTranscript = "",
                            transcriptionTimeMs = 0,
                            matchedContact = null,
                            confidence = null,
                            confidenceBreakdown = null
                        )
                    )
                    handler.post {
                        TtsManager.speak(
                            if (modelMissing) "Λείπει το μοντέλο ομιλίας. Κάντε λήψη από τις ρυθμίσεις."
                            else "Σφάλμα αναγνώρισης ομιλίας",
                            "model_missing"
                        )
                        CallOverlayController.dismiss()
                    }
                    return@Thread
                }

                val startTime = System.currentTimeMillis()
                val transcription = try {
                    sharedWhisperEngine?.transcribeBuffer(audioSamples) ?: ""
                } catch (e: Exception) {
                    Log.e(TAG, "Whisper crashed, resetting engine", e)
                    resetWhisperEngine()
                    initializeWhisperIfNeeded()

                    // Retry once
                    try {
                        sharedWhisperEngine?.transcribeBuffer(audioSamples) ?: ""
                    } catch (e2: Exception) {
                        Log.e(TAG, "Whisper failed after reset", e2)
                        ""
                    }
                }
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
                        CallOverlayController.dismiss()
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Transcription error", e)
                handler.post {
                    safeToast("Error: ${e.message}")
                    CallOverlayController.dismiss()
                }
            } finally {
                isProcessing = false
                sessionInProgress = false
                // Don't dismiss overlay here - CallManagerService owns it now if we handed off
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
                    CallOverlayController.dismiss()
                    val magnifierIntent = Intent(this@RecordingService, MagnifierActivity::class.java)
                    magnifierIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(magnifierIntent)
                }
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
                    CallOverlayController.dismiss()
                    RadioActivity.launch(this@RecordingService)
                }
            }
            SuperFuzzyContactMatcher.Intent.MISSED_CALLS -> {
                Log.i(TAG, "MISSED_CALLS intent detected")

                val logEntry = TranscriptionLog(
                    originalTranscript = transcriptionText,
                    fuzzifiedTranscript = fuzzifiedTranscript,
                    transcriptionTimeMs = transcriptionTime,
                    matchedContact = "MISSED_CALLS",
                    confidence = 1.0,
                    confidenceBreakdown = "Missed calls command recognized"
                )
                addLogEntry(logEntry)

                handler.post {
                    CallOverlayController.dismiss()
                    // Reads the call log directly — no dialer, no accessibility service,
                    // so this no longer depends on per-device calibration.
                    MissedCallsReader.announce(this@RecordingService)
                }
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
                    vibrateShort()
                    TtsManager.speak("Δεν σας κατάλαβα", "no_intent")
                    // Autocancel after TTS completes (~1.5s for this phrase)
                    handler.postDelayed({
                        CallOverlayController.dismiss()
                        sessionInProgress = false
                    }, 1500)
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
                    vibrateShort()
                    TtsManager.speak("Δεν βρέθηκε επαφή", "not_found")
                    // Autocancel after TTS completes (~1.5s for this phrase)
                    handler.postDelayed({
                        CallOverlayController.dismiss()
                        sessionInProgress = false
                    }, 1500)
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
            // Clear contact cache so restart gets fresh contacts from device
            ContactRepository.clearCache(this)
            Log.d(TAG, "Contact cache cleared before nuke")

            // Reset both engines
            resetWhisperEngine()
            TtsManager.reset()

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
     * Handle cancel from overlay - vibrate, beep, stop everything
     */
    private fun handleCancelFromOverlay() {
        if (isCancelled) {
            Log.d(TAG, "Already cancelled, ignoring duplicate tap")
            return
        }

        Log.i(TAG, "Cancel tapped - stopping recording")
        isCancelled = true

        // Short vibration
        vibrateShort()

        // Short beep
        playBeep()

        // Stop everything
        stopEverything()

        // Cleanup but keep service alive
        handler.postDelayed({
            CallOverlayController.dismiss()
            sessionInProgress = false
            Log.d(TAG, "Session cancelled, service ready for next trigger")
        }, 300)
    }

    /**
     * Emergency stop for all recording/processing
     */
    private fun stopEverything() {
        Log.d(TAG, "Emergency stop - cancelling all operations")

        // Stop TTS via manager
        TtsManager.stop()

        // Stop online recognizer if active (main thread — we're already on it)
        destroyRecognizer()

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

    /**
     * Short vibration feedback
     */
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

    /**
     * Short beep feedback
     */
    private fun playBeep() {
        try {
            val toneGen = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
            toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, 100)
            handler.postDelayed({
                toneGen.release()
            }, 20)
        } catch (e: Exception) {
            Log.e(TAG, "Beep error", e)
        }
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

        if (intent?.action == "RELOAD_WHISPER") {
            Log.d(TAG, "Reloading Whisper with new model...")
            resetWhisperEngine()
            initializeWhisperIfNeeded()
            return START_STICKY
        }

        // Check if session already in progress
        if (sessionInProgress) {
            Log.w(TAG, "Session already in progress, ignoring duplicate start")
            return START_STICKY
        }

        // Reset flags for new session
        isCancelled = false
        sessionInProgress = true

        // Buzz on widget activation
        vibrateShort()

        // Show cancel overlay via controller
        CallOverlayController.showRecording {
            handleCancelFromOverlay()
        }

        // Then start recording session
        Log.d(TAG, "Starting new recording session")
        handler.postDelayed({
            if (!isCancelled) {
                playPrompt()
            }
        }, 10)

        return START_STICKY
    }

    override fun onDestroy() {
        isServiceAlive = false
        super.onDestroy()

        try {
            unregisterReceiver(refreshReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering receiver", e)
        }

        handler.removeCallbacksAndMessages(null)

        isRecording = false
        sessionInProgress = false

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up AudioRecord", e)
        }

        // Stop TTS but don't shutdown - keep it alive
        TtsManager.stop()

        // Destroy online recognizer if any
        destroyRecognizer()

        try {
            vadPipeline?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up VAD pipeline", e)
        }

        // Dismiss overlay via controller
        CallOverlayController.dismiss()

        audioRecord = null
        vadPipeline = null

        Log.d(TAG, "RecordingService destroyed (WhisperEngine and TTS kept alive)")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
