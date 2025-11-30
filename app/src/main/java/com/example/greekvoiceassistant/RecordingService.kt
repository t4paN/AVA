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
    private var mediaPlayer: MediaPlayer? = null
    private var recordingThread: Thread? = null

    companion object {
        private const val TAG = "RecordingService"
        private const val RECORDING_DURATION_MS = 2000L
        private const val SAMPLE_RATE = 16000 // Whisper expects 16kHz
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
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
                    initializeWhisper()
                    prepareRecorder()
                }, 1000)
            }

            mediaPlayer?.setOnErrorListener { mp, what, extra ->
                Log.e(TAG, "MediaPlayer error: what=$what, extra=$extra")
                mp.release()
                mediaPlayer = null
                initializeWhisper()
                prepareRecorder()
                true
            }

            mediaPlayer?.start()

        } catch (e: Exception) {
            Log.e(TAG, "Error playing prompt", e)
            initializeWhisper()
            prepareRecorder()
        }
    }

    private fun initializeWhisper() {
        Log.d(TAG, "Initializing Whisper...")
        try {
            Log.d(TAG, "Copying model from assets...")

            // Copy model file
            val modelPath = File(filesDir, "whisper-base.TOP_WORLD.tflite").absolutePath
            assets.open("whisper-base.TOP_WORLD.tflite").use { input ->
                FileOutputStream(modelPath).use { output ->
                    input.copyTo(output)
                }
            }

            // Copy filters_vocab file
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
                showToast("Recording init failed")
                stopSelf()
                return
            }

            audioRecord?.startRecording()
            Log.d(TAG, "Recording started!")
            showToast("Recording...")

            // Record in background thread
            recordingThread = Thread {
                recordAudio(bufferSize)
            }
            recordingThread?.start()

            // Auto-stop after 2 seconds
            handler.postDelayed({
                stopRecordingAndPlayBeep()
            }, RECORDING_DURATION_MS)

        } catch (e: Exception) {
            Log.e(TAG, "Recording error", e)
            isRecording = false
            showToast("Recording error: ${e.message}")
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

            // Convert to byte array
            val audioData = allData.toByteArray()
            Log.d(TAG, "Recorded ${audioData.size} bytes")

            // Write to WAV file using WaveUtil
            WaveUtil.createWaveFile(
                outputFile.absolutePath,
                audioData,
                SAMPLE_RATE,
                1, // mono
                2  // 16-bit = 2 bytes per sample
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

            // Wait for recording thread to finish
            recordingThread?.join(500)

            // Play beep, then transcribe
            playBeep()

        } catch (e: Exception) {
            Log.e(TAG, "Stop recording error", e)
            transcribeAudio()
        }
    }

    private fun playBeep() {
        try {
            Log.d(TAG, "Playing beep...")
            mediaPlayer = MediaPlayer.create(this, R.raw.beep)

            mediaPlayer?.setOnCompletionListener {
                Log.d(TAG, "Beep finished")
                it.release()
                mediaPlayer = null
                transcribeAudio()
            }

            mediaPlayer?.setOnErrorListener { mp, what, extra ->
                Log.e(TAG, "MediaPlayer beep error: what=$what, extra=$extra")
                mp.release()
                mediaPlayer = null
                transcribeAudio()
                true
            }

            mediaPlayer?.start()

        } catch (e: Exception) {
            Log.e(TAG, "Error playing beep", e)
            transcribeAudio()
        }
    }

    private fun transcribeAudio() {
        Thread {
            try {
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
                        showToast("Transcription was empty!")
                    } else {
                        showToast("Result: $transcription")
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Transcription error", e)
                handler.post {
                    showToast("Error: ${e.message}")
                }
            } finally {
                whisperEngine?.deinitialize()
                handler.post {
                    stopSelf()
                }
            }
        }.start()
    }

    private fun showToast(message: String) {
        handler.post {
            Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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