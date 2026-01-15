// VoIPAppConfig.kt

package com.t4paN.AVA

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import org.json.JSONObject

/**
 * VoIPAppConfig - Data model and registry for VoIP app configurations
 * 
 * Stores:
 * - Known VoIP apps with their deep link schemes
 * - Per-app calibration data (click position, wait time, screenshot path)
 * 
 * Single source of truth for both VoIPAutoClickManager and HapticGuideManager.
 */
data class VoIPAppConfig(
    val packageName: String,
    val displayName: String,
    val deepLinkScheme: String,  // Template with {phone} placeholder
    val clickX: Float = -1f,     // 0.0-1.0 relative position, -1 = not calibrated
    val clickY: Float = -1f,
    val waitTimeMs: Long = 3000, // Time to wait before clicking
    val screenshotPath: String? = null
) {
    val isCalibrated: Boolean
        get() = clickX >= 0f && clickY >= 0f
    
    fun buildDeepLink(phoneNumber: String): String {
        // Strip everything except digits and leading +
        val cleaned = phoneNumber.replace(Regex("[^+\\d]"), "")
        return deepLinkScheme.replace("{phone}", cleaned)
    }
    
    fun toJson(): JSONObject = JSONObject().apply {
        put("packageName", packageName)
        put("displayName", displayName)
        put("deepLinkScheme", deepLinkScheme)
        put("clickX", clickX.toDouble())
        put("clickY", clickY.toDouble())
        put("waitTimeMs", waitTimeMs)
        put("screenshotPath", screenshotPath ?: "")
    }
    
    companion object {
        fun fromJson(json: JSONObject): VoIPAppConfig = VoIPAppConfig(
            packageName = json.getString("packageName"),
            displayName = json.getString("displayName"),
            deepLinkScheme = json.getString("deepLinkScheme"),
            clickX = json.optDouble("clickX", -1.0).toFloat(),
            clickY = json.optDouble("clickY", -1.0).toFloat(),
            waitTimeMs = json.optLong("waitTimeMs", 3000),
            screenshotPath = json.optString("screenshotPath").ifEmpty { null }
        )
    }
}

/**
 * Registry of known VoIP apps with their deep link schemes.
 * 
 * Cross-referenced against installed Communication apps to show
 * only apps that are both installed AND have known deep links.
 */
object VoIPAppRegistry {
    private const val TAG = "VoIPAppRegistry"
    private const val PREFS_NAME = "voip_app_configs"
    
    // Known VoIP apps with deep link templates
    // {phone} placeholder will be replaced with the actual number
    private val knownApps = mapOf(
        "com.viber.voip" to Pair("Viber", "viber://chat?number={phone}"),
        "com.whatsapp" to Pair("WhatsApp", "https://wa.me/{phone}"),
        "org.telegram.messenger" to Pair("Telegram", "tg://resolve?phone={phone}"),
        "com.facebook.orca" to Pair("Messenger", "fb-messenger://user/{phone}"),
        "com.skype.raider" to Pair("Skype", "skype:{phone}?call"),
        "com.google.android.apps.tachyon" to Pair("Google Duo", "https://duo.google.com/call/{phone}"),
        "com.google.android.apps.meetings" to Pair("Google Meet", "https://meet.google.com/lookup/{phone}"),
        "us.zoom.videomeetings" to Pair("Zoom", "zoomus://phone/call?number={phone}"),
        "com.microsoft.teams" to Pair("Microsoft Teams", "msteams://teams.microsoft.com/l/call/0/0?users={phone}"),
        "com.discord" to Pair("Discord", "discord://"),  // Discord doesn't support phone-based calling
        "com.imo.android.imoim" to Pair("imo", "imo://call?phone={phone}"),
        "com.linecorp.JPCM" to Pair("LINE", "line://call/{phone}"),
        "com.kakao.talk" to Pair("KakaoTalk", "kakaotalk://call/{phone}"),
        "com.tencent.mm" to Pair("WeChat", "weixin://voip/call?phone={phone}"),
        "jp.naver.line.android" to Pair("LINE", "line://call/number/{phone}"),
        "org.thoughtcrime.securesms" to Pair("Signal", "sgnl://call?phone={phone}")
    )
    
    /**
     * Get list of VoIP apps that are both:
     * 1. Installed on device
     * 2. In the Communication category
     * 3. Have a known deep link scheme
     * 
     * Returns base configs (uncalibrated) - use getConfig() for calibrated data.
     */
    fun getAvailableApps(context: Context): List<VoIPAppConfig> {
        val pm = context.packageManager
        val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        
        return installedApps
            .filter { app ->
                // Check if it's a Communication app (API 26+)
                val isCommunication = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    app.category == ApplicationInfo.CATEGORY_SOCIAL ||
                    app.category == ApplicationInfo.CATEGORY_PRODUCTIVITY
                } else {
                    true // On older APIs, don't filter by category
                }
                
                // Must be in our known apps list
                val isKnown = knownApps.containsKey(app.packageName)
                
                isCommunication && isKnown
            }
            .mapNotNull { app ->
                knownApps[app.packageName]?.let { (displayName, deepLink) ->
                    VoIPAppConfig(
                        packageName = app.packageName,
                        displayName = displayName,
                        deepLinkScheme = deepLink
                    )
                }
            }
            .sortedBy { it.displayName }
    }
    
    /**
     * Get fully configured app (with calibration data if available).
     */
    fun getConfig(context: Context, packageName: String): VoIPAppConfig? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(packageName, null)
        
        return if (json != null) {
            try {
                VoIPAppConfig.fromJson(JSONObject(json))
            } catch (e: Exception) {
                // Fallback to base config if saved data is corrupt
                getBaseConfig(packageName)
            }
        } else {
            getBaseConfig(packageName)
        }
    }
    
    /**
     * Get base config (no calibration) for a known app.
     */
    fun getBaseConfig(packageName: String): VoIPAppConfig? {
        return knownApps[packageName]?.let { (displayName, deepLink) ->
            VoIPAppConfig(
                packageName = packageName,
                displayName = displayName,
                deepLinkScheme = deepLink
            )
        }
    }
    
    /**
     * Save calibration data for an app.
     */
    fun saveConfig(context: Context, config: VoIPAppConfig) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(config.packageName, config.toJson().toString()).apply()
    }
    
    /**
     * Check if an app has been calibrated.
     */
    fun isCalibrated(context: Context, packageName: String): Boolean {
        return getConfig(context, packageName)?.isCalibrated == true
    }
    
    /**
     * Get calibrated click position for an app.
     * Returns null if not calibrated.
     * 
     * Used by both VoIPAutoClickManager and HapticGuideManager.
     */
    fun getClickPosition(context: Context, packageName: String): Pair<Float, Float>? {
        val config = getConfig(context, packageName)
        return if (config?.isCalibrated == true) {
            Pair(config.clickX, config.clickY)
        } else {
            null
        }
    }
    
    /**
     * Clear calibration for an app (for recalibration).
     */
    fun clearCalibration(context: Context, packageName: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(packageName).apply()
    }
    
    /**
     * Get all calibrated apps.
     */
    fun getCalibratedApps(context: Context): List<VoIPAppConfig> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.all.mapNotNull { (_, value) ->
            try {
                val config = VoIPAppConfig.fromJson(JSONObject(value as String))
                if (config.isCalibrated) config else null
            } catch (e: Exception) {
                null
            }
        }
    }
    
    /**
     * Check if we know about this package (has deep link scheme).
     */
    fun isKnownApp(packageName: String): Boolean = knownApps.containsKey(packageName)
}