// DeepOptionsActivity.kt

package com.t4paN.AVA

import android.app.AlertDialog
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.TextView

/**
 * The gear: advanced settings for a caregiver or an experienced user. The (!) notice
 * explains; this adjusts.
 *
 * Two rules shape the whole screen:
 *
 * 1. **Reset comes first, above every knob.** The caregiver tinkers on someone else's
 *    phone and then leaves. If a setting turns out wrong, the person living with it
 *    usually cannot see the screen to describe what changed, and often cannot say more
 *    than "it stopped working". There is no support channel back, so the escape hatch
 *    has to be reachable without understanding what broke.
 * 2. **Steppers, not sliders.** A slider needs fine motor precision and lands badly at
 *    high display zoom. A stepper is also describable over the phone: "press plus twice".
 */
class DeepOptionsActivity : CaregiverScreen() {

    companion object {
        private const val TAG = "DeepOptions"
        private const val PREFS = "ava_settings"

        // Kept in step with RecordingService's clamps. Duplicated rather than shared
        // because the service owns the runtime contract and this screen owns the UI
        // one; if they ever disagree the service still clamps on read, so an
        // out-of-range value cannot reach the recogniser.
        private const val KEY_THINK_GAP = "think_gap_ms"
        private const val GAP_DEFAULT = 0L
        private const val GAP_MIN = 0L
        private const val GAP_MAX = 10000L
        private const val GAP_STEP = 500L

        private const val KEY_MAX_LISTEN = "max_listen_ms"
        private const val LISTEN_DEFAULT = 5000L
        private const val LISTEN_MIN = 2000L
        private const val LISTEN_MAX = 15000L
        private const val LISTEN_STEP = 1000L

        private const val KEY_ENDPOINT_SILENCE = "endpoint_silence_ms"
        private const val PAUSE_DEFAULT = 700L
        private const val PAUSE_MIN = 200L
        private const val PAUSE_MAX = 3000L
        private const val PAUSE_STEP = 100L
    }

    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var txtGapValue: TextView
    private lateinit var txtListenValue: TextView
    private lateinit var txtPauseValue: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_deep_options)
        padForSystemBars(findViewById(R.id.deepOptionsContent))
        title = "Προχωρημένες ρυθμίσεις"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        TtsManager.initialize(this)

        txtGapValue = findViewById(R.id.txtGapValue)
        txtListenValue = findViewById(R.id.txtListenValue)
        txtPauseValue = findViewById(R.id.txtPauseValue)

        findViewById<Button>(R.id.btnResetDefaults).setOnClickListener { confirmReset() }

        findViewById<Button>(R.id.btnGapMinus).setOnClickListener { nudgeGap(-GAP_STEP) }
        findViewById<Button>(R.id.btnGapPlus).setOnClickListener { nudgeGap(+GAP_STEP) }
        findViewById<Button>(R.id.btnListenMinus).setOnClickListener { nudgeListen(-LISTEN_STEP) }
        findViewById<Button>(R.id.btnListenPlus).setOnClickListener { nudgeListen(+LISTEN_STEP) }
        findViewById<Button>(R.id.btnPauseMinus).setOnClickListener { nudgePause(-PAUSE_STEP) }
        findViewById<Button>(R.id.btnPausePlus).setOnClickListener { nudgePause(+PAUSE_STEP) }

        findViewById<Button>(R.id.btnTryIt).setOnClickListener { tryItNow() }

        render()
    }

    private fun gapMs() = prefs.getLong(KEY_THINK_GAP, GAP_DEFAULT).coerceIn(GAP_MIN, GAP_MAX)
    private fun listenMs() = prefs.getLong(KEY_MAX_LISTEN, LISTEN_DEFAULT).coerceIn(LISTEN_MIN, LISTEN_MAX)
    private fun pauseMs() = prefs.getLong(KEY_ENDPOINT_SILENCE, PAUSE_DEFAULT).coerceIn(PAUSE_MIN, PAUSE_MAX)

    private fun render() {
        // Zero is a real setting, not an empty field, so it gets words rather than "0".
        txtGapValue.text = if (gapMs() == 0L) "Χωρίς αναμονή"
                           else String.format("%.1f δευτερόλεπτα", gapMs() / 1000.0)
        txtListenValue.text = "${listenMs() / 1000} δευτερόλεπτα"
        // Greek uses a decimal comma, and the value is sub-second, so seconds with one
        // decimal reads better than "700 χιλιοστά" to someone who is not an engineer.
        txtPauseValue.text = String.format("%.1f δευτερόλεπτα", pauseMs() / 1000.0)
    }

    private fun nudgeGap(delta: Long) {
        val next = (gapMs() + delta).coerceIn(GAP_MIN, GAP_MAX)
        prefs.edit().putLong(KEY_THINK_GAP, next).apply()
        Log.i(TAG, "think_gap_ms -> $next")
        render()
    }

    private fun nudgeListen(delta: Long) {
        val next = (listenMs() + delta).coerceIn(LISTEN_MIN, LISTEN_MAX)
        prefs.edit().putLong(KEY_MAX_LISTEN, next).apply()
        Log.i(TAG, "max_listen_ms -> $next")
        render()
    }

    private fun nudgePause(delta: Long) {
        val next = (pauseMs() + delta).coerceIn(PAUSE_MIN, PAUSE_MAX)
        prefs.edit().putLong(KEY_ENDPOINT_SILENCE, next).apply()
        Log.i(TAG, "endpoint_silence_ms -> $next")
        render()
    }

    private fun confirmReset() {
        AlertDialog.Builder(this)
            .setTitle("Επαναφορά προεπιλογών;")
            .setMessage("Όλες οι ρυθμίσεις θα γυρίσουν όπως ήρθαν. Το μοντέλο ομιλίας και οι επαφές δεν πειράζονται.")
            .setPositiveButton("Επαναφορά") { _, _ -> doReset() }
            .setNegativeButton("Άκυρο", null)
            .show()
    }

    /**
     * Clearing the file beats writing each shipped default back: every getter in the app
     * already supplies its own default, so an empty file *is* the shipped state, and a
     * setting added later is covered without anyone remembering to update this list.
     *
     * Only `ava_settings` is touched. The Whisper model lives in filesDir and its presence
     * is checked from the filesystem, never from a pref, so it survives — re-downloading
     * 100 MB is a punishment, not a recovery, and may be impossible where they're standing.
     * Radio stations live in their own prefs file and have their own Επαναφορά.
     */
    private fun doReset() {
        prefs.edit().clear().apply()
        Log.w(TAG, "ava_settings cleared — back to shipped defaults")
        render()
        // Spoken as well as shown, so it still works for a primary user being talked
        // through this over the phone.
        TtsManager.speakWhenReady("Οι ρυθμίσεις επανήλθαν", "reset_done")
    }

    /**
     * Runs a real capture at the current values. Tuning with the primary user present
     * beats guessing for them: the caregiver hears where it cuts off and adjusts on the
     * spot. Both knobs are read fresh per session, so no restart is needed.
     */
    private fun tryItNow() {
        val intent = Intent(this, RecordingService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        // Step back so the capture overlay is not competing with this screen.
        handler.postDelayed({ moveTaskToBack(true) }, 300)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
