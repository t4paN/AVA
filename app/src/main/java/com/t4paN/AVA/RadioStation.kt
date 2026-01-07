package com.t4paN.AVA

/**
 * Represents a radio station with its stream URL.
 * Hardcoded for stability - elderly users don't need 80 stations,
 * they need 4 that work every time.
 */
data class RadioStation(
    val id: String,
    val displayName: String,  // Used for TTS announcement
    val streamUrl: String
)

object RadioStations {
    
    val ALL = listOf(
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
        )
    )
    
    fun getByIndex(index: Int): RadioStation {
        return ALL[index.mod(ALL.size)]
    }
    
    fun nextIndex(currentIndex: Int): Int {
        return (currentIndex + 1).mod(ALL.size)
    }
    
    fun prevIndex(currentIndex: Int): Int {
        return (currentIndex - 1).mod(ALL.size)
    }
}
