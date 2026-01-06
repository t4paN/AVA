// TtsManager.kt

package com.t4paN.AVA

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

object TtsManager {
    private const val TAG = "TtsManager"

    @Volatile
    private var tts: TextToSpeech? = null

    @Volatile
    var isReady = false
        private set

    private val lock = Any()

    /**
     * Initialize TTS if not already ready.
     * Safe to call multiple times - will reuse existing engine.
     */
    fun initialize(context: Context, onReady: (() -> Unit)? = null) {
        synchronized(lock) {
            if (tts != null && isReady) {
                Log.d(TAG, "Reusing existing TTS engine")
                onReady?.invoke()
                return
            }

            Log.d(TAG, "First-time TTS initialization...")

            tts = TextToSpeech(context.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    val result = tts?.setLanguage(Locale("el", "GR"))
                    if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                        Log.w(TAG, "Greek TTS not available, using default")
                        tts?.setLanguage(Locale.getDefault())
                    }
                    isReady = true
                    Log.d(TAG, "TTS initialized and ready")
                    onReady?.invoke()
                } else {
                    Log.e(TAG, "TTS initialization failed with status: $status")
                    isReady = false
                }
            }
        }
    }

    /**
     * Speak text with utterance ID for callbacks.
     * Returns true if speech started, false if TTS not ready.
     */
    fun speak(text: String, utteranceId: String? = null): Boolean {
        if (!isReady || tts == null) {
            Log.w(TAG, "TTS not ready, cannot speak: $text")
            return false
        }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        return true
    }

    /**
     * Set speech rate. 1.0 = normal, 0.75 = slow, 1.25 = fast.
     */
    fun setSpeechRate(rate: Float) {
        tts?.setSpeechRate(rate)
    }

    /**
     * Set listener for utterance callbacks.
     * Each service should set its own listener before speaking.
     */
    fun setUtteranceListener(listener: UtteranceProgressListener) {
        tts?.setOnUtteranceProgressListener(listener)
    }

    /**
     * Stop current speech without destroying engine.
     * Safe to call during cancel operations.
     */
    fun stop() {
        try {
            tts?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping TTS", e)
        }
    }

    /**
     * Force reset - only call during nuke/full restart.
     * Destroys engine completely for clean state.
     */
    fun reset() {
        synchronized(lock) {
            Log.w(TAG, "Force-resetting TTS engine")
            try {
                tts?.stop()
                tts?.shutdown()
            } catch (e: Exception) {
                Log.e(TAG, "Error resetting TTS", e)
            }
            tts = null
            isReady = false
        }
    }
}