//ContactRepository.kt

package com.t4paN.AVA

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.util.Log
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject

/**
 * Repository for loading, caching, and managing phone contacts
 *
 * Handles:
 * - Querying Android's ContactsContract
 * - Building Contact objects with phonetically normalized names
 * - Persistent caching via SharedPreferences + JSON
 * - Caregiver-managed phonetic variants for failed transcriptions
 *
 * Routing keywords (viber, whatsapp, signal) in surname position are
 * extracted and stripped from the matchable name.
 * 
 * IMPORTANT: normalizeName() applies the same phonetic normalization as
 * SuperFuzzyContactMatcher.cleanTranscription() to ensure apples-to-apples comparison.
 */
object ContactRepository {
    private const val TAG = "ContactRepository"
    
    // Cache constants
    private const val PREFS_NAME = "ava_contact_cache"
    private const val KEY_CONTACTS = "cached_contacts"
    private const val KEY_CACHE_VERSION = "cache_version"
    private const val CURRENT_CACHE_VERSION = 1

    // Routing keywords - last word of contact name triggers routing
    private val routingKeywords = mapOf(
        "viber" to "VIBER",
        "whatsapp" to "WHATSAPP",
        "signal" to "SIGNAL"
    )

    // Greek accent removal map (same as SuperFuzzyContactMatcher)
    private val accentMap = mapOf(
        'ά' to 'α', 'έ' to 'ε', 'ή' to 'η', 'ί' to 'ι', 'ό' to 'ο',
        'ύ' to 'υ', 'ώ' to 'ω', 'ϊ' to 'ι', 'ϋ' to 'υ',
        'Ά' to 'Α', 'Έ' to 'Ε', 'Ή' to 'Η', 'Ί' to 'Ι', 'Ό' to 'Ο',
        'Ύ' to 'Υ', 'Ώ' to 'Ω', 'Ϊ' to 'Ι', 'Ϋ' to 'Υ'
    )
    
    // Placeholder for protecting άι from digraph conversion
    private const val AI_PLACEHOLDER = "\u0000AI\u0000"

    // ==================== MAIN LOADING API ====================

    /**
     * Load contacts - tries cache first, falls back to device
     * 
     * @param context Application context
     * @return List of contacts (from cache if available, otherwise fresh from device)
     */
    fun loadContacts(context: Context): List<Contact> {
        // Try cache first
        val cached = loadFromCache(context)
        if (cached != null) {
            Log.i(TAG, "Loaded ${cached.size} contacts from cache")
            return cached
        }
        
        // No cache, load from device and cache it
        Log.i(TAG, "No cache found, loading from device")
        val contacts = loadFromDevice(context)
        saveToCache(context, contacts)
        return contacts
    }

    /**
     * Force reload contacts from device (bypasses and updates cache)
     *
     * Use this when caregiver manually refreshes after adding contacts
     */
    fun reloadContacts(context: Context): List<Contact> {
        Log.i(TAG, "Forcing contact refresh from device")
        val contacts = loadFromDevice(context)
        saveToCache(context, contacts)
        return contacts
    }

    // ==================== PHONETIC VARIANTS ====================

    /**
     * Add a phonetic variant to a contact
     * 
     * When a transcription fails to match, caregiver can add it as a variant
     * so future attempts will match.
     * 
     * @param context Application context
     * @param displayName The contact's display name to update
     * @param variant The failed transcription to add (will be normalized)
     * @return true if variant was added, false if contact not found or variant already exists
     */
    fun addPhoneticVariant(context: Context, displayName: String, variant: String): Boolean {
        val contacts = loadFromCache(context)?.toMutableList() ?: return false
        
        val normalizedVariant = normalizeName(variant)
        if (normalizedVariant.isBlank()) return false
        
        val index = contacts.indexOfFirst { it.displayName == displayName }
        if (index == -1) {
            Log.w(TAG, "Contact not found: $displayName")
            return false
        }
        
        val contact = contacts[index]
        
        // Check if variant already exists
        if (contact.phoneticVariants.contains(normalizedVariant)) {
            Log.d(TAG, "Variant '$normalizedVariant' already exists for $displayName")
            return false
        }
        
        // Add variant
        val updatedVariants = contact.phoneticVariants + normalizedVariant
        val updatedContact = contact.copy(phoneticVariants = updatedVariants)
        contacts[index] = updatedContact
        
        saveToCache(context, contacts)
        Log.i(TAG, "Added variant '$normalizedVariant' to $displayName (now has ${updatedVariants.size} variants)")
        return true
    }

    /**
     * Get phonetic variants for a contact
     */
    fun getPhoneticVariants(context: Context, displayName: String): List<String> {
        val contacts = loadFromCache(context) ?: return emptyList()
        return contacts.find { it.displayName == displayName }?.phoneticVariants ?: emptyList()
    }

    /**
     * Remove a phonetic variant from a contact
     */
    fun removePhoneticVariant(context: Context, displayName: String, variant: String): Boolean {
        val contacts = loadFromCache(context)?.toMutableList() ?: return false
        
        val index = contacts.indexOfFirst { it.displayName == displayName }
        if (index == -1) return false
        
        val contact = contacts[index]
        val updatedVariants = contact.phoneticVariants.filter { it != variant }
        
        if (updatedVariants.size == contact.phoneticVariants.size) {
            return false // Variant wasn't in the list
        }
        
        contacts[index] = contact.copy(phoneticVariants = updatedVariants)
        saveToCache(context, contacts)
        Log.i(TAG, "Removed variant '$variant' from $displayName")
        return true
    }

    // ==================== CACHE OPERATIONS ====================

    /**
     * Clear the contact cache
     */
    fun clearCache(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
        Log.i(TAG, "Contact cache cleared")
    }

    /**
     * Check if cache exists
     */
    fun hasCachedContacts(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.contains(KEY_CONTACTS)
    }

    private fun loadFromCache(context: Context): List<Contact>? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        
        // Check cache version
        val version = prefs.getInt(KEY_CACHE_VERSION, 0)
        if (version != CURRENT_CACHE_VERSION) {
            Log.i(TAG, "Cache version mismatch ($version != $CURRENT_CACHE_VERSION), invalidating")
            clearCache(context)
            return null
        }
        
        val json = prefs.getString(KEY_CONTACTS, null) ?: return null
        
        return try {
            val contacts = mutableListOf<Contact>()
            val array = JSONArray(json)
            
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                
                // Parse phonetic variants
                val variantsArray = obj.optJSONArray("phoneticVariants")
                val variants = mutableListOf<String>()
                if (variantsArray != null) {
                    for (j in 0 until variantsArray.length()) {
                        variants.add(variantsArray.getString(j))
                    }
                }
                
                contacts.add(Contact(
                    displayName = obj.getString("displayName"),
                    phoneNumber = obj.getString("phoneNumber"),
                    normalizedName = obj.getString("normalizedName"),
                    routing = obj.optString("routing", ""),
                    phoneticVariants = variants
                ))
            }
            
            contacts
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing contact cache", e)
            null
        }
    }

    private fun saveToCache(context: Context, contacts: List<Contact>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        
        try {
            val array = JSONArray()
            
            for (contact in contacts) {
                val obj = JSONObject().apply {
                    put("displayName", contact.displayName)
                    put("phoneNumber", contact.phoneNumber)
                    put("normalizedName", contact.normalizedName)
                    put("routing", contact.routing)
                    put("phoneticVariants", JSONArray(contact.phoneticVariants))
                }
                array.put(obj)
            }
            
            prefs.edit()
                .putString(KEY_CONTACTS, array.toString())
                .putInt(KEY_CACHE_VERSION, CURRENT_CACHE_VERSION)
                .apply()
            
            Log.i(TAG, "Saved ${contacts.size} contacts to cache")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving contact cache", e)
        }
    }

    // ==================== DEVICE LOADING ====================

    /**
     * Load all phone contacts from the device
     */
    private fun loadFromDevice(context: Context): List<Contact> {
        // Check for READ_CONTACTS permission
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "READ_CONTACTS permission not granted, returning empty contact list")
            return emptyList()
        }

        val contacts = mutableListOf<Contact>()

        try {
            // Query contacts with phone numbers
            val cursor = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                null,
                null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
            )

            cursor?.use {
                val displayNameIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numberIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)

                while (it.moveToNext()) {
                    val displayName = it.getString(displayNameIndex) ?: continue
                    val phoneNumber = it.getString(numberIndex) ?: continue

                    // Skip empty names or numbers
                    if (displayName.isBlank() || phoneNumber.isBlank()) continue

                    // Extract routing and matchable name
                    val (matchableName, routing) = extractRouting(displayName)

                    if (routing.isNotEmpty()) {
                        Log.d(TAG, "Contact '$displayName' -> matchable: '$matchableName', routing: $routing")
                    }

                    val normalizedName = normalizeName(matchableName)

                    contacts.add(
                        Contact(
                            displayName = displayName,
                            phoneNumber = phoneNumber,
                            normalizedName = normalizedName,
                            routing = routing,
                            phoneticVariants = emptyList()
                        )
                    )
                }
            }

            Log.i(TAG, "Loaded ${contacts.size} contacts from device")

        } catch (e: Exception) {
            Log.e(TAG, "Error loading contacts", e)
            return emptyList()
        }

        return contacts
    }

    // ==================== NORMALIZATION ====================

    /**
     * Extract routing keyword from contact name
     *
     * If last word is "viber", "whatsapp", or "signal" (case-insensitive),
     * strip it and return the routing type.
     *
     * Examples:
     * - "Δημήτρης Viber" -> ("Δημήτρης", "VIBER")
     * - "Γιάννης Παπαδόπουλος WhatsApp" -> ("Γιάννης Παπαδόπουλος", "WHATSAPP")
     * - "Μαρία Κωνσταντίνου" -> ("Μαρία Κωνσταντίνου", "")
     */
    private fun extractRouting(displayName: String): Pair<String, String> {
        val parts = displayName.trim().split("\\s+".toRegex())

        if (parts.isEmpty()) return Pair(displayName, "")

        val lastPart = parts.last().lowercase()
        val routing = routingKeywords[lastPart]

        return if (routing != null) {
            val matchableName = parts.dropLast(1).joinToString(" ")
            Pair(matchableName, routing)
        } else {
            Pair(displayName, "")
        }
    }

    /**
     * Normalize a contact name for fuzzy matching
     *
     * MUST match SuperFuzzyContactMatcher.cleanTranscription() exactly!
     * 
     * Steps:
     * 1. Protect άι (accented α + ι) - it's two syllables, not a digraph
     * 2. Convert to lowercase
     * 3. Remove Greek accents (ά→α, έ→ε, etc.)
     * 4. Apply phonetic normalization:
     *    - ει → ι, οι → ι, αι → ε (digraphs)
     *    - η → ι, υ → ι (vowel equivalents, υ only when not part of ου)
     *    - ω → ο (vowel equivalent)
     *    - γγ → γκ (consonant cluster)
     * 5. Restore protected άι as αι
     * 6. Remove non-Greek characters
     * 7. Deduplicate repeated chars
     * 8. Trim and normalize whitespace
     *
     * @param name The contact's display name (with routing already stripped)
     * @return Normalized name ready for fuzzy matching
     */
    fun normalizeName(name: String): String {
        // Step 1: protect άι (two syllables, not digraph) BEFORE lowercasing
        val protected = name.replace("άι", AI_PLACEHOLDER).replace("Άι", AI_PLACEHOLDER)
        
        // Step 2: lowercase + remove accents
        val noAccents = protected.lowercase().map { accentMap[it] ?: it }.joinToString("")

        // Step 3: phonetic normalization (order matters - do digraphs first)
        val phonetic = noAccents
            .replace("ει", "ι")
            .replace("οι", "ι")
            .replace("αι", "ε")
            .replace("η", "ι")
            .replace(Regex("(?<!ο)υ"), "ι")  // υ → ι only when NOT part of ου
            .replace("ω", "ο")
            .replace("γγ", "γκ")
        
        // Step 4: restore protected άι as αι
        val restored = phonetic.replace(AI_PLACEHOLDER, "αι")

        // Step 5: remove non-Greek chars (keeps spaces)
        val noNoise = restored.replace(Regex("[^α-ω ]"), " ")

        // Step 6: deduplicate repeated chars ("ελεννι" → "ελενι")
        val dedup = noNoise.replace(Regex("([α-ω])\\1+"), "$1")

        return dedup.trim().replace(Regex("\\s+"), " ")
    }
}
