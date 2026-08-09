package com.t4paN.AVA

import android.app.AlertDialog
import android.content.Context
import android.util.Log
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
 * Fast mode ON (default): Uses whisper-base
 * Fast mode OFF: Uses whisper-small, downloads if not present
 *
 * The base model is bundled in assets when a build supplies it, but *.tflite is
 * git-ignored and there is no app/src/main/assets in the repo, so CI-built and
 * distributed APKs ship without it. Those fall back to downloading base from the
 * same HuggingFace repo the small model already comes from. Assets are always
 * preferred, so builds that do bundle the model behave exactly as before and
 * never hit the network.
 */
object ModelManager {
    private const val TAG = "ModelManager"

    // Model filenames
    private const val MODEL_BASE = "whisper-base.TOP_WORLD.tflite"
    private const val MODEL_SMALL = "whisper-small.TOP_WORLD.tflite"

    // HuggingFace URLs (DocWolle's repo)
    private const val MODEL_BASE_URL =
        "https://huggingface.co/DocWolle/whisper_tflite_models/resolve/main/whisper-base.TOP_WORLD.tflite"
    private const val MODEL_SMALL_URL =
        "https://huggingface.co/DocWolle/whisper_tflite_models/resolve/main/whisper-small.TOP_WORLD.tflite"

    // Sanity thresholds for a complete download (actual sizes: base ~102MB, small ~293MB)
    private const val MIN_BASE_BYTES = 50_000_000L
    private const val MIN_SMALL_BYTES = 100_000_000L

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
        val exists = modelFile.exists() && modelFile.length() > MIN_SMALL_BYTES
        Log.d(TAG, "Small model exists: $exists (${modelFile.length()} bytes)")
        return exists
    }

    /** True if this build bundles the base model in assets (no download needed). */
    fun isBaseModelInAssets(context: Context): Boolean {
        return try {
            context.assets.open(MODEL_BASE).close()
            true
        } catch (e: Exception) {
            false
        }
    }

    /** True if the base model was previously downloaded to internal storage. */
    fun isBaseModelDownloaded(context: Context): Boolean {
        val modelFile = File(context.filesDir, MODEL_BASE)
        return modelFile.exists() && modelFile.length() > MIN_BASE_BYTES
    }

    /**
     * True if the base model can be loaded at all — bundled in assets, or already
     * downloaded. When this is false, transcription cannot work offline and the
     * caregiver needs to run downloadBaseModel() once.
     */
    fun isBaseModelReady(context: Context): Boolean =
        isBaseModelInAssets(context) || isBaseModelDownloaded(context)

    /**
     * Get the appropriate model path based on settings.
     *
     * Returns null when the required model is not available at all, so callers can
     * report it instead of failing silently. Previously this threw from
     * assets.open() when the model was missing, which was swallowed upstream and
     * left the user with no feedback whatsoever.
     */
    fun getModelPath(context: Context): String? {
        val useSmall = !isFastModeEnabled(context) && isSmallModelDownloaded(context)

        if (useSmall) {
            Log.d(TAG, "Using whisper-small model")
            return File(context.filesDir, MODEL_SMALL).absolutePath
        }

        Log.d(TAG, "Using whisper-base model")
        val baseFile = File(context.filesDir, MODEL_BASE)

        // Already extracted or downloaded.
        if (baseFile.exists() && baseFile.length() > MIN_BASE_BYTES) {
            return baseFile.absolutePath
        }

        // Prefer the bundled copy: builds that ship the model never touch the network.
        if (isBaseModelInAssets(context)) {
            Log.d(TAG, "Copying base model from assets...")
            return try {
                context.assets.open(MODEL_BASE).use { input ->
                    FileOutputStream(baseFile).use { output ->
                        input.copyTo(output)
                    }
                }
                baseFile.absolutePath
            } catch (e: Exception) {
                Log.e(TAG, "Failed to copy base model from assets", e)
                baseFile.delete()
                null
            }
        }

        Log.e(TAG, "Base model unavailable: not in assets and not downloaded")
        return null
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
     * Download the whisper-small model (accurate mode) with progress dialog.
     *
     * @param context Activity context (needed for dialog)
     * @param onComplete Called when download finishes successfully
     * @param onError Called if download fails
     */
    fun downloadSmallModel(
        context: Context,
        onComplete: () -> Unit,
        onError: (String) -> Unit
    ) = downloadModel(
        context, MODEL_SMALL_URL, MODEL_SMALL, MIN_SMALL_BYTES,
        "Λήψη ακριβούς μοντέλου", onComplete, onError
    )

    /**
     * Download the whisper-base model — the default engine. Only needed on builds
     * that don't bundle it in assets (CI / Play Store builds). Check
     * isBaseModelReady() first; without this the app cannot transcribe offline.
     */
    fun downloadBaseModel(
        context: Context,
        onComplete: () -> Unit,
        onError: (String) -> Unit
    ) = downloadModel(
        context, MODEL_BASE_URL, MODEL_BASE, MIN_BASE_BYTES,
        "Λήψη μοντέλου ομιλίας", onComplete, onError
    )

    /**
     * Shared download implementation for both models: progress dialog, cancellable,
     * writes to a .tmp file and only promotes it once the size looks sane.
     */
    private fun downloadModel(
        context: Context,
        modelUrl: String,
        fileName: String,
        minBytes: Long,
        title: String,
        onComplete: () -> Unit,
        onError: (String) -> Unit
    ) {
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
        
        // Declared before the dialog so the Cancel button can stop the transfer.
        // Previously cancelling only dismissed the dialog and reported an error
        // while the download kept running in the background.
        var job: Job? = null

        val dialog = AlertDialog.Builder(context)
            .setTitle(title)
            .setView(layout)
            .setCancelable(false)
            .setNegativeButton("Ακύρωση") { d, _ ->
                job?.cancel()
                d.dismiss()
                onError("Ακυρώθηκε")
            }
            .create()

        dialog.show()

        // Download in background
        job = CoroutineScope(Dispatchers.IO).launch {
            val outputFile = File(context.filesDir, fileName)
            val tempFile = File(context.filesDir, "$fileName.tmp")
            try {
                Log.d(TAG, "Starting download from: $modelUrl")

                val url = URL(modelUrl)
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 15000
                connection.readTimeout = 30000
                connection.connect()

                // Without this an error page (404/redirect to HTML) would be written
                // to disk and later loaded as if it were a model.
                if (connection.responseCode !in 200..299) {
                    throw java.io.IOException("HTTP ${connection.responseCode}")
                }

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
                
                // A truncated transfer that still closed cleanly would otherwise be
                // promoted and then fail deep inside the TFLite loader.
                if (tempFile.length() < minBytes) {
                    throw java.io.IOException(
                        "Incomplete download: ${tempFile.length()} bytes, expected at least $minBytes"
                    )
                }

                outputFile.delete()
                if (!tempFile.renameTo(outputFile)) {
                    throw java.io.IOException("Could not move model into place")
                }
                Log.d(TAG, "Download complete: ${outputFile.length()} bytes")

                withContext(Dispatchers.Main) {
                    dialog.dismiss()
                    onComplete()
                }

            } catch (e: CancellationException) {
                // User pressed Ακύρωση — the dialog and onError are already handled
                // by the button; just don't leave a partial file behind.
                tempFile.delete()
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Download failed", e)
                tempFile.delete()
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
