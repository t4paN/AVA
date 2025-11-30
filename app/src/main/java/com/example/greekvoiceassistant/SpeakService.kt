package com.example.greekvoiceassistant

import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.Locale

class SpeakService : Service(), TextToSpeech.OnInitListener {
    companion object {
        const val EXTRA_TEXT = "EXTRA_TEXT"
        const val EXTRA_START_RECORDING = "EXTRA_START_RECORDING"
        private const val TAG = "SpeakService"
        private const val CHANNEL_ID = "tts_channel"
        private const val NOTIF_ID = 1001
        private const val UTTERANCE_ID = "utt_id"
    }

    private var tts: TextToSpeech? = null
    private var textToSpeak: String = "Γειά σου"
    private var shouldStartRecording = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "TTS"
            val desc = "Notification channel for TTS foreground service"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = android.app.NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = desc
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        textToSpeak = intent?.getStringExtra(EXTRA_TEXT) ?: textToSpeak
        shouldStartRecording = intent?.getBooleanExtra(EXTRA_START_RECORDING, false) ?: false

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Voice assistant")
            .setContentText(textToSpeak)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOnlyAlertOnce(true)
            .build()

        startForeground(NOTIF_ID, notification)

        tts = TextToSpeech(this, this)

        return START_NOT_STICKY
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val locale = Locale.forLanguageTag("el-GR")
            val result = tts?.setLanguage(locale) ?: TextToSpeech.LANG_MISSING_DATA

            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e(TAG, "Greek language missing or not supported: $result")
                stopSelfAndCleanup()
                return
            }

            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}

                override fun onDone(utteranceId: String?) {
                    if (shouldStartRecording) {
                        // Start recording after TTS finishes
                        Log.d(TAG, "TTS done, starting recording...")
                        val recordIntent = Intent(this@SpeakService, RecordingService::class.java)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            startForegroundService(recordIntent)
                        } else {
                            startService(recordIntent)
                        }
                    }
                    stopSelfAndCleanup()
                }

                @Deprecated("Deprecated")
                override fun onError(utteranceId: String?) { stopSelfAndCleanup() }

                override fun onError(utteranceId: String?, errorCode: Int) { stopSelfAndCleanup() }
            })

            tts?.speak(textToSpeak, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
        } else {
            Log.e(TAG, "TTS initialization failed: $status")
            stopSelfAndCleanup()
        }
    }

    private fun stopSelfAndCleanup() {
        try { tts?.shutdown() } catch (_: Exception) {}
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        try { tts?.shutdown() } catch (_: Exception) {}
        super.onDestroy()
    }
}