//AVAApplication.kt

package com.t4paN.AVA

import android.app.Application
import android.util.Log

/**
 * AVAApplication - Application class for AVA
 *
 * Initializes singletons that need Application context.
 */
class AVAApplication : Application() {

    companion object {
        private const val TAG = "AVAApplication"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "AVAApplication onCreate")

        // Initialize CallOverlayController with application context
        CallOverlayController.init(this)
    }
}
