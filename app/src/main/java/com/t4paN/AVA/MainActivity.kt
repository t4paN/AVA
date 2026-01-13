//MainActivity.kt

package com.t4paN.AVA

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import com.google.android.material.snackbar.Snackbar
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import android.view.Menu
import android.view.MenuItem
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.t4paN.AVA.databinding.ActivityMainBinding
import android.content.IntentFilter
import android.app.AlertDialog
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ScrollView


class MainActivity : AppCompatActivity() {

    private lateinit var appBarConfiguration: AppBarConfiguration
    private lateinit var binding: ActivityMainBinding

    private val permissions = arrayOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.CALL_PHONE,
        Manifest.permission.READ_CONTACTS
    )

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.all { it.value }
        if (allGranted) {
            preloadWhisper()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)

        val navController = findNavController(R.id.nav_host_fragment_content_main)
        appBarConfiguration = AppBarConfiguration(navController.graph)
        setupActionBarWithNavController(navController, appBarConfiguration)

        binding.fab.setOnClickListener { view ->
            // Send broadcast to refresh contacts (will take effect after restart)
            sendBroadcast(Intent("REFRESH_CONTACTS"))

            // Nuclear reset - kills process, restarts clean
            val nukeIntent = Intent(this, RecordingService::class.java).apply {
                action = RecordingService.ACTION_NUKE_APP
            }
            startService(nukeIntent)

            Snackbar.make(view, "Resetting...", Snackbar.LENGTH_SHORT).show()
        }

        requestPermissionsIfNeeded()

        // Notification permission (Android 13+)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(
                    arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                    1001
                )
            }
        }

        // Register unlock receiver dynamically
        val unlockReceiver = UnlockReceiver()
        val filter = IntentFilter(Intent.ACTION_USER_PRESENT)
        registerReceiver(unlockReceiver, filter)
    }

    private fun requestPermissionsIfNeeded() {
        val notGranted = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (notGranted.isNotEmpty()) {
            permissionLauncher.launch(notGranted.toTypedArray())
        } else {
            preloadWhisper()
        }
    }

    private fun showStationManagerDialog() {
        val stations = RadioStations.getAll(this).toMutableList()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 16)
        }

        val scrollView = ScrollView(this)
        val listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        fun refreshList() {
            listContainer.removeAllViews()
            val currentStations = RadioStations.getAll(this)

            if (currentStations.isEmpty()) {
                listContainer.addView(TextView(this).apply {
                    text = "Δεν υπάρχουν σταθμοί"
                    setPadding(0, 16, 0, 16)
                })
            } else {
                currentStations.forEachIndexed { index, station ->
                    val row = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding(0, 8, 0, 8)
                    }

                    val label = TextView(this).apply {
                        text = "${station.displayName}\n${station.streamUrl}"
                        textSize = 14f
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    }

                    val deleteBtn = TextView(this).apply {
                        text = "  ✕  "
                        textSize = 20f
                        setTextColor(0xFFCC0000.toInt())
                        setOnClickListener {
                            RadioStations.removeStation(this@MainActivity, index)
                            refreshList()
                        }
                    }

                    row.addView(label)
                    row.addView(deleteBtn)
                    listContainer.addView(row)
                }
            }
        }

        refreshList()
        scrollView.addView(listContainer)
        container.addView(scrollView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            400
        ))

        // Add station input
        val inputLabel = TextView(this).apply {
            text = "\nΠροσθήκη σταθμού (Όνομα, URL):"
            setPadding(0, 24, 0, 8)
        }
        container.addView(inputLabel)

        val input = EditText(this).apply {
            hint = "π.χ. Σκάι, http://netradio.live24.gr/skai1003"
            setSingleLine(true)
        }
        container.addView(input)

        AlertDialog.Builder(this)
            .setTitle("Σταθμοί Ραδιοφώνου")
            .setView(container)
            .setPositiveButton("Προσθήκη") { _, _ ->
                val text = input.text.toString()
                val parts = text.split(",", limit = 2)
                if (parts.size == 2) {
                    val success = RadioStations.addStation(this, parts[0], parts[1])
                    if (success) {
                        Snackbar.make(binding.root, "Προστέθηκε: ${parts[0].trim()}", Snackbar.LENGTH_SHORT).show()
                    } else {
                        Snackbar.make(binding.root, "Λάθος μορφή - χρησιμοποιήστε: Όνομα, URL", Snackbar.LENGTH_LONG).show()
                    }
                } else {
                    Snackbar.make(binding.root, "Λάθος μορφή - χρησιμοποιήστε: Όνομα, URL", Snackbar.LENGTH_LONG).show()
                }
            }
            .setNeutralButton("Επαναφορά") { _, _ ->
                RadioStations.resetToDefaults(this)
                Snackbar.make(binding.root, "Επαναφορά στους αρχικούς σταθμούς", Snackbar.LENGTH_SHORT).show()
            }
            .setNegativeButton("Κλείσιμο", null)
            .show()
    }

    private fun preloadWhisper() {
        Thread {
            val intent = Intent(this, RecordingService::class.java)
            intent.action = "PRELOAD_WHISPER"
            startService(intent)
        }.start()
    }

    private fun restartWhisperEngine() {
        // Tell RecordingService to reinitialize Whisper with new model
        val intent = Intent(this, RecordingService::class.java)
        intent.action = "RELOAD_WHISPER"
        startService(intent)
    }
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val prefs = getSharedPreferences("ava_settings", MODE_PRIVATE)

        // Update "Start on unlock" menu item
        val unlockEnabled = prefs.getBoolean("start_on_unlock", false)
        val unlockItem = menu.findItem(R.id.action_toggle_unlock)
        unlockItem.title = if (unlockEnabled) "Start on unlock: ON" else "Start on unlock: OFF"
        unlockItem.isChecked = unlockEnabled

        // Update "Fast mode" menu item
        val fastModeEnabled = ModelManager.isFastModeEnabled(this)
        val fastModeItem = menu.findItem(R.id.action_toggle_fastmode)
        fastModeItem.title = if (fastModeEnabled) "Fast mode: ON" else "Fast mode: OFF"
        fastModeItem.isChecked = fastModeEnabled

        // Update "Auto-call" menu item
        val autoCallEnabled = prefs.getBoolean("auto_call_enabled", true)
        val autoCallItem = menu.findItem(R.id.action_toggle_autocall)
        autoCallItem.title = if (autoCallEnabled) "Auto-call: ON" else "Auto-call: OFF"
        autoCallItem.isChecked = autoCallEnabled

        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_toggle_unlock -> {
                val prefs = getSharedPreferences("ava_settings", MODE_PRIVATE)
                val currentlyEnabled = prefs.getBoolean("start_on_unlock", false)
                val newValue = !currentlyEnabled
                prefs.edit().putBoolean("start_on_unlock", newValue).apply()
                invalidateOptionsMenu()
                Snackbar.make(binding.root,
                    if (newValue) "AVA will start on unlock" else "Unlock start disabled",
                    Snackbar.LENGTH_SHORT).show()
                true
            }
            R.id.action_toggle_fastmode -> {
                val currentlyEnabled = ModelManager.isFastModeEnabled(this)

                if (currentlyEnabled) {
                    // Turning OFF fast mode - need to download small model if not present
                    if (ModelManager.isSmallModelDownloaded(this)) {
                        // Already downloaded, just toggle
                        ModelManager.setFastModeEnabled(this, false)
                        invalidateOptionsMenu()
                        Snackbar.make(binding.root, "Accurate mode enabled", Snackbar.LENGTH_SHORT).show()
                        restartWhisperEngine()
                    } else {
                        // Need to download
                        ModelManager.downloadSmallModel(this,
                            onComplete = {
                                ModelManager.setFastModeEnabled(this, false)
                                invalidateOptionsMenu()
                                Snackbar.make(binding.root, "Model downloaded! Restarting...", Snackbar.LENGTH_SHORT).show()
                                restartWhisperEngine()
                            },
                            onError = { error ->
                                Snackbar.make(binding.root, "Download failed: $error", Snackbar.LENGTH_LONG).show()
                            }
                        )
                    }
                } else {
                    // Turning ON fast mode - just toggle, no download needed
                    ModelManager.setFastModeEnabled(this, true)
                    invalidateOptionsMenu()
                    Snackbar.make(binding.root, "Fast mode enabled", Snackbar.LENGTH_SHORT).show()
                    restartWhisperEngine()
                }
                true
            }

            R.id.action_toggle_autocall -> {
                val prefs = getSharedPreferences("ava_settings", MODE_PRIVATE)
                val currentlyEnabled = prefs.getBoolean("auto_call_enabled", true)
                val newValue = !currentlyEnabled
                prefs.edit().putBoolean("auto_call_enabled", newValue).apply()
                invalidateOptionsMenu()
                Snackbar.make(binding.root,
                    if (newValue) "Auto-call enabled" else "Auto-call disabled (show confirmation)",
                    Snackbar.LENGTH_LONG).show()
                true
            }

            R.id.action_manage_stations -> {
                showStationManagerDialog()
                true
            }

            R.id.action_settings -> true
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        val navController = findNavController(R.id.nav_host_fragment_content_main)
        return navController.navigateUp(appBarConfiguration)
                || super.onSupportNavigateUp()
    }
}