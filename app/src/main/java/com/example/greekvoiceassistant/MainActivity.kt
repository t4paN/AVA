//MainActivity.kt
package com.example.greekvoiceassistant

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
import com.example.greekvoiceassistant.databinding.ActivityMainBinding
import android.content.IntentFilter

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

        // Register unlock receiver dynamically (must be outside the if block!)
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

    private fun preloadWhisper() {
        Thread {
            val intent = Intent(this, RecordingService::class.java)
            intent.action = "PRELOAD_WHISPER"
            startService(intent)
        }.start()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val prefs = getSharedPreferences("ava_settings", MODE_PRIVATE)
        val enabled = prefs.getBoolean("start_on_unlock", false)
        val item = menu.findItem(R.id.action_toggle_unlock)
        item.title = if (enabled) "Start on unlock: ON" else "Start on unlock: OFF"
        item.isChecked = enabled
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