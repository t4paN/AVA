package com.t4paN.AVA

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Represents a radio station with its stream URL.
 */
data class RadioStation(
    val id: String,
    val displayName: String,  // Used for TTS announcement
    val streamUrl: String
)

object RadioStations {

    private const val PREFS_NAME = "ava_radio_stations"
    private const val KEY_STATIONS = "stations_json"

    /**
     * All six ship over HTTPS. Every one of these endpoints was re-probed on
     * 2026-08-21 and answered 200 audio/mpeg over TLS, so there is no reason to
     * put a Play reviewer in front of a cleartext stream URL.
     *
     * Cleartext is still permitted app-wide (see usesCleartextTraffic in the
     * manifest) because a caregiver may add an http-only station by hand — see
     * addStation(). Shipping no cleartext ourselves and forbidding it outright
     * are different things.
     */
    private val DEFAULT_STATIONS = listOf(
        RadioStation(
            id = "era_sport",
            displayName = "ΕΡΑ Σπορ",
            streamUrl = "https://radiostreaming.ert.gr/ert-erasport"
        ),
        RadioStation(
            id = "era_proto",
            displayName = "ΕΡΑ Πρώτο",
            streamUrl = "https://radiostreaming.ert.gr/ert-proto"
        ),
        RadioStation(
            id = "era_trito",
            displayName = "ΕΡΑ Τρίτο",
            streamUrl = "https://radiostreaming.ert.gr/ert-trito"
        ),
        RadioStation(
            id = "metropolis",
            displayName = "Μετρόπολις",
            streamUrl = "https://metropolis.live24.gr/metropolis955thess"
        ),
        RadioStation(
            id = "maestro",
            displayName = "Μαέστρο",
            streamUrl = "https://radiostreaming.ert.gr/ert-trito-maestro"
        ),
        RadioStation(
            id = "rebelfm",
            displayName = "Rebel FM",
            streamUrl = "https://netradio.live24.gr/rebel1052"
        )

    )

    private var cachedStations: List<RadioStation>? = null

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getAll(context: Context): List<RadioStation> {
        cachedStations?.let { return it }

        val prefs = getPrefs(context)
        val json = prefs.getString(KEY_STATIONS, null)

        val stations = if (json != null) {
            try {
                val array = JSONArray(json)
                (0 until array.length()).map { i ->
                    val obj = array.getJSONObject(i)
                    RadioStation(
                        id = obj.getString("id"),
                        displayName = obj.getString("displayName"),
                        streamUrl = obj.getString("streamUrl")
                    )
                }
            } catch (e: Exception) {
                DEFAULT_STATIONS
            }
        } else {
            DEFAULT_STATIONS
        }

        cachedStations = stations
        return stations
    }

    fun save(context: Context, stations: List<RadioStation>) {
        val array = JSONArray()
        for (station in stations) {
            val obj = JSONObject().apply {
                put("id", station.id)
                put("displayName", station.displayName)
                put("streamUrl", station.streamUrl)
            }
            array.put(obj)
        }

        getPrefs(context).edit()
            .putString(KEY_STATIONS, array.toString())
            .apply()

        cachedStations = stations
    }

    fun addStation(context: Context, name: String, url: String): Boolean {
        val trimmedName = name.trim()
        val trimmedUrl = url.trim()

        if (trimmedName.isEmpty() || trimmedUrl.isEmpty()) return false

        // Prefer HTTPS, but never override what was typed. A caregiver pasting a
        // bare host used to be rejected outright with "wrong format", which reads
        // as "your station is bad" rather than "add a scheme"; default those to
        // https. An explicit http:// is kept as-is — the station may genuinely
        // have no TLS, and silently rewriting it would break a working stream.
        val normalizedUrl = when {
            trimmedUrl.startsWith("http://") || trimmedUrl.startsWith("https://") -> trimmedUrl
            trimmedUrl.contains("://") -> return false
            else -> "https://$trimmedUrl"
        }

        val current = getAll(context).toMutableList()
        val id = "custom_${System.currentTimeMillis()}"
        current.add(RadioStation(id, trimmedName, normalizedUrl))
        save(context, current)
        return true
    }

    fun removeStation(context: Context, index: Int): Boolean {
        val current = getAll(context).toMutableList()
        if (index < 0 || index >= current.size) return false
        current.removeAt(index)
        save(context, current)
        return true
    }

    fun resetToDefaults(context: Context) {
        save(context, DEFAULT_STATIONS)
    }

    fun getByIndex(context: Context, index: Int): RadioStation {
        val all = getAll(context)
        if (all.isEmpty()) return DEFAULT_STATIONS[0]
        return all[index.mod(all.size)]
    }

    fun nextIndex(context: Context, currentIndex: Int): Int {
        val size = getAll(context).size
        if (size == 0) return 0
        return (currentIndex + 1).mod(size)
    }

    fun prevIndex(context: Context, currentIndex: Int): Int {
        val size = getAll(context).size
        if (size == 0) return 0
        return (currentIndex - 1).mod(size)
    }
}