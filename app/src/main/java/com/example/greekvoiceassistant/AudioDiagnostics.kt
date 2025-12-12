package com.example.greekvoiceassistant

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Diagnostic utilities for debugging audio pipeline issues.
 * 
 * USAGE: Add these calls temporarily to RecordingService to inspect audio:
 * 
 * ```kotlin
 * // In transcribeAudio(), before calling whisperEngine:
 * AudioDiagnostics.saveAudioToWav(
 *     this, 
 *     audioSamples, 
 *     "debug_audio_${System.currentTimeMillis()}.wav"
 * )
 * ```
 */
object AudioDiagnostics {
    
    private const val TAG = "AudioDiagnostics"
    
    /**
     * Save float audio samples to a WAV file for manual inspection.
     * File will be saved to app's files directory.
     */
    fun saveAudioToWav(context: Context, samples: FloatArray, filename: String) {
        try {
            val file = File(context.filesDir, filename)
            FileOutputStream(file).use { fos ->
                // WAV header
                val sampleRate = 16000
                val numChannels = 1
                val bitsPerSample = 16
                val byteRate = sampleRate * numChannels * bitsPerSample / 8
                val blockAlign = (numChannels * bitsPerSample / 8).toShort()
                val dataSize = samples.size * 2  // 2 bytes per sample (16-bit)
                
                // RIFF chunk
                fos.write("RIFF".toByteArray())
                fos.write(intToBytes(36 + dataSize))
                fos.write("WAVE".toByteArray())
                
                // fmt chunk
                fos.write("fmt ".toByteArray())
                fos.write(intToBytes(16))  // Chunk size
                fos.write(shortToBytes(1))  // Audio format (1 = PCM)
                fos.write(shortToBytes(numChannels.toShort()))
                fos.write(intToBytes(sampleRate))
                fos.write(intToBytes(byteRate))
                fos.write(shortToBytes(blockAlign))
                fos.write(shortToBytes(bitsPerSample.toShort()))
                
                // data chunk
                fos.write("data".toByteArray())
                fos.write(intToBytes(dataSize))
                
                // Convert float samples to 16-bit PCM
                for (sample in samples) {
                    val intSample = (sample * 32767f).toInt().coerceIn(-32768, 32767)
                    fos.write(shortToBytes(intSample.toShort()))
                }
            }
            
            Log.d(TAG, "Saved audio to: ${file.absolutePath}")
            Log.d(TAG, "  Samples: ${samples.size}")
            Log.d(TAG, "  Duration: ${samples.size / 16000f}s")
            
            // Calculate and log statistics
            val stats = calculateStats(samples)
            Log.d(TAG, "Audio statistics:")
            Log.d(TAG, "  RMS: ${stats.rms}")
            Log.d(TAG, "  Peak: ${stats.peak}")
            Log.d(TAG, "  Min: ${stats.min}")
            Log.d(TAG, "  Max: ${stats.max}")
            Log.d(TAG, "  Zero crossings: ${stats.zeroCrossings}")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error saving WAV file", e)
        }
    }
    
    /**
     * Calculate comprehensive audio statistics for debugging
     */
    fun calculateStats(samples: FloatArray): AudioStats {
        if (samples.isEmpty()) {
            return AudioStats(0f, 0f, 0f, 0f, 0)
        }
        
        var min = Float.MAX_VALUE
        var max = Float.MIN_VALUE
        var sumSquares = 0.0
        var zeroCrossings = 0
        var lastSign = if (samples[0] >= 0) 1 else -1
        
        for (sample in samples) {
            if (sample < min) min = sample
            if (sample > max) max = sample
            sumSquares += sample * sample
            
            val sign = if (sample >= 0) 1 else -1
            if (sign != lastSign) {
                zeroCrossings++
                lastSign = sign
            }
        }
        
        val rms = kotlin.math.sqrt(sumSquares / samples.size).toFloat()
        val peak = kotlin.math.max(kotlin.math.abs(min), kotlin.math.abs(max))
        
        return AudioStats(rms, peak, min, max, zeroCrossings)
    }
    
    /**
     * Log detailed frame-by-frame VAD analysis
     * Call this from VadAudioPipeline.processFrame() to see what VAD is detecting
     */
    fun logFrameAnalysis(frameNumber: Int, frame: ShortArray, isSpeech: Boolean) {
        // Calculate frame energy
        var energy = 0.0
        for (sample in frame) {
            val normalized = sample.toFloat() / 32768f
            energy += normalized * normalized
        }
        val rms = kotlin.math.sqrt(energy / frame.size).toFloat()
        
        // Calculate zero crossing rate
        var zeroCrossings = 0
        for (i in 1 until frame.size) {
            val prevSign = if (frame[i-1] >= 0) 1 else -1
            val currSign = if (frame[i] >= 0) 1 else -1
            if (prevSign != currSign) zeroCrossings++
        }
        val zcr = zeroCrossings.toFloat() / frame.size
        
        Log.d("VADFrameAnalysis", "Frame $frameNumber: " +
                "speech=$isSpeech, " +
                "RMS=${String.format("%.4f", rms)}, " +
                "ZCR=${String.format("%.4f", zcr)}")
    }
    
    /**
     * Compare two audio buffers to see if they're similar
     * Useful for verifying normalization didn't corrupt audio
     */
    fun compareAudioBuffers(original: FloatArray, processed: FloatArray): BufferComparison {
        if (original.size != processed.size) {
            return BufferComparison(
                sizeMismatch = true,
                correlationCoefficient = 0.0,
                rmsDifference = 0.0
            )
        }
        
        // Calculate correlation coefficient
        var sumOrig = 0.0
        var sumProc = 0.0
        for (i in original.indices) {
            sumOrig += original[i]
            sumProc += processed[i]
        }
        val meanOrig = sumOrig / original.size
        val meanProc = sumProc / processed.size
        
        var numerator = 0.0
        var denomOrig = 0.0
        var denomProc = 0.0
        
        for (i in original.indices) {
            val diffOrig = original[i] - meanOrig
            val diffProc = processed[i] - meanProc
            numerator += diffOrig * diffProc
            denomOrig += diffOrig * diffOrig
            denomProc += diffProc * diffProc
        }
        
        val correlation = if (denomOrig * denomProc > 0) {
            numerator / kotlin.math.sqrt(denomOrig * denomProc)
        } else 0.0
        
        // Calculate RMS difference
        var sumSquaredDiff = 0.0
        for (i in original.indices) {
            val diff = processed[i] - original[i]
            sumSquaredDiff += diff * diff
        }
        val rmsDiff = kotlin.math.sqrt(sumSquaredDiff / original.size)
        
        return BufferComparison(
            sizeMismatch = false,
            correlationCoefficient = correlation,
            rmsDifference = rmsDiff
        )
    }
    
    // Helper functions for WAV file creation
    private fun intToBytes(value: Int): ByteArray {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
    }
    
    private fun shortToBytes(value: Short): ByteArray {
        return ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value).array()
    }
    
    data class AudioStats(
        val rms: Float,
        val peak: Float,
        val min: Float,
        val max: Float,
        val zeroCrossings: Int
    )
    
    data class BufferComparison(
        val sizeMismatch: Boolean,
        val correlationCoefficient: Double,  // 1.0 = identical, 0 = uncorrelated
        val rmsDifference: Double
    )
}

/**
 * EXAMPLE USAGE IN RecordingService.kt:
 * 
 * In transcribeAudio(), add this after getting audioSamples:
 * 
 * ```kotlin
 * // Save audio for manual inspection
 * AudioDiagnostics.saveAudioToWav(
 *     this, 
 *     audioSamples, 
 *     "recording_${System.currentTimeMillis()}.wav"
 * )
 * 
 * // Log statistics
 * val stats = AudioDiagnostics.calculateStats(audioSamples)
 * Log.d(TAG, "Audio stats: RMS=${stats.rms}, Peak=${stats.peak}, ZC=${stats.zeroCrossings}")
 * ```
 * 
 * To enable frame-by-frame VAD analysis, add this in VadAudioPipeline.processFrame():
 * 
 * ```kotlin
 * // Right after calling vad?.isSpeech(frame)
 * AudioDiagnostics.logFrameAnalysis(totalFrames, frame, isSpeech)
 * ```
 * 
 * Then use `adb pull` to retrieve WAV files:
 * ```bash
 * adb shell run-as com.example.greekvoiceassistant ls files/
 * adb shell run-as com.example.greekvoiceassistant cat files/recording_*.wav > recording.wav
 * ```
 */
