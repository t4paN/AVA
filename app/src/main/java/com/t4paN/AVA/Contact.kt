package com.t4paN.AVA

import android.content.Context

/**
 * Represents a phone contact with fuzzy matching support
 * 
 * @property displayName The contact's name as shown in the phone's contacts
 * @property phoneNumber The contact's phone number
 * @property normalizedName Preprocessed name for matching (lowercase, no accents)
 * @property phoneticVariants Optional list of phonetic variants for improved matching (future cache feature)
 */
data class Contact(
    val displayName: String,
    val phoneNumber: String,
    val normalizedName: String,
    val phoneticVariants: List<String> = emptyList()
)

/**
 * Contact caching system using SharedPreferences + JSON
 * 
 * This skeleton provides the structure for future cache implementation.
 * When implemented, it will allow caregivers to add phonetic variants for
 * contacts that consistently fail to match from rough Greek transcriptions.
 */
object ContactCache {
    private const val PREFS_NAME = "ava_contact_cache"
    private const val KEY_CONTACTS = "cached_contacts"
    
    /**
     * TODO: Save contacts list to SharedPreferences as JSON
     * 
     * Implementation notes:
     * - Serialize List<Contact> to JSON
     * - Store in SharedPreferences under KEY_CONTACTS
     * - Include all fields: displayName, phoneNumber, normalizedName, phoneticVariants
     */
    fun saveContacts(context: Context, contacts: List<Contact>) {
        // Future implementation: JSON serialize and store
    }
    
    /**
     * TODO: Load contacts from SharedPreferences
     * 
     * @return List<Contact> if cache exists, null if not yet implemented or empty
     * 
     * Implementation notes:
     * - Retrieve JSON string from SharedPreferences
     * - Deserialize to List<Contact>
     * - Return null if no cache exists (triggers fresh load from ContactsContract)
     */
    fun loadCachedContacts(context: Context): List<Contact>? {
        // Future implementation: Load and deserialize JSON
        return null // Currently returns null - cache not yet implemented
    }
    
    /**
     * TODO: Add a phonetic variant to a specific contact
     * 
     * @param displayName The contact's display name to update
     * @param variant The failed transcription to add as a new phonetic variant
     * 
     * Implementation notes:
     * - Load existing contacts from cache
     * - Find contact by displayName
     * - Add variant to phoneticVariants list if not already present
     * - Save updated contacts back to cache
     * - This will be exposed via a caregiver UI (manual refresh button)
     */
    fun addPhoneticVariant(context: Context, displayName: String, variant: String) {
        // Future implementation: Caregiver adds failed transcription as new variant
    }
    
    /**
     * TODO: Clear all cached contacts (manual refresh)
     * 
     * Implementation notes:
     * - Clear SharedPreferences
     * - Trigger fresh load from ContactsContract
     */
    fun clearCache(context: Context) {
        // Future implementation: Clear cache and force refresh
    }
}
