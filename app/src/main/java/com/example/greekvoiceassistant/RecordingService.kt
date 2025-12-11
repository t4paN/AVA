package com.example.greekvoiceassistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.greekvoiceassistant.whisper.engine.WhisperEngineNative
import com.greekvoiceassistant.whisper.utils.WaveUtil
import java.io.File
import com.greekvoiceassistant.whisper.engine.WhisperEngine
import com.greekvoiceassistant.whisper.engine.WhisperEngineJava
import java.io.FileOutputStream
import com.example.greekvoiceassistant.Contact
import com.example.greekvoiceassistant.ContactRepository
import com.example.greekvoiceassistant.SuperFuzzyContactMatcher
import org.json.JSONArray
import org.json.JSONObject


class RecordingService : Service() {

    private var audioRecord: AudioRecord? = null
    private lateinit var outputFile: File
    private val handler = Handler(Looper.getMainLooper())
    private var whisperEngine: WhisperEngine? = null
    private var isRecording = false
    private var isProcessing = false
    private var mediaPlayer: MediaPlayer? = null
    private var recordingThread: Thread? = null
    
    // Cache contacts in memory for fuzzy matching
    private var cachedContacts: List<Contact> = emptyList()

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
        
        // Store transcription logs for display in FirstFragment
        private val transcriptionLogs = mutableListOf<TranscriptionLog>()
        private var logUpdateCallback: (() -> Unit)? = null
        
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
        
        // Load persisted logs on startup
        loadPersistedLogs(this)
        
        // Load contacts once when service starts
        cachedContacts = ContactRepository.loadContacts(this)
        Log.i(TAG, "RecordingService created with ${cachedContacts.size} cached contacts")
        
        playPrompt()
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
            .setContentTitle("Listening...")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSound(null)
            .build()

        startForeground(1, notification)
    }

    private fun playPrompt() {
        try {
            Log.d(TAG, "Playing prompt...")
            mediaPlayer = MediaPlayer.create(this, R.raw.prompt)

            mediaPlayer?.setOnCompletionListener {
                Log.d(TAG, "Prompt finished")
                try {
                    it.stop()
                    it.release()
                } catch (e: Exception) {
                    Log.e(TAG, "Error releasing MediaPlayer", e)
                }
                mediaPlayer = null

                handler.postDelayed({
                    if (whisperEngine == null) {
                        Log.d(TAG, "First run - initializing Whisper...")
                        initializeWhisper()
                    }
                    prepareRecorder()
                }, 100)
            }

            mediaPlayer?.setOnErrorListener { mp, what, extra ->
                Log.e(TAG, "MediaPlayer error: what=$what, extra=$extra")
                mp.release()
                mediaPlayer = null
                if (whisperEngine == null) {
                    initializeWhisper()
                }
                prepareRecorder()
                true
            }

            mediaPlayer?.start()

        } catch (e: Exception) {
            Log.e(TAG, "Error playing prompt", e)
            if (whisperEngine == null) {
                initializeWhisper()
            }
            prepareRecorder()
        }
    }

    private fun initializeWhisper() {
        Log.d(TAG, "Initializing Whisper...")
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

            whisperEngine = WhisperEngineJava(this)
            whisperEngine?.initialize(modelPath, filtersVocabPath, true)
            Log.d(TAG, "Whisper initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Whisper init error", e)
        }
    }

    private fun prepareRecorder() {
        if (isRecording) return

        try {
            Log.d(TAG, "Preparing AudioRecord...")
            isRecording = true
            outputFile = File(cacheDir, "voice_input.wav")

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

            playStartBeep()

            safeToast("Recording...")

            recordingThread = Thread {
                recordAudio(bufferSize)
            }
            recordingThread?.start()

            handler.postDelayed({
                stopRecordingAndPlayBeep()
            }, RECORDING_DURATION_MS)

        } catch (e: Exception) {
            Log.e(TAG, "Recording error", e)
            isRecording = false
            safeToast("Recording error: ${e.message}")
            stopSelf()
        }
    }

    private fun recordAudio(bufferSize: Int) {
        val data = ByteArray(bufferSize)
        val allData = mutableListOf<Byte>()

        try {
            while (isRecording) {
                val read = audioRecord?.read(data, 0, bufferSize) ?: 0
                if (read > 0) {
                    for (i in 0 until read) {
                        allData.add(data[i])
                    }
                }
            }

            val audioData = allData.toByteArray()
            Log.d(TAG, "Recorded ${audioData.size} bytes")

            WaveUtil.createWaveFile(
                outputFile.absolutePath,
                audioData,
                SAMPLE_RATE,
                1,
                2
            )
            Log.d(TAG, "WAV file created: ${outputFile.absolutePath}")

        } catch (e: Exception) {
            Log.e(TAG, "Error during recording", e)
        }
    }

    private fun stopRecordingAndPlayBeep() {
        if (!isRecording) return

        try {
            Log.d(TAG, "Stopping recorder...")
            isRecording = false

            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null

            recordingThread?.join(500)

            playStopBeep()

        } catch (e: Exception) {
            Log.e(TAG, "Stop recording error", e)
            transcribeAudio()
        }
    }

    private fun playStartBeep() {
        // DISABLED for testing - beep was overlapping with recording start **** Change to vibrate/buzz instead
        Log.d(TAG, "Start beep disabled for testing")
    }

    private fun playStopBeep() {
        try {
            Log.d(TAG, "Playing stop beep...")
            mediaPlayer = MediaPlayer.create(this, R.raw.beep)

            mediaPlayer?.setOnCompletionListener {
                Log.d(TAG, "Stop beep finished")
                it.release()
                mediaPlayer = null

                if (isProcessing) {
                    Log.d(TAG, "Already processing - skipping this transcription")
                    stopSelf()
                    return@setOnCompletionListener
                }

                transcribeAudio()
            }

            mediaPlayer?.setOnErrorListener { mp, what, extra ->
                Log.e(TAG, "MediaPlayer stop beep error: what=$what, extra=$extra")
                mp.release()
                mediaPlayer = null

                if (!isProcessing) {
                    transcribeAudio()
                }
                true
            }

            mediaPlayer?.start()

        } catch (e: Exception) {
            Log.e(TAG, "Error playing stop beep", e)
            if (!isProcessing) {
                transcribeAudio()
            }
        }
    }

    private fun transcribeAudio() {
        Thread {
            try {
                isProcessing = true

                Log.d(TAG, "=== Starting transcription ===")
                Log.d(TAG, "Audio file: ${outputFile.absolutePath}")
                Log.d(TAG, "File exists: ${outputFile.exists()}")
                Log.d(TAG, "File size: ${outputFile.length()} bytes")

                val startTime = System.currentTimeMillis()
                val transcription = whisperEngine?.transcribeFile(outputFile.absolutePath) ?: ""
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
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Transcription error", e)
                handler.post {
                    safeToast("Error: ${e.message}")
                }
            } finally {
                isProcessing = false
                handler.post {
                    stopSelf()
                }
            }
        }.start()
    }

    /**
     * Handle transcription with intent detection and routing
     * 
     * NEW: Detects CALL, FLASHLIGHT, and RADIO intents before processing
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
                // toggleFlashlight()
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
                // playRadio()
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
                }
            }
        }
    }
    
    /**
     * Handle CALL intent - match contact and initiate call
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
            
            // TODO: Initiate phone call
            // initiateCall(matchResult.contact.phoneNumber)
            
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
            }
            
            // TODO: Play "contact not found" TTS message
            // playContactNotFoundMessage()
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "PRELOAD_WHISPER") {
            Log.d(TAG, "Preloading Whisper...")
            if (whisperEngine == null) {
                initializeWhisper()
            }
            stopSelf()
            return START_NOT_STICKY
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        isRecording = false
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {}
        try {
            mediaPlayer?.release()
        } catch (e: Exception) {}
        audioRecord = null
        mediaPlayer = null
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
