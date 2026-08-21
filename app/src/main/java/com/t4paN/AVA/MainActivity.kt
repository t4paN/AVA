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
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.READ_CALL_LOG
    )

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        // Keyed on RECORD_AUDIO alone: the others are needed for calling and for
        // reading missed calls, but none of them affect whether Whisper can load,
        // and requiring all of them meant one optional denial silently skipped the
        // preload and made the first recording slow.
        val micGranted = results[Manifest.permission.RECORD_AUDIO] ?: ContextCompat
            .checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (micGranted) {
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

            Snackbar.make(view, "Επαναφορά…", Snackbar.LENGTH_SHORT).show()
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

    /**
     * Offer the ~100MB Whisper download, but only when the user has actually
     * chosen offline recognition and the model is absent.
     *
     * This deliberately does NOT run at startup. Online recognition needs no
     * model at all, so nagging every launch asks most users to fetch 100MB they
     * will never use. If the model really is needed and missing, RecordingService
     * says so out loud at the moment it matters — better for this audience than a
     * dialog they cannot easily read.
     */
    private fun promptForBaseModelIfMissing() {
        if (ModelManager.isBaseModelReady(this)) return

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Απαιτείται μοντέλο ομιλίας")
            .setMessage(
                "Για να λειτουργήσει η αναγνώριση φωνής χωρίς σύνδεση, " +
                "χρειάζεται λήψη ενός αρχείου περίπου 100 MB. " +
                "Συνιστάται σύνδεση Wi-Fi."
            )
            .setPositiveButton("Λήψη τώρα") { _, _ -> downloadBaseModel() }
            .setNegativeButton("Αργότερα", null)
            .show()
    }

    private fun downloadBaseModel() {
        ModelManager.downloadBaseModel(this,
            onComplete = {
                invalidateOptionsMenu()
                Snackbar.make(binding.root, "Το μοντέλο είναι έτοιμο", Snackbar.LENGTH_SHORT).show()
                restartWhisperEngine()
            },
            onError = { error ->
                Snackbar.make(binding.root, "Η λήψη απέτυχε: $error", Snackbar.LENGTH_LONG).show()
            }
        )
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
            hint = "π.χ. Σκάι, https://netradio.live24.gr/skai1003"
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

        // Titles are Greek, and state is ΝΑΙ / ΟΧΙ rather than ON / OFF — both are
        // three characters, so a row does not change length as it is toggled and
        // the ellipsis stops moving when the popup truncates at high zoom.
        val unlockEnabled = prefs.getBoolean("start_on_unlock", false)
        val unlockItem = menu.findItem(R.id.action_toggle_unlock)
        unlockItem.title =
            if (unlockEnabled) "Εκκίνηση με ξεκλείδωμα: ΝΑΙ" else "Εκκίνηση με ξεκλείδωμα: ΟΧΙ"
        unlockItem.isChecked = unlockEnabled

        val fastModeEnabled = ModelManager.isFastModeEnabled(this)
        val fastModeItem = menu.findItem(R.id.action_toggle_fastmode)
        fastModeItem.title =
            if (fastModeEnabled) "Γρήγορη λειτουργία: ΝΑΙ" else "Γρήγορη λειτουργία: ΟΧΙ"
        fastModeItem.isChecked = fastModeEnabled

        val autoCallEnabled = prefs.getBoolean("auto_call_enabled", true)
        val autoCallItem = menu.findItem(R.id.action_toggle_autocall)
        autoCallItem.title =
            if (autoCallEnabled) "Αυτόματη κλήση: ΝΑΙ" else "Αυτόματη κλήση: ΟΧΙ"
        autoCallItem.isChecked = autoCallEnabled

        // Update "Online recognition" menu item
        val onlineEnabled = prefs.getBoolean("online_recognition_enabled", false)
        val onlineItem = menu.findItem(R.id.action_toggle_online)
        // Framed as a choice between two engines rather than an on/off switch —
        // "off" gave no hint that the alternative needs a 100MB download.
        onlineItem.title = if (onlineEnabled) "Αναγνώριση: Google" else "Αναγνώριση: Whisper"
        onlineItem.isChecked = onlineEnabled

        // Only offer the base-model download on builds that need it — hidden
        // entirely when the model is bundled in assets or already fetched.
        menu.findItem(R.id.action_download_model).isVisible = !ModelManager.isBaseModelReady(this)

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
                    if (newValue) "Η AVA θα ξεκινά με το ξεκλείδωμα"
                    else "Η εκκίνηση με το ξεκλείδωμα απενεργοποιήθηκε",
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
                        Snackbar.make(binding.root, "Λειτουργία ακρίβειας ενεργή", Snackbar.LENGTH_SHORT).show()
                        restartWhisperEngine()
                    } else {
                        // Need to download
                        ModelManager.downloadSmallModel(this,
                            onComplete = {
                                ModelManager.setFastModeEnabled(this, false)
                                invalidateOptionsMenu()
                                Snackbar.make(binding.root, "Το μοντέλο κατέβηκε. Επανεκκίνηση…", Snackbar.LENGTH_SHORT).show()
                                restartWhisperEngine()
                            },
                            onError = { error ->
                                Snackbar.make(binding.root, "Η λήψη απέτυχε: $error", Snackbar.LENGTH_LONG).show()
                            }
                        )
                    }
                } else {
                    // Turning ON fast mode - just toggle, no download needed
                    ModelManager.setFastModeEnabled(this, true)
                    invalidateOptionsMenu()
                    Snackbar.make(binding.root, "Γρήγορη λειτουργία ενεργή", Snackbar.LENGTH_SHORT).show()
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
                    if (newValue) "Αυτόματη κλήση ενεργή"
                    else "Αυτόματη κλήση ανενεργή — θα ζητά επιβεβαίωση",
                    Snackbar.LENGTH_LONG).show()
                true
            }

            R.id.action_toggle_online -> {
                val prefs = getSharedPreferences("ava_settings", MODE_PRIVATE)
                val currentlyEnabled = prefs.getBoolean("online_recognition_enabled", false)
                val newValue = !currentlyEnabled
                prefs.edit().putBoolean("online_recognition_enabled", newValue).apply()
                invalidateOptionsMenu()
                Snackbar.make(binding.root,
                    if (newValue) "Αναγνώριση: Google — χρειάζεται σύνδεση, στέλνει τον ήχο στην Google"
                    else "Αναγνώριση: Whisper — στη συσκευή, χωρίς σύνδεση",
                    Snackbar.LENGTH_LONG).show()
                // Switching TO Whisper is the only moment the model actually
                // matters, so that is the only moment worth asking about it.
                if (!newValue) promptForBaseModelIfMissing()
                true
            }

            R.id.action_download_model -> {
                downloadBaseModel()
                true
            }

            R.id.action_manage_stations -> {
                showStationManagerDialog()
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        val navController = findNavController(R.id.nav_host_fragment_content_main)
        return navController.navigateUp(appBarConfiguration)
                || super.onSupportNavigateUp()
    }
}