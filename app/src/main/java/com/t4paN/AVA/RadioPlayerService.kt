package com.t4paN.AVA

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

/**
 * RadioPlayerService - Born to play, meant to die.
 * 
 * Lifecycle:
 * - START: Create player, start streaming, show notification
 * - STOP: Release everything, scorched earth, no pause, no resume
 * 
 * Auto-shutoff after 1 hour (safety net for "fell asleep with radio on")
 */
class RadioPlayerService : Service() {
    
    companion object {
        private const val TAG = "RadioPlayerService"
        private const val NOTIFICATION_ID = 9955
        private const val CHANNEL_ID = "ava_radio_channel"
        
        private const val AUTO_SHUTOFF_MS = 60 * 60 * 1000L  // 1 hour
        
        const val ACTION_PLAY = "com.t4paN.AVA.radio.PLAY"
        const val ACTION_STOP = "com.t4paN.AVA.radio.STOP"
        const val EXTRA_STATION_INDEX = "station_index"
        
        fun play(context: Context, stationIndex: Int) {
            val intent = Intent(context, RadioPlayerService::class.java).apply {
                action = ACTION_PLAY
                putExtra(EXTRA_STATION_INDEX, stationIndex)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
        
        fun stop(context: Context) {
            val intent = Intent(context, RadioPlayerService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
    
    private var player: ExoPlayer? = null
    private var currentStation: RadioStation? = null
    private val handler = Handler(Looper.getMainLooper())
    
    private val autoShutoffRunnable = Runnable {
        Log.i(TAG, "Auto-shutoff triggered after 1 hour")
        TtsManager.speak("Το ραδιόφωνο σταμάτησε")
        handler.postDelayed({
            stopSelf()
        }, 2000)
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        
        // Ensure TTS is ready (reuses existing if already initialized)
        TtsManager.initialize(this)
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> {
                val stationIndex = intent.getIntExtra(EXTRA_STATION_INDEX, 0)
                val station = RadioStations.getByIndex(this, stationIndex)
                startPlaying(station)
            }
            ACTION_STOP -> {
                stopPlaying()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }
    
    private fun startPlaying(station: RadioStation) {
        Log.i(TAG, "Starting playback: ${station.displayName}")
        
        // Kill any existing player first
        player?.release()
        
        currentStation = station
        
        player = ExoPlayer.Builder(this).build().apply {
            setMediaItem(MediaItem.fromUri(station.streamUrl))
            
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    when (playbackState) {
                        Player.STATE_READY -> {
                            Log.i(TAG, "Stream ready, playing")
                        }
                        Player.STATE_BUFFERING -> {
                            Log.d(TAG, "Buffering...")
                        }
                        Player.STATE_ENDED -> {
                            Log.w(TAG, "Stream ended unexpectedly")
                            stopSelf()
                        }
                        Player.STATE_IDLE -> {
                            Log.d(TAG, "Player idle")
                        }
                    }
                }
                
                override fun onPlayerError(error: PlaybackException) {
                    Log.e(TAG, "Playback error: ${error.message}")
                    TtsManager.speak("Δεν βρέθηκε ο σταθμός")
                    handler.postDelayed({
                        stopSelf()
                    }, 2000)
                }
            })
            
            prepare()
            play()
        }
        
        // Start foreground with notification
        startForeground(NOTIFICATION_ID, createNotification(station))
        
        // Schedule auto-shutoff
        handler.removeCallbacks(autoShutoffRunnable)
        handler.postDelayed(autoShutoffRunnable, AUTO_SHUTOFF_MS)
    }
    
    private fun stopPlaying() {
        Log.i(TAG, "Stopping playback")
        handler.removeCallbacks(autoShutoffRunnable)
        player?.release()
        player = null
        currentStation = null
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Ραδιόφωνο AVA",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Ειδοποίηση αναπαραγωγής ραδιοφώνου"
                setSound(null, null)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(station: RadioStation): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, RadioActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AVA Ραδιόφωνο")
            .setContentText(station.displayName)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSound(null)
            .build()
    }
    
    override fun onDestroy() {
        Log.i(TAG, "Service destroyed - scorched earth cleanup")
        stopPlaying()
        super.onDestroy()
    }
    
    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.i(TAG, "Task removed - killing service")
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }
}
