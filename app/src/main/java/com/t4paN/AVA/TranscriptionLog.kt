package com.t4paN.AVA

import java.text.SimpleDateFormat
import java.util.*

/**
 * Represents a single transcription attempt with all debug info
 * 
 * NOW WITH: Ambiguous candidate tracking for better debugging
 */
data class TranscriptionLog(
    val timestamp: Long = System.currentTimeMillis(),
    val originalTranscript: String,
    val fuzzifiedTranscript: String,
    val transcriptionTimeMs: Long,
    val matchedContact: String?,
    val confidence: Double?,
    val confidenceBreakdown: String?,
    val ambiguousCandidates: List<Pair<String, Double>>? = null, // NEW: Track near-misses
    val noIntentDetected: Boolean = false // NEW: Track when no command detected
) {
    fun toDisplayString(): String {
        val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val timeStr = dateFormat.format(Date(timestamp))
        
        return buildString {
            appendLine("[$timeStr]")
            appendLine("Original: \"$originalTranscript\"")
            appendLine("Fuzzified: \"$fuzzifiedTranscript\"")
            appendLine("Time: ${transcriptionTimeMs}ms")
            
            when {
                noIntentDetected -> {
                    appendLine("Result: NO INTENT DETECTED")
                    appendLine("(Transcription didn't contain call command)")
                }
                matchedContact != null && confidence != null -> {
                    appendLine("Match: $matchedContact (confidence: ${String.format("%.2f", confidence)})")
                    if (confidenceBreakdown != null) {
                        appendLine("Breakdown: $confidenceBreakdown")
                    }
                }
                ambiguousCandidates != null && ambiguousCandidates.isNotEmpty() -> {
                    appendLine("Match: AMBIGUOUS")
                    ambiguousCandidates.take(3).forEachIndexed { index, (name, conf) ->
                        appendLine("  ${index + 1}. $name (${String.format("%.3f", conf)})")
                    }
                    if (ambiguousCandidates.size >= 2) {
                        val gap = ambiguousCandidates[0].second - ambiguousCandidates[1].second
                        appendLine("  Gap: ${String.format("%.3f", gap)} (need 0.10)")
                    }
                }
                else -> {
                    appendLine("Match: NO MATCH FOUND")
                }
            }
            appendLine("---")
        }
    }
}
