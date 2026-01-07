package com.t4paN.AVA

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.t4paN.AVA.databinding.ActivityRadioBinding

/**
 * RadioActivity - Fullscreen radio control overlay.
 *
 * Layout:
 * ┌─────────────────────────────────┐
 * │      [  ▲  ]  (next station)    │  <- Green zone
 * │      [ ▶/■ ]  (play/stop)       │  <- Blue/Purple zone
 * │      [  ▼  ]  (prev station)    │  <- Orange zone
 * └─────────────────────────────────┘
 *
 * Lifecycle:
 * - Foreground + screen on: streaming active
 * - Screen off: streaming continues (1h max)
 * - Minimized/home/back pressed: KILL PLAYBACK
 */
class RadioActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "RadioActivity"

        // Colors
        private const val COLOR_BLUE = 0xFF2196F3.toInt()
        private const val COLOR_PURPLE = 0xFF9C27B0.toInt()
        private const val BG_BLUE = 0x4D2196F3
        private const val BG_PURPLE = 0x4D9C27B0

        fun launch(context: Context) {
            context.startActivity(Intent(context, RadioActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
        }
    }

    private lateinit var binding: ActivityRadioBinding

    private var currentStationIndex = 0
    private var isPlaying = false
    private var vibrator: Vibrator? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityRadioBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Hide action bar for fullscreen
        supportActionBar?.hide()

        // Initialize vibrator (same pattern as RecordingService)
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        // Ensure TTS is ready
        TtsManager.initialize(this)

        // Set up click listeners
        setupClickListeners()

        // Initialize UI with first station
        updateUI()

        // Announce radio mode
        TtsManager.speak("Ραδιόφωνο")
    }

    private fun setupClickListeners() {
        binding.zoneNext.setOnClickListener {
            changeStation(1)
        }

        binding.zonePrev.setOnClickListener {
            changeStation(-1)
        }

        binding.zonePlayStop.setOnClickListener {
            togglePlayStop()
        }
    }

    private fun changeStation(direction: Int) {
        vibrateClick()

        currentStationIndex = if (direction > 0) {
            RadioStations.nextIndex(currentStationIndex)
        } else {
            RadioStations.prevIndex(currentStationIndex)
        }

        val station = RadioStations.getByIndex(currentStationIndex)
        Log.i(TAG, "Changed to station: ${station.displayName}")

        // Update UI
        updateUI()

        // Announce station name
        TtsManager.speak(station.displayName)

        // If currently playing, switch to new station
        if (isPlaying) {
            RadioPlayerService.play(this, currentStationIndex)
        }
    }

    private fun togglePlayStop() {
        vibrateClick()
        playBeep()

        if (isPlaying) {
            // STOP - kill everything
            Log.i(TAG, "Stopping radio")
            RadioPlayerService.stop(this)
            isPlaying = false
            updateUI()
            TtsManager.speak("Στοπ")
        } else {
            // PLAY - start fresh
            val station = RadioStations.getByIndex(currentStationIndex)
            Log.i(TAG, "Playing: ${station.displayName}")
            RadioPlayerService.play(this, currentStationIndex)
            isPlaying = true
            updateUI()
            TtsManager.speak(station.displayName)
        }
    }

    private fun updateUI() {
        val station = RadioStations.getByIndex(currentStationIndex)

        // Update station name
        binding.txtStationName.text = station.displayName

        // Update play/stop button appearance
        if (isPlaying) {
            binding.txtPlayStopIcon.text = "■"
            binding.txtPlayStopIcon.setTextColor(COLOR_PURPLE)
            binding.txtPlayStopHint.text = "ΠΑΤΑ ΓΙΑ ΣΤΟΠ"
            binding.zonePlayStop.setBackgroundColor(BG_PURPLE)
        } else {
            binding.txtPlayStopIcon.text = "▶"
            binding.txtPlayStopIcon.setTextColor(COLOR_BLUE)
            binding.txtPlayStopHint.text = "ΠΑΤΑ ΓΙΑ PLAY"
            binding.zonePlayStop.setBackgroundColor(BG_BLUE)
        }
    }

    private fun vibrateClick() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(50)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Vibration error", e)
        }
    }

    private fun playBeep() {
        try {
            val toneGen = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
            toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, 100)
            handler.postDelayed({
                toneGen.release()
            }, 150)
        } catch (e: Exception) {
            Log.e(TAG, "Beep error", e)
        }
    }

    override fun onStop() {
        super.onStop()

        // Check if screen is actually off
        val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager

        if (powerManager.isInteractive) {
            // Screen is ON, so user actually left the app
            Log.i(TAG, "Activity stopped (screen on) - killing radio")
            RadioPlayerService.stop(this)
            isPlaying = false
            updateUI()
        } else {
            // Screen is OFF, keep playing
            Log.d(TAG, "Activity stopped but screen off - keeping radio alive")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "RadioActivity destroyed")
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Back = kill everything and exit
        Log.i(TAG, "Back pressed - killing radio")
        RadioPlayerService.stop(this)
        isPlaying = false
        super.onBackPressed()
    }
}