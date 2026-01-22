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

    private val DEFAULT_STATIONS = listOf(
        RadioStation(
            id = "era_sport",
            displayName = "ΕΡΑ Σπορ",
            streamUrl = "http://radiostreaming.ert.gr/ert-erasport"
        ),
        RadioStation(
            id = "era_proto",
            displayName = "ΕΡΑ Πρώτο",
            streamUrl = "http://radiostreaming.ert.gr/ert-proto"
        ),
        RadioStation(
            id = "era_trito",
            displayName = "ΕΡΑ Τρίτο",
            streamUrl = "http://radiostreaming.ert.gr/ert-trito"
        ),
        RadioStation(
            id = "metropolis",
            displayName = "Μετρόπολις",
            streamUrl = "http://metropolis.live24.gr/metropolis955thess"
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
        if (!trimmedUrl.startsWith("http")) return false

        val current = getAll(context).toMutableList()
        val id = "custom_${System.currentTimeMillis()}"
        current.add(RadioStation(id, trimmedName, trimmedUrl))
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