package com.t4paN.AVA

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class UnlockReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "UnlockReceiver"
        private const val PREFS_NAME = "ava_settings"
        private const val KEY_UNLOCK_ENABLED = "start_on_unlock"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "UnlockReceiver triggered! Action: ${intent.action}")

        if (intent.action == Intent.ACTION_USER_PRESENT) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val enabled = prefs.getBoolean(KEY_UNLOCK_ENABLED, false)

            Log.i(TAG, "Unlock detected, enabled=$enabled")

            if (enabled) {
                Log.i(TAG, "Starting AVA service...")
                val serviceIntent = Intent(context, RecordingService::class.java)
                context.startForegroundService(serviceIntent)
            }
        }
    }
}