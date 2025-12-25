package com.t4paN.AVA

import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.t4paN.AVA.databinding.FragmentFirstBinding

/**
 * Fragment that displays transcription logs from AVA
 * 
 * NOW WITH:
 * - Persistent log loading from SharedPreferences
 * - Ambiguous candidate display
 * - "No intent detected" tracking
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
        
        // Load persisted logs on first view creation
        RecordingService.loadPersistedLogs(requireContext())
        
        // Initial display of logs
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
     * 
     * Shows:
     * - Original + fuzzified transcripts
     * - Transcription time
     * - Match results OR ambiguous candidates OR no intent
     */
    private fun updateLogDisplay() {
        val logs = RecordingService.getTranscriptionLogs()
        
        if (logs.isEmpty()) {
            binding.textviewFirst.text = buildString {
                appendLine("=== AVA Transcription Logs ===")
                appendLine()
                appendLine("No transcription logs yet.")
                appendLine()
                appendLine("Press the microphone button to start recording.")
                appendLine()
                appendLine("Logs persist across app restarts!")
            }
        } else {
            // Build the display string from all logs
            val displayText = buildString {
                appendLine("=== AVA Transcription Logs ===")
                appendLine("(${logs.size} total, showing newest first)")
                appendLine()
                
                for (log in logs) {
                    append(log.toDisplayString())
                    appendLine()
                }
                
                appendLine()
                appendLine("--- End of Logs ---")
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
