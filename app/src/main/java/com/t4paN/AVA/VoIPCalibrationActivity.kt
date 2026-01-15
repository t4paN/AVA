// VoIPCalibrationActivity.kt

package com.t4paN.AVA

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.FileOutputStream

/**
 * VoIPCalibrationActivity - Caregiver workflow for calibrating VoIP app button positions
 * 
 * Simplified flow (no MediaProjection):
 * 1. Show list of available VoIP apps
 * 2. Caregiver selects app
 * 3. Caregiver picks screenshot from gallery (taken manually beforehand)
 * 4. Show desaturated screenshot, caregiver taps button position
 * 5. Adjust wait time, save
 * 
 * Supports recalibration by overwriting existing config.
 */
class VoIPCalibrationActivity : AppCompatActivity() {
    
    companion object {
        private const val TAG = "VoIPCalibration"
        private const val SCREENSHOT_DIR = "voip_screenshots"
    }
    
    // UI State
    private enum class State {
        APP_SELECTION,      // Showing app list
        POSITION_SELECTION  // Screenshot loaded, waiting for tap position
    }
    
    private var currentState = State.APP_SELECTION
    private var selectedApp: VoIPAppConfig? = null
    private var capturedBitmap: Bitmap? = null
    private var selectedX: Float = -1f
    private var selectedY: Float = -1f
    private var waitTimeSeconds: Int = 3
    
    // Views
    private lateinit var appListContainer: LinearLayout
    private lateinit var appListView: ListView
    private lateinit var calibrationContainer: LinearLayout
    private lateinit var screenshotImageView: ImageView
    private lateinit var purpleDotView: View
    private lateinit var instructionText: TextView
    private lateinit var waitTimeInput: EditText
    private lateinit var waitTimeMinus: Button
    private lateinit var waitTimePlus: Button
    private lateinit var saveButton: Button
    private lateinit var cancelButton: Button
    private lateinit var pickScreenshotButton: Button
    
    // Photo picker launcher
    private val photoPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            loadScreenshotFromUri(uri)
        } else {
            Toast.makeText(this, "Δεν επιλέχθηκε εικόνα", Toast.LENGTH_SHORT).show()
        }
    }
    
    // ==================== Lifecycle ====================
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_voip_calibration)
        
        initViews()
        loadAvailableApps()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        capturedBitmap?.recycle()
    }
    
    override fun onBackPressed() {
        when (currentState) {
            State.POSITION_SELECTION -> {
                showAppSelection()
            }
            else -> super.onBackPressed()
        }
    }
    
    // ==================== View Setup ====================
    
    private fun initViews() {
        appListContainer = findViewById(R.id.appListContainer)
        appListView = findViewById(R.id.appListView)
        calibrationContainer = findViewById(R.id.calibrationContainer)
        screenshotImageView = findViewById(R.id.screenshotImageView)
        purpleDotView = findViewById(R.id.purpleDotView)
        instructionText = findViewById(R.id.instructionText)
        waitTimeInput = findViewById(R.id.waitTimeInput)
        waitTimeMinus = findViewById(R.id.waitTimeMinus)
        waitTimePlus = findViewById(R.id.waitTimePlus)
        saveButton = findViewById(R.id.saveButton)
        cancelButton = findViewById(R.id.cancelButton)
        pickScreenshotButton = findViewById(R.id.pickScreenshotButton)
        
        // Wait time controls
        waitTimeInput.setText(waitTimeSeconds.toString())
        
        waitTimeMinus.setOnClickListener {
            if (waitTimeSeconds > 1) {
                waitTimeSeconds--
                waitTimeInput.setText(waitTimeSeconds.toString())
            }
        }
        
        waitTimePlus.setOnClickListener {
            if (waitTimeSeconds < 10) {
                waitTimeSeconds++
                waitTimeInput.setText(waitTimeSeconds.toString())
            }
        }
        
        // Screenshot tap handler
        screenshotImageView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN && currentState == State.POSITION_SELECTION) {
                handleScreenshotTap(event.x, event.y)
                true
            } else false
        }
        
        // Pick screenshot button
        pickScreenshotButton.setOnClickListener {
            photoPickerLauncher.launch("image/*")
        }
        
        saveButton.setOnClickListener { saveCalibration() }
        cancelButton.setOnClickListener { 
            if (currentState == State.POSITION_SELECTION) {
                showAppSelection()
            } else {
                finish()
            }
        }
    }
    
    // ==================== App Selection ====================
    
    private fun loadAvailableApps() {
        val apps = VoIPAppRegistry.getAvailableApps(this)
        
        if (apps.isEmpty()) {
            Toast.makeText(this, R.string.voip_calibration_no_apps, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        
        val adapter = VoIPAppAdapter(this, apps)
        appListView.adapter = adapter
        
        appListView.setOnItemClickListener { _, _, position, _ ->
            val app = apps[position]
            
            // Check if already configured
            if (VoIPAppRegistry.isCalibrated(this, app.packageName)) {
                AlertDialog.Builder(this)
                    .setTitle(app.displayName)
                    .setMessage(R.string.voip_calibration_reconfigure)
                    .setPositiveButton(android.R.string.yes) { _, _ ->
                        selectApp(app)
                    }
                    .setNegativeButton(android.R.string.no, null)
                    .show()
            } else {
                selectApp(app)
            }
        }
        
        showAppSelection()
    }
    
    private fun showAppSelection() {
        currentState = State.APP_SELECTION
        appListContainer.visibility = View.VISIBLE
        calibrationContainer.visibility = View.GONE
        purpleDotView.visibility = View.GONE
        selectedX = -1f
        selectedY = -1f
        capturedBitmap?.recycle()
        capturedBitmap = null
    }
    
    private fun selectApp(app: VoIPAppConfig) {
        selectedApp = app
        Log.i(TAG, "Selected app: ${app.displayName}")
        
        // Show instructions dialog then open photo picker
        AlertDialog.Builder(this)
            .setTitle("Οδηγίες")
            .setMessage("1. Ανοίξτε το ${app.displayName}\n2. Πηγαίνετε στην οθόνη κλήσης μιας επαφής\n3. Τραβήξτε screenshot (Power + Volume Down)\n4. Επιστρέψτε εδώ και επιλέξτε το screenshot")
            .setPositiveButton("Επιλογή Screenshot") { _, _ ->
                photoPickerLauncher.launch("image/*")
            }
            .setNegativeButton("Ακύρωση", null)
            .show()
    }
    
    // ==================== Screenshot Loading ====================
    
    private fun loadScreenshotFromUri(uri: Uri) {
        try {
            val inputStream = contentResolver.openInputStream(uri)
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream?.close()
            
            if (bitmap != null) {
                capturedBitmap?.recycle()
                capturedBitmap = bitmap
                showPositionSelection()
            } else {
                Toast.makeText(this, "Αποτυχία φόρτωσης εικόνας", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading screenshot", e)
            Toast.makeText(this, "Σφάλμα: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    // ==================== Position Selection ====================
    
    private fun showPositionSelection() {
        currentState = State.POSITION_SELECTION
        appListContainer.visibility = View.GONE
        calibrationContainer.visibility = View.VISIBLE
        purpleDotView.visibility = View.GONE
        
        // Apply desaturation filter to screenshot
        capturedBitmap?.let { bitmap ->
            val desaturated = desaturateBitmap(bitmap)
            screenshotImageView.setImageBitmap(desaturated)
        }
        
        instructionText.setText(R.string.voip_calibration_tap_instruction)
        saveButton.isEnabled = false
    }
    
    private fun desaturateBitmap(source: Bitmap): Bitmap {
        val result = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint()
        
        // Desaturate to ~30% color
        val colorMatrix = ColorMatrix().apply {
            setSaturation(0.3f)
        }
        paint.colorFilter = ColorMatrixColorFilter(colorMatrix)
        
        canvas.drawBitmap(source, 0f, 0f, paint)
        return result
    }
    
    private fun handleScreenshotTap(viewX: Float, viewY: Float) {
        val bitmap = capturedBitmap ?: return
        
        // Since screenshot is displayed at actual size (scaleType=matrix), 
        // tap coordinates directly map to bitmap coordinates
        val bitmapWidth = bitmap.width.toFloat()
        val bitmapHeight = bitmap.height.toFloat()
        
        // Convert to relative (0.0-1.0)
        selectedX = (viewX / bitmapWidth).coerceIn(0f, 1f)
        selectedY = (viewY / bitmapHeight).coerceIn(0f, 1f)
        
        Log.d(TAG, "Position selected: ($selectedX, $selectedY) from tap ($viewX, $viewY) on ${bitmapWidth}x${bitmapHeight}")
        
        // Show purple dot at tap location
        showPurpleDot(viewX, viewY)
        
        saveButton.isEnabled = true
    }
    
    private fun showPurpleDot(x: Float, y: Float) {
        purpleDotView.visibility = View.VISIBLE
        
        // Position dot centered on tap point (relative to ImageView)
        val dotSize = (40 * resources.displayMetrics.density).toInt()
        
        purpleDotView.x = x - dotSize / 2
        purpleDotView.y = y - dotSize / 2
    }
    
    // ==================== Save ====================
    
    private fun saveCalibration() {
        val app = selectedApp ?: return
        
        if (selectedX < 0 || selectedY < 0) {
            Toast.makeText(this, "Πατήστε στο κουμπί κλήσης πρώτα", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Parse wait time
        waitTimeSeconds = waitTimeInput.text.toString().toIntOrNull() ?: 3
        waitTimeSeconds = waitTimeSeconds.coerceIn(1, 10)
        
        // Save screenshot to internal storage
        val screenshotPath = saveScreenshotToFile(app.packageName)
        
        // Create updated config
        val config = VoIPAppConfig(
            packageName = app.packageName,
            displayName = app.displayName,
            deepLinkScheme = app.deepLinkScheme,
            clickX = selectedX,
            clickY = selectedY,
            waitTimeMs = waitTimeSeconds * 1000L,
            screenshotPath = screenshotPath
        )
        
        VoIPAppRegistry.saveConfig(this, config)
        
        Log.i(TAG, "Saved calibration for ${app.displayName}: ($selectedX, $selectedY), wait=${waitTimeSeconds}s")
        
        Toast.makeText(this, R.string.voip_calibration_success, Toast.LENGTH_SHORT).show()
        finish()
    }
    
    private fun saveScreenshotToFile(packageName: String): String? {
        val bitmap = capturedBitmap ?: return null
        
        return try {
            val dir = File(filesDir, SCREENSHOT_DIR)
            if (!dir.exists()) dir.mkdirs()
            
            val file = File(dir, "${packageName.replace(".", "_")}.png")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
            }
            
            file.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save screenshot", e)
            null
        }
    }
    
    // ==================== App List Adapter ====================
    
    private inner class VoIPAppAdapter(
        context: Context,
        private val apps: List<VoIPAppConfig>
    ) : ArrayAdapter<VoIPAppConfig>(context, R.layout.item_voip_app, apps) {
        
        override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
            val view = convertView ?: layoutInflater.inflate(R.layout.item_voip_app, parent, false)
            
            val app = apps[position]
            val nameText = view.findViewById<TextView>(R.id.appNameText)
            val statusText = view.findViewById<TextView>(R.id.appStatusText)
            val iconView = view.findViewById<ImageView>(R.id.appIconView)
            
            nameText.text = app.displayName
            
            // Show calibration status
            val isCalibrated = VoIPAppRegistry.isCalibrated(context, app.packageName)
            statusText.text = if (isCalibrated) {
                getString(R.string.voip_calibration_configured)
            } else {
                getString(R.string.voip_calibration_not_configured)
            }
            statusText.setTextColor(
                if (isCalibrated) 0xFF4CAF50.toInt() else 0xFF9E9E9E.toInt()
            )
            
            // Load app icon
            try {
                val appIcon = packageManager.getApplicationIcon(app.packageName)
                iconView.setImageDrawable(appIcon)
            } catch (e: Exception) {
                iconView.setImageResource(android.R.drawable.sym_def_app_icon)
            }
            
            return view
        }
    }
}
