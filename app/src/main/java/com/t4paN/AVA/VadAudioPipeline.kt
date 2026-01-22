package com.t4paN.AVA

import android.content.Context
import android.util.Log
import com.konovalov.vad.silero.Vad
import com.konovalov.vad.silero.VadSilero
import com.konovalov.vad.silero.config.FrameSize
import com.konovalov.vad.silero.config.Mode
import com.konovalov.vad.silero.config.SampleRate
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * VAD-based audio pipeline for cleaner Whisper transcription.
 *
 * Follows woheller69's approach:
 * - Accumulate ALL audio from start (no trimming)
 * - Use VAD only to detect when to STOP (silence after speech)
 * - Apply RMS normalization before sending to Whisper (more conservative than peak normalization)
 */
class VadAudioPipeline(context: Context) {

    companion object {
        private const val TAG = "VadAudioPipeline"

        // VAD parameters - matched closer to woheller69's settings
        const val SILENCE_TIMEOUT_MS = 700      // woheller69 uses 800ms
        const val MIN_SPEECH_MS = 200           // woheller69 uses 200ms
        const val FRAME_SIZE_SAMPLES = 512      // Silero VAD frame size at 16kHz
        
        // Normalization target - aim for -20dB RMS with peak ceiling at -0.5dB
        private const val TARGET_RMS = 0.1f     // ~-20dB
        private const val PEAK_CEILING = 0.95f  // Prevent clipping
        private const val MIN_RMS_THRESHOLD = 0.001f  // Silence threshold
    }

    // VAD instance
    private var vad: VadSilero? = null

    // State tracking
    private var speechDetected = false
    private var silenceFrameCount = 0
    private var speechFrameCount = 0

    // Audio accumulation buffer - keeps ALL audio from start
    private val accumulatedSamples = mutableListOf<Short>()

    // Calculated thresholds (in frames)
    private val silenceFramesThreshold: Int
    private val minSpeechFrames: Int

    // Frame duration in ms = 512 samples / 16000 Hz * 1000 = 32ms per frame
    private val frameDurationMs = (FRAME_SIZE_SAMPLES * 1000) / 16000

    init {
        silenceFramesThreshold = SILENCE_TIMEOUT_MS / frameDurationMs
        minSpeechFrames = MIN_SPEECH_MS / frameDurationMs

        Log.d(TAG, "Silence threshold: $silenceFramesThreshold frames ($SILENCE_TIMEOUT_MS ms)")
        Log.d(TAG, "Min speech frames: $minSpeechFrames frames ($MIN_SPEECH_MS ms)")

        // Initialize Silero VAD
        vad = Vad.builder()
            .setContext(context)
            .setSampleRate(SampleRate.SAMPLE_RATE_16K)
            .setFrameSize(FrameSize.FRAME_SIZE_512)
            .setMode(Mode.AGGRESSIVE)
            .setSilenceDurationMs(SILENCE_TIMEOUT_MS)
            .setSpeechDurationMs(MIN_SPEECH_MS)
            .build()

        Log.d(TAG, "Silero VAD initialized (AGGRESSIVE mode)")
    }

    /**
     * Reset state for a new recording session
     */
    fun reset() {
        speechDetected = false
        silenceFrameCount = 0
        speechFrameCount = 0
        accumulatedSamples.clear()
        Log.d(TAG, "Pipeline reset")
    }

    /**
     * Process a frame of audio data.
     *
     * Accumulates ALL audio from start (like woheller69).
     * VAD is only used to detect when to stop recording.
     *
     * @param frame ShortArray of exactly FRAME_SIZE_SAMPLES (512) samples
     * @return ProcessResult indicating current state
     */
    fun processFrame(frame: ShortArray): ProcessResult {
        if (frame.size != FRAME_SIZE_SAMPLES) {
            Log.w(TAG, "Invalid frame size: ${frame.size}, expected $FRAME_SIZE_SAMPLES")
            return ProcessResult.CONTINUE
        }

        // ALWAYS accumulate ALL audio from start (no trimming)
        accumulatedSamples.addAll(frame.toList())

        val isSpeech = vad?.isSpeech(frame) ?: false

        if (isSpeech) {
            if (!speechDetected) {
                speechDetected = true
                Log.d(TAG, "Speech START detected at ${getAccumulatedDurationMs()}ms into recording")
            }

            speechFrameCount++
            silenceFrameCount = 0

            return ProcessResult.CONTINUE

        } else {
            // Silence/noise
            if (speechDetected) {
                silenceFrameCount++

                if (silenceFrameCount >= silenceFramesThreshold) {
                    Log.d(TAG, "Speech END detected (silence timeout)")
                    Log.d(TAG, "Speech frames: $speechFrameCount, Total accumulated: ${accumulatedSamples.size} samples (${getAccumulatedDurationMs()}ms)")

                    if (speechFrameCount >= minSpeechFrames) {
                        return ProcessResult.SPEECH_END
                    } else {
                        Log.w(TAG, "Speech too short (${speechFrameCount * frameDurationMs}ms), discarding")
                        reset()
                        return ProcessResult.CONTINUE
                    }
                }
            }

            return ProcessResult.CONTINUE
        }
    }

    /**
     * Get accumulated audio as float array normalized for Whisper (-1.0 to 1.0)
     * 
     * Applies RMS-based normalization targeting -20dB RMS with peak limiting at -0.5dB.
     * This is more conservative than peak normalization and prevents clipping artifacts.
     */
    fun getAccumulatedAudioFloat(): FloatArray {
        if (accumulatedSamples.isEmpty()) {
            return FloatArray(0)
        }

        // Convert to float first (range: -1.0 to 1.0)
        val samples = FloatArray(accumulatedSamples.size)
        for (i in accumulatedSamples.indices) {
            samples[i] = accumulatedSamples[i].toFloat() / 32768f
        }

        // Calculate RMS (Root Mean Square) for intelligent normalization
        var sumSquares = 0.0
        for (sample in samples) {
            sumSquares += sample * sample
        }
        val rms = sqrt(sumSquares / samples.size).toFloat()
        
        Log.d(TAG, "Audio RMS before normalization: $rms (${20 * kotlin.math.log10(rms + 0.0001f)} dB)")
        
        // If audio is essentially silence, don't normalize
        if (rms < MIN_RMS_THRESHOLD) {
            Log.w(TAG, "Audio RMS too low ($rms), returning without normalization")
            return samples
        }
        
        // Calculate scale factor to reach target RMS
        val scaleFactor = TARGET_RMS / rms
        
        // Find what the peak would be after scaling
        var maxPeak = 0.0f
        for (sample in samples) {
            val scaled = abs(sample * scaleFactor)
            if (scaled > maxPeak) maxPeak = scaled
        }
        
        // If we'd clip, reduce scale factor to keep peaks under ceiling
        val finalScale = if (maxPeak > PEAK_CEILING) {
            scaleFactor * (PEAK_CEILING / maxPeak)
        } else {
            scaleFactor
        }
        
        // Apply normalization
        for (i in samples.indices) {
            samples[i] *= finalScale
        }
        
        // Calculate final RMS for logging
        var finalSumSquares = 0.0
        var finalMaxAbs = 0.0f
        for (sample in samples) {
            finalSumSquares += sample * sample
            val absVal = abs(sample)
            if (absVal > finalMaxAbs) finalMaxAbs = absVal
        }
        val finalRms = sqrt(finalSumSquares / samples.size).toFloat()
        
        Log.d(TAG, "Audio normalized:")
        Log.d(TAG, "  Original RMS: $rms")
        Log.d(TAG, "  Scale factor: $finalScale")
        Log.d(TAG, "  Final RMS: $finalRms (${20 * kotlin.math.log10(finalRms + 0.0001f)} dB)")
        Log.d(TAG, "  Final peak: $finalMaxAbs")

        val durationMs = (samples.size * 1000) / 16000
        Log.d(TAG, "Returning ${samples.size} samples (${durationMs}ms)")

        return samples
    }

    /**
     * Get accumulated audio as ShortArray (raw PCM, not normalized)
     */
    fun getAccumulatedAudioShort(): ShortArray {
        return accumulatedSamples.toShortArray()
    }

    /**
     * Check if any speech has been detected in this session
     */
    fun hasSpeechBeenDetected(): Boolean = speechDetected

    /**
     * Get duration of accumulated audio in milliseconds
     */
    fun getAccumulatedDurationMs(): Int {
        return (accumulatedSamples.size * 1000) / 16000
    }

    /**
     * Release VAD resources
     */
    fun close() {
        vad?.close()
        vad = null
        Log.d(TAG, "VAD closed")
    }

    /**
     * Result of processing a frame
     */
    enum class ProcessResult {
        CONTINUE,       // Keep recording
        SPEECH_END      // Speech finished, ready to transcribe
    }
}
