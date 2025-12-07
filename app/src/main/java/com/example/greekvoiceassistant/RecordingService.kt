package com.example.greekvoiceassistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
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


class RecordingService : Service() {

    private var audioRecord: AudioRecord? = null
    private lateinit var outputFile: File
    private val handler = Handler(Looper.getMainLooper())
    private var whisperEngine: WhisperEngine? = null
    private var isRecording = false
    private var isProcessing = false
    private var mediaPlayer: MediaPlayer? = null
    private var recordingThread: Thread? = null

    companion object {
        private const val TAG = "RecordingService"
        private const val RECORDING_DURATION_MS = 4000L
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
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
        try {
            Log.d(TAG, "Playing start beep...")
            mediaPlayer = MediaPlayer.create(this, R.raw.beep)

            mediaPlayer?.setOnCompletionListener {
                Log.d(TAG, "Start beep finished")
                it.release()
                mediaPlayer = null
            }

            mediaPlayer?.setOnErrorListener { mp, what, extra ->
                Log.e(TAG, "MediaPlayer start beep error: what=$what, extra=$extra")
                mp.release()
                mediaPlayer = null
                true
            }

            mediaPlayer?.start()

        } catch (e: Exception) {
            Log.e(TAG, "Error playing start beep", e)
        }
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
                val duration = System.currentTimeMillis() - startTime

                Log.d(TAG, "Transcription took ${duration}ms")
                Log.d(TAG, "Transcription result: '$transcription'")

                handler.post {
                    if (transcription.isEmpty()) {
                        safeToast("Transcription was empty!")
                    } else {
                        safeToast("Result: $transcription")
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