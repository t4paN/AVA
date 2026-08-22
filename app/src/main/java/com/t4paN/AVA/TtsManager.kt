// TtsManager.kt

package com.t4paN.AVA

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

object TtsManager {
    private const val TAG = "TtsManager"

    // Long enough to cover a cold engine bind on a slow phone (~4.4 s measured on a
    // Samsung), short enough that a genuinely broken engine does not strand the user
    // in silence with no beep either.
    private const val DEFAULT_WAIT_MS = 5000L
    private const val POLL_MS = 100L

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
     * Speak as soon as the engine is bound, giving up after [timeoutMs].
     *
     * The plain [speak] returns false and drops the line when the engine is not up
     * yet. That is the cold-start race: binding a TTS engine takes seconds on some
     * phones (~4.4 s measured on a Samsung), and the first thing AVA ever says is the
     * prompt that tells a user who cannot see the screen that it is listening. Losing
     * that one line loses the whole interaction, so it is worth waiting for.
     *
     * Polls rather than registering a callback with [initialize] on purpose: calling
     * initialize() again while a bind is in flight would construct a second
     * TextToSpeech and orphan the first.
     *
     * [onSpoken] runs when the line was handed to the engine, [onTimeout] when the
     * wait ran out — exactly one of them fires, always on the main thread.
     */
    fun speakWhenReady(
        text: String,
        utteranceId: String? = null,
        timeoutMs: Long = DEFAULT_WAIT_MS,
        onSpoken: (() -> Unit)? = null,
        onTimeout: (() -> Unit)? = null
    ) {
        val handler = Handler(Looper.getMainLooper())
        val deadline = System.currentTimeMillis() + timeoutMs

        fun attempt() {
            if (speak(text, utteranceId)) {
                onSpoken?.invoke()
                return
            }
            if (System.currentTimeMillis() >= deadline) {
                Log.w(TAG, "TTS still not ready after ${timeoutMs}ms, dropping: $text")
                onTimeout?.invoke()
                return
            }
            handler.postDelayed({ attempt() }, POLL_MS)
        }
        handler.post { attempt() }
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