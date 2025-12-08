package com.example.greekvoiceassistant

import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.example.greekvoiceassistant.databinding.FragmentFirstBinding

/**
 * Fragment that displays transcription logs from AVA
 * 
 * Shows a simple scrollable list of all voice recognition attempts
 * with original transcripts, fuzzified versions, timing, and match results
 */
class FirstFragment : Fragment() {

    private var _binding: FragmentFirstBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFirstBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        // Initial load of logs
        updateLogDisplay()
        
        // Register callback to update when new logs arrive
        RecordingService.setLogUpdateCallback {
            updateLogDisplay()
        }
    }
    
    override fun onResume() {
        super.onResume()
        // Refresh logs when returning to this fragment
        updateLogDisplay()
    }

    /**
     * Update the TextView with all transcription logs
     */
    private fun updateLogDisplay() {
        val logs = RecordingService.getTranscriptionLogs()
        
        if (logs.isEmpty()) {
            binding.textviewFirst.text = "No transcription logs yet.\n\nPress the microphone button to start recording."
        } else {
            // Build the display string from all logs
            val displayText = buildString {
                appendLine("=== AVA Transcription Logs ===")
                appendLine()
                for (log in logs) {
                    append(log.toDisplayString())
                    appendLine()
                }
            }
            binding.textviewFirst.text = displayText
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Clear the callback when view is destroyed
        RecordingService.clearLogUpdateCallback()
        _binding = null
    }
}
