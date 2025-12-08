package com.example.greekvoiceassistant

import java.text.SimpleDateFormat
import java.util.*

/**
 * Represents a single transcription attempt with all debug info
 */
data class TranscriptionLog(
    val timestamp: Long = System.currentTimeMillis(),
    val originalTranscript: String,
    val fuzzifiedTranscript: String,
    val transcriptionTimeMs: Long,
    val matchedContact: String?,
    val confidence: Double?,
    val confidenceBreakdown: String?
) {
    fun toDisplayString(): String {
        val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val timeStr = dateFormat.format(Date(timestamp))
        
        return buildString {
            appendLine("[$timeStr]")
            appendLine("Original: \"$originalTranscript\"")
            appendLine("Fuzzified: \"$fuzzifiedTranscript\"")
            appendLine("Time: ${transcriptionTimeMs}ms")
            if (matchedContact != null && confidence != null) {
                appendLine("Match: $matchedContact (confidence: ${String.format("%.2f", confidence)})")
                if (confidenceBreakdown != null) {
                    appendLine("Breakdown: $confidenceBreakdown")
                }
            } else {
                appendLine("Match: NO MATCH FOUND")
            }
            appendLine("---")
        }
    }
}
