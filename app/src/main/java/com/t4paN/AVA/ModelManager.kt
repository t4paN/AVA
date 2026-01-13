package com.t4paN.AVA

import android.app.AlertDialog
import android.content.Context
import android.util.Log
import android.view.LayoutInflater
import android.widget.ProgressBar
import android.widget.TextView
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Manages Whisper model selection and downloading.
 * 
 * Fast mode ON (default): Uses bundled whisper-base from assets
 * Fast mode OFF: Uses whisper-small, downloads if not present
 */
object ModelManager {
    private const val TAG = "ModelManager"
    
    // Model filenames
    private const val MODEL_BASE = "whisper-base.TOP_WORLD.tflite"
    private const val MODEL_SMALL = "whisper-small.TOP_WORLD.tflite"
    
    // HuggingFace URL for whisper-small (DocWolle's repo)
    private const val MODEL_SMALL_URL = 
        "https://huggingface.co/DocWolle/whisper_tflite_models/resolve/main/whisper-small.TOP_WORLD.tflite"
    
    // SharedPrefs keys
    private const val PREFS_NAME = "ava_settings"
    private const val KEY_FAST_MODE = "fast_mode_enabled"
    
    /**
     * Check if fast mode is enabled (default: true)
     */
    fun isFastModeEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_FAST_MODE, true)
    }
    
    /**
     * Set fast mode on/off
     */
    fun setFastModeEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_FAST_MODE, enabled).apply()
        Log.d(TAG, "Fast mode set to: $enabled")
    }
    
    /**
     * Check if whisper-small model is downloaded
     */
    fun isSmallModelDownloaded(context: Context): Boolean {
        val modelFile = File(context.filesDir, MODEL_SMALL)
        val exists = modelFile.exists() && modelFile.length() > 100_000_000 // >100MB sanity check
        Log.d(TAG, "Small model exists: $exists (${modelFile.length()} bytes)")
        return exists
    }
    
    /**
     * Get the appropriate model path based on settings.
     * 
     * For base model: copies from assets if needed, returns path
     * For small model: returns path (caller should ensure it's downloaded first)
     */
    fun getModelPath(context: Context): String {
        val useSmall = !isFastModeEnabled(context) && isSmallModelDownloaded(context)
        
        return if (useSmall) {
            Log.d(TAG, "Using whisper-small model")
            File(context.filesDir, MODEL_SMALL).absolutePath
        } else {
            Log.d(TAG, "Using whisper-base model")
            // Copy base model from assets if not already done
            val baseFile = File(context.filesDir, MODEL_BASE)
            if (!baseFile.exists()) {
                Log.d(TAG, "Copying base model from assets...")
                context.assets.open(MODEL_BASE).use { input ->
                    FileOutputStream(baseFile).use { output ->
                        input.copyTo(output)
                    }
                }
            }
            baseFile.absolutePath
        }
    }
    
    /**
     * Get the vocab path (same for both models)
     */
    fun getVocabPath(context: Context): String {
        val vocabFile = File(context.filesDir, "filters_vocab_multilingual.bin")
        if (!vocabFile.exists()) {
            Log.d(TAG, "Copying vocab from assets...")
            context.assets.open("filters_vocab_multilingual.bin").use { input ->
                FileOutputStream(vocabFile).use { output ->
                    input.copyTo(output)
                }
            }
        }
        return vocabFile.absolutePath
    }
    
    /**
     * Download the whisper-small model with progress dialog.
     * 
     * @param context Activity context (needed for dialog)
     * @param onComplete Called when download finishes successfully
     * @param onError Called if download fails
     */
    fun downloadSmallModel(
        context: Context,
        onComplete: () -> Unit,
        onError: (String) -> Unit
    ) {
        // Create progress dialog
        val dialogView = LayoutInflater.from(context).inflate(
            android.R.layout.simple_list_item_2, null
        )
        
        // Build custom dialog with progress bar
        val progressBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal)
        progressBar.max = 100
        progressBar.progress = 0
        
        val textView = TextView(context)
        textView.text = "Λήψη μοντέλου... 0%"
        textView.setPadding(48, 32, 48, 16)
        
        progressBar.setPadding(48, 0, 48, 32)
        
        val layout = android.widget.LinearLayout(context)
        layout.orientation = android.widget.LinearLayout.VERTICAL
        layout.addView(textView)
        layout.addView(progressBar)
        
        val dialog = AlertDialog.Builder(context)
            .setTitle("Λήψη ακριβούς μοντέλου")
            .setView(layout)
            .setCancelable(false)
            .setNegativeButton("Ακύρωση") { d, _ ->
                d.dismiss()
                onError("Ακυρώθηκε")
            }
            .create()
        
        dialog.show()
        
        // Download in background
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val outputFile = File(context.filesDir, MODEL_SMALL)
                val tempFile = File(context.filesDir, "${MODEL_SMALL}.tmp")
                
                Log.d(TAG, "Starting download from: $MODEL_SMALL_URL")
                
                val url = URL(MODEL_SMALL_URL)
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 15000
                connection.readTimeout = 30000
                connection.connect()
                
                val totalBytes = connection.contentLength.toLong()
                Log.d(TAG, "Total size: $totalBytes bytes (${totalBytes / 1024 / 1024} MB)")
                
                var downloadedBytes = 0L
                val buffer = ByteArray(8192)
                
                connection.inputStream.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            downloadedBytes += bytesRead
                            
                            val progress = if (totalBytes > 0) {
                                (downloadedBytes * 100 / totalBytes).toInt()
                            } else {
                                -1
                            }
                            
                            withContext(Dispatchers.Main) {
                                if (progress >= 0) {
                                    progressBar.progress = progress
                                    textView.text = "Λήψη μοντέλου... $progress%"
                                } else {
                                    textView.text = "Λήψη... ${downloadedBytes / 1024 / 1024} MB"
                                }
                            }
                        }
                    }
                }
                
                // Rename temp to final
                tempFile.renameTo(outputFile)
                Log.d(TAG, "Download complete: ${outputFile.length()} bytes")
                
                withContext(Dispatchers.Main) {
                    dialog.dismiss()
                    onComplete()
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Download failed", e)
                withContext(Dispatchers.Main) {
                    dialog.dismiss()
                    onError(e.message ?: "Unknown error")
                }
            }
        }
    }
    
    /**
     * Delete the downloaded small model to free space
     */
    fun deleteSmallModel(context: Context): Boolean {
        val modelFile = File(context.filesDir, MODEL_SMALL)
        val deleted = modelFile.delete()
        Log.d(TAG, "Deleted small model: $deleted")
        return deleted
    }
    
    /**
     * Get size of downloaded small model in MB (for display)
     */
    fun getSmallModelSizeMB(context: Context): Long {
        val modelFile = File(context.filesDir, MODEL_SMALL)
        return if (modelFile.exists()) modelFile.length() / 1024 / 1024 else 0
    }
}
