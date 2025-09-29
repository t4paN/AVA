package com.example.greekvoiceassistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.IOException

/**
 * Foreground service that records raw audio to a temporary file.
 * Whisper (C++ JNI) will consume the file after the service stops.
 */
class RecordingService : Service() {

    private var recorder: MediaRecorder? = null
    private lateinit var outputFile: File

    override fun onCreate() {
        super.onCreate()
        startForegroundServiceNotification()
        prepareRecorder()
    }

    private fun startForegroundServiceNotification() {
        val channelId = "record_channel"
        val channelName = "Recording Service"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(
                channelId, channelName,
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(chan)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Recording audio")
            .setContentText("Listening for contact name…")
            .setSmallIcon(R.drawable.ic_mic) // create a small icon in res/drawable
            .setOngoing(true)
            .build()

        startForeground(1, notification)
    }

    private fun prepareRecorder() {
        try {
            outputFile = File(cacheDir, "voice_input.wav")

            recorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
                setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
                setOutputFile(outputFile.absolutePath)
                prepare()
                start()
            }
        } catch (e: IOException) {
            e.printStackTrace()
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Already started recording in onCreate
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            recorder?.apply {
                stop()
                release()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        recorder = null

        // TODO: hand off outputFile to the Whisper C++ JNI pipeline
        // e.g., send a broadcast or use a shared ViewModel / WorkManager job
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

