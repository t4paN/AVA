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

    /** Speaking budget in force for this session, counted from the first syllable. */
    private var activeMaxListenMs = MAX_LISTEN_DEFAULT_MS

    /** How long this session will wait for speech to begin before giving up. */
    private var activeLeadInMs = LEAD_IN_DEFAULT_MS

    /**
     * Last resort for the online path: the recognizer neither returned a result nor
     * reported an error. Tear it down and end the session — otherwise sessionInProgress
     * stays true and every later widget press is dropped as a duplicate start.
     */
    private val onlineWatchdogRunnable = Runnable {
        Log.e(TAG, "Online recognizer never called back — aborting session")
        destroyRecognizer()
        logOnlineTerminal("(recognizer timed out)", activeLeadInMs + activeMaxListenMs + ONLINE_RESULT_GRACE_MS)
        if (TtsManager.isReady) TtsManager.speak("Δεν άκουσα τίποτα", "online_timeout")
        safeToast("Recognition timed out")
        CallOverlayController.dismiss()
    }

    /**
     * Hard cap on online listening. Calls stopListening(), which asks the recognizer to
     * finalise what it has already heard rather than discarding it — so a user who keeps
     * talking still gets the first ONLINE_MAX_LISTEN_MS transcribed instead of nothing.
     */
    private val onlineCapRunnable = Runnable {
        Log.w(TAG, "Online listening hit the ${activeMaxListenMs}ms cap — forcing endpoint")
        try {
            speechRecognizer?.stopListening()
        } catch (e: Exception) {
            Log.e(TAG, "stopListening() failed", e)
        }
        // stopListening() is a request, not a guarantee — arm the watchdog behind it.
        handler.postDelayed(onlineWatchdogRunnable, ONLINE_RESULT_GRACE_MS)
    }

    // Set when the Whisper model is absent entirely (not bundled, not downloaded),
    // so the failure can be reported as "download the model" rather than a generic error.
    private var modelMissing = false

    // Timeout runnable as a field so we can cancel it specifically
    private val timeoutRunnable = Runnable {
        Log.d(TAG, "Absolute session bound reached (${activeLeadInMs + activeMaxListenMs}ms)")
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

        // ---- Listening budget, shared by both recognition paths ----------------
        //
        // Knob 1: the hard ceiling on a capture. Google's recognizer keeps listening
        // for as long as it hears *anything* — continuous speech or steady room noise
        // both keep it alive indefinitely (observed on device 2026-08-20: a 29 s
        // session while the user kept talking), so online needs an explicit bound.
        // Whisper was bounded too, but by a *different* number: a hardcoded 4000 ms,
        // one second tighter than the online default. Same person, same command, less
        // budget whenever the network dropped. One value now governs both.
        //
        // Caregiver-tunable. Speech tempo is personal, not a tuning constant: a brisk
        // speaker is happy at 3 s, someone who needs a run-up before the name wants 6+.
        // Clamped on read — a caregiver must not be able to set a value that makes the
        // assistant unusable for someone who cannot report what changed.
        private const val PREF_MAX_LISTEN_MS = "max_listen_ms"
        private const val MAX_LISTEN_DEFAULT_MS = 5000L
        private const val MAX_LISTEN_MIN_MS = 2000L
        private const val MAX_LISTEN_MAX_MS = 15000L

        // Knob 2: how long a pause may last before the capture is considered finished.
        // Offline this is Silero's silence timeout and it is usually the number that
        // actually bites — a pause in the middle of a name ends the recording however
        // large knob 1 is. Online it is only a hint; Google frequently ignores it, which
        // is exactly why the ceiling above exists.
        //
        // The floor is deliberately lower than a caregiver would ever want (200 ms):
        // this doubles as the instrument for testing early VAD cutoffs, and a floor set
        // for safety would make the experiment impossible.
        // Knob 1 is a *speaking* budget, and it does not start until speech does.
        // This is how long AVA waits for that to happen. Deciding who to call and
        // composing the sentence happens before the first syllable — reported on
        // device by an elderly user and, independently, by a child asked to say
        // "κλήση Τυρανοσαυρος". Under a listen-anchored cap that thinking time was
        // spent from the same budget as the speech, so the slowest starters got the
        // least room to talk: exactly backwards.
        //
        // Deliberately *not* generous, and deliberately not on the gear screen — it is
        // patience, not tempo. The absolute bound on a session is lead-in + speaking
        // budget.
        //
        // 6 s, not the 10 s this first shipped with, and the reason is consistency
        // rather than capability. Google enforces its own pre-speech timeout that no
        // extra reliably extends — measured on device 2026-08-21, it gave up at about
        // 4.6 s and returned the fragment 'κλή'. Whisper has no such ceiling, so it
        // *could* wait far longer. It should not: an engine that waits forever on one
        // path and cuts you off on the other teaches no usable rhythm, and the user
        // cannot see which engine is running. `shouldUseOnline()` silently falls back
        // to Whisper when the network drops, so the same person meets both.
        //
        // 6 s covers a slow decider counting to five — the case this exists for — while
        // staying close enough to Google's real behaviour that one habit works on both.
        // This is the number to revisit if people keep being clipped.
        private const val PREF_LEAD_IN_MS = "lead_in_ms"
        private const val LEAD_IN_DEFAULT_MS = 6000L
        private const val LEAD_IN_MIN_MS = 2000L
        private const val LEAD_IN_MAX_MS = 30000L

        // The pause between the spoken prompt and the go-tone. Thinking time spent
        // here costs nothing, because no recogniser is running yet — which is the only
        // way to buy a slow decider more time on the Google path, whose own pre-speech
        // timeout cannot be extended. Widening this beats widening the recorder.
        //
        // Default 0 keeps today's behaviour and today's prompt. Above zero the prompt
        // changes to name the tone, because "say a name" followed by silence invites
        // the user to speak into a mic that is not listening yet.
        private const val PREF_THINK_GAP_MS = "think_gap_ms"
        private const val THINK_GAP_DEFAULT_MS = 0L
        private const val THINK_GAP_MIN_MS = 0L
        private const val THINK_GAP_MAX_MS = 10000L

        private const val PREF_ENDPOINT_SILENCE_MS = "endpoint_silence_ms"
        private const val ENDPOINT_SILENCE_DEFAULT_MS = 700L
        private const val ENDPOINT_SILENCE_MIN_MS = 200L
        private const val ENDPOINT_SILENCE_MAX_MS = 3000L
        private const val ONLINE_RESULT_GRACE_MS = 5000L  // after stopListening(), wait this long for a result
        private const val ONLINE_MIN_INPUT_MS = 1500L

        // Spoken whenever the thing the user asked for needs the internet and there
        // isn't any. AVA is offline-first for calls (Whisper), but radio is a live
        // stream and the model download is a download — both are dead without a link.
        private const val MSG_NO_NETWORK = "Δεν υπάρχει σύνδεση στο διαδίκτυο"
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

        // Initialize VAD pipeline at the stored pause tolerance. startRecording()
        // re-checks and rebuilds if the caregiver has changed it since.
        vadPipeline = VadAudioPipeline(this, resolveEndpointSilenceMs().toInt())

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
                    handler.postDelayed({
                        if (!isCancelled && isServiceAlive) {
                            cueAndStart()
                        }
                    }, resolveThinkGapMs())
                }
            }

            override fun onError(utteranceId: String?) {
                if (!isServiceAlive) return
                Log.e(TAG, "TTS error: $utteranceId")
                if (utteranceId == "prompt") {
                    handler.postDelayed({
                        if (!isCancelled && isServiceAlive) {
                            cueAndStart()
                        }
                    }, resolveThinkGapMs())
                }
            }
        })

        if (TtsManager.isReady) {
            // With a gap the tone is the instruction, so the prompt has to say so —
            // otherwise the user speaks into the silence, before anything is listening.
            val gap = resolveThinkGapMs()
            TtsManager.speak(
                if (gap > 0) "Μετά τον ήχο, πείτε όνομα" else "Πείτε όνομα",
                "prompt"
            )
        } else {
            Log.w(TAG, "TTS not ready, skipping prompt")
            cueAndStart()
        }
    }

    /**
     * The go-tone, then whichever engine this session picked.
     *
     * The beep used to fire only on the Whisper path — the online path merely
     * vibrated. That left the *audible* cue depending on which engine happened to run,
     * on a phone whose user cannot see the screen to tell, and a buzz is easy to miss
     * on a table. Both paths sound the same tone now; it is the one moment the user
     * has to recognise, and it must not move.
     */
    private fun cueAndStart() {
        if (isCancelled) return

        Log.d(TAG, "Go cue (beep + vibrate)")
        playBeep()
        vibrateShort()

        // Let the beep clear the speaker before the mic opens.
        handler.postDelayed({
            if (!isCancelled) {
                chooseEngineAndStart()
            }
        }, 50)
    }

    /** Re-cue and record with Whisper, used when the online path bails mid-session. */
    private fun vibrateAndStartRecording() {
        if (isCancelled) return

        Log.d(TAG, "Beep + vibrate before recording...")
        playBeep()
        vibrateShort()

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
            // Cue already played by cueAndStart(); go straight to the mic.
            prepareRecorder()
        }
    }

    private fun shouldUseOnline(): Boolean {
        val enabled = getSharedPreferences("ava_settings", MODE_PRIVATE)
            // Default ON as of 2026-08-21 (t4paN). Whisper remains the automatic
            // fallback whenever there is no network, so this is "which engine leads",
            // not "whether Whisper is present". ⚠️ ava/privacy.html and the Play
            // data-safety answers describe the default and must match this value.
            .getBoolean("online_recognition_enabled", true)
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
                // Ask the recognizer to endpoint sooner. These are hints and several
                // implementations ignore them, so onlineCapRunnable is the real bound.
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, resolveEndpointSilenceMs())
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, resolveEndpointSilenceMs())
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, ONLINE_MIN_INPUT_MS)
            }
            onlineStartMs = System.currentTimeMillis()
            speechRecognizer?.startListening(intent)
            activeMaxListenMs = resolveMaxListenMs()
            activeLeadInMs = resolveLeadInMs()
            // Armed at the lead-in first. onBeginningOfSpeech() re-arms it at the
            // speaking budget, so a long think before the name costs nothing.
            handler.postDelayed(onlineCapRunnable, activeLeadInMs)
            Log.d(TAG, "Online: waiting up to ${activeLeadInMs}ms for speech to start, " +
                    "then ${activeMaxListenMs}ms to say it")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start online recognition, falling back to Whisper", e)
            recueAndFallbackToWhisper()
        }
    }

    private val onlineListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        /**
         * The whole point of the lead-in. Until this fires the session is on patience
         * time; from here it is on speaking time, so the budget restarts now.
         *
         * ⚠️ Google runs its *own* pre-speech timeout underneath this and there is no
         * public extra that reliably extends it — a very slow starter can still be cut
         * off by the recogniser itself with ERROR_SPEECH_TIMEOUT before we ever get
         * here. Offline has no such ceiling, which is a real point in Whisper's favour
         * for exactly the users this app is for.
         */
        override fun onBeginningOfSpeech() {
            handler.removeCallbacks(onlineCapRunnable)
            handler.postDelayed(onlineCapRunnable, activeMaxListenMs)
            Log.d(TAG, "Speech began after ${System.currentTimeMillis() - onlineStartMs}ms — " +
                    "budget restarts, ${activeMaxListenMs}ms to talk")
        }
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onResults(results: Bundle?) {
            cancelOnlineTimers()
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
            cancelOnlineTimers()
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

    /**
     * Listening cap in ms, read fresh each session so a change takes effect without a
     * restart, and clamped to a range that always leaves AVA usable.
     */
    private fun resolveMaxListenMs(): Long {
        val stored = getSharedPreferences("ava_settings", MODE_PRIVATE)
            .getLong(PREF_MAX_LISTEN_MS, MAX_LISTEN_DEFAULT_MS)
        val clamped = stored.coerceIn(MAX_LISTEN_MIN_MS, MAX_LISTEN_MAX_MS)
        if (clamped != stored) Log.w(TAG, "Listen cap ${stored}ms out of range, using ${clamped}ms")
        return clamped
    }

    /**
     * Think gap in ms — how long after the prompt before the go-tone sounds.
     */
    private fun resolveThinkGapMs(): Long {
        val stored = getSharedPreferences("ava_settings", MODE_PRIVATE)
            .getLong(PREF_THINK_GAP_MS, THINK_GAP_DEFAULT_MS)
        val clamped = stored.coerceIn(THINK_GAP_MIN_MS, THINK_GAP_MAX_MS)
        if (clamped != stored) Log.w(TAG, "Think gap ${stored}ms out of range, using ${clamped}ms")
        return clamped
    }

    /**
     * Lead-in patience in ms, same read-fresh-and-clamp contract as the rest.
     */
    private fun resolveLeadInMs(): Long {
        val stored = getSharedPreferences("ava_settings", MODE_PRIVATE)
            .getLong(PREF_LEAD_IN_MS, LEAD_IN_DEFAULT_MS)
        val clamped = stored.coerceIn(LEAD_IN_MIN_MS, LEAD_IN_MAX_MS)
        if (clamped != stored) Log.w(TAG, "Lead-in ${stored}ms out of range, using ${clamped}ms")
        return clamped
    }

    /**
     * Pause tolerance in ms, same read-fresh-and-clamp contract as the ceiling.
     */
    private fun resolveEndpointSilenceMs(): Long {
        val stored = getSharedPreferences("ava_settings", MODE_PRIVATE)
            .getLong(PREF_ENDPOINT_SILENCE_MS, ENDPOINT_SILENCE_DEFAULT_MS)
        val clamped = stored.coerceIn(ENDPOINT_SILENCE_MIN_MS, ENDPOINT_SILENCE_MAX_MS)
        if (clamped != stored) Log.w(TAG, "Pause tolerance ${stored}ms out of range, using ${clamped}ms")
        return clamped
    }

    /**
     * Silero fixes its silence timeout at build time, so a changed pause tolerance means a
     * new pipeline. Rebuilding only when the value actually moved keeps the common case
     * (nothing changed) free — building the VAD loads a model.
     */
    private fun ensureVadPipeline(silenceMs: Int) {
        if (vadPipeline?.silenceTimeoutMs == silenceMs) return
        Log.i(TAG, "Rebuilding VAD pipeline for pause tolerance ${silenceMs}ms")
        try { vadPipeline?.close() } catch (e: Exception) { Log.e(TAG, "Error closing VAD", e) }
        vadPipeline = VadAudioPipeline(this, silenceMs)
    }

    /** Drop both online clamps. Safe to call when they were never armed. */
    private fun cancelOnlineTimers() {
        handler.removeCallbacks(onlineCapRunnable)
        handler.removeCallbacks(onlineWatchdogRunnable)
    }

    /** Destroy the SpeechRecognizer on the main thread and null it out. */
    private fun destroyRecognizer() {
        cancelOnlineTimers()
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

            // Both knobs are read fresh per session, so a change in the gear screen
            // takes effect on the very next press with no restart.
            activeMaxListenMs = resolveMaxListenMs()
            activeLeadInMs = resolveLeadInMs()
            ensureVadPipeline(resolveEndpointSilenceMs().toInt())
            Log.d(TAG, "Listening budget: lead-in ${activeLeadInMs}ms, speaking ${activeMaxListenMs}ms, " +
                    "pause tolerance ${vadPipeline?.silenceTimeoutMs}ms")

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
            handler.postDelayed(timeoutRunnable, activeLeadInMs + activeMaxListenMs)

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
        // Two budgets, not one. Until the first syllable the session runs on lead-in
        // patience; from there it runs on the speaking budget. Anchoring to speech start
        // is the whole fix for a slow decider — under a single listen-anchored cap, the
        // longer someone took to begin, the less room they had to finish.
        val leadInSamples = (activeLeadInMs * SAMPLE_RATE / 1000).toInt()
        val budgetSamples = (activeMaxListenMs * SAMPLE_RATE / 1000).toInt()
        val absoluteSamples = leadInSamples + budgetSamples

        var deadlineSamples = leadInSamples
        var speechStarted = false

        Log.d(TAG, "Recording: ${leadInSamples} samples of patience (${activeLeadInMs}ms), " +
                "then ${budgetSamples} to speak (${activeMaxListenMs}ms)")

        // Reset VAD pipeline for new recording
        vadPipeline?.reset()

        var speechEndDetected = false

        try {
            while (isRecording && totalSamplesRead < deadlineSamples && !speechEndDetected && !isCancelled) {
                // Read exactly one frame worth of samples
                val shortsRead = audioRecord?.read(frameBuffer, 0, frameSize) ?: 0

                if (shortsRead == frameSize) {
                    totalSamplesRead += shortsRead

                    // Feed frame to VAD
                    val result = vadPipeline?.processFrame(frameBuffer)

                    // Track the pipeline's own view of whether speech has begun. It can
                    // flip back to false: a burst too short to qualify (a cough, a chair)
                    // is discarded by the pipeline, and when that happens the deadline has
                    // to return to patience rather than leaving a false start to eat the
                    // speaking budget.
                    val speechNow = vadPipeline?.hasSpeechBeenDetected() == true
                    if (speechNow != speechStarted) {
                        speechStarted = speechNow
                        val fromHere = if (speechNow) budgetSamples else leadInSamples
                        deadlineSamples = (totalSamplesRead + fromHere).coerceAtMost(absoluteSamples)
                        val atMs = (totalSamplesRead * 1000) / SAMPLE_RATE
                        Log.d(TAG, if (speechNow)
                            "Speech started at ${atMs}ms — ${activeMaxListenMs}ms to talk from here"
                        else
                            "False start discarded at ${atMs}ms — back to waiting")
                    }

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
                        // Downloading the model needs the internet too, so offline the
                        // "get it from settings" advice is a dead end — name the real
                        // blocker instead.
                        TtsManager.speak(
                            when {
                                !isNetworkAvailable() -> MSG_NO_NETWORK
                                modelMissing -> "Λείπει το μοντέλο ομιλίας. Κάντε λήψη από τις ρυθμίσεις."
                                else -> "Σφάλμα αναγνώρισης ομιλίας"
                            },
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
                    // Radio is a live stream: no network, no station. Say that up front
                    // instead of letting ExoPlayer report "σταθμός not found" later and
                    // blame the station for a connectivity problem.
                    if (isNetworkAvailable()) {
                        RadioActivity.launch(this@RecordingService)
                    } else {
                        Log.w(TAG, "RADIO requested with no network")
                        TtsManager.speak(MSG_NO_NETWORK, "no_network")
                    }
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
