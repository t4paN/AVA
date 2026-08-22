package com.t4paN.AVA

import android.content.Context
import android.util.Log

/**
 * Whether Whisper is allowed to run on this phone.
 *
 * Whisper is CPU-only here, so how usable it is depends entirely on the device, and no
 * hardware check (RAM, cores, `isLowRamDevice`) answers the question that matters —
 * how long the user is left standing there. AVA does not try to measure it either.
 * The caregiver notice asks for a few manual tries and leaves the judgement with the
 * person doing the setup, who is the only one who can weigh a slow answer against no
 * answer for this particular user.
 *
 * Default on: a phone with no signal and no offline engine has no working call button,
 * so this is never turned off *for* someone — only *by* someone who has tried it.
 */
object OfflineMode {
    private const val TAG = "OfflineMode"
    private const val PREFS = "ava_settings"

    const val KEY_ENABLED = "offline_recognition_enabled"

    /**
     * A transcription still running after this earns a spoken «Περιμένετε».
     *
     * A watchdog on the run in progress, not a prediction from history — which is what
     * makes it work on the very first run, before anyone knows how slow this phone is.
     */
    const val PATIENCE_MS = 3_000L

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        Log.i(TAG, "$KEY_ENABLED -> $enabled")
    }
}
