package com.example.greekvoiceassistant

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.util.Log
import androidx.core.content.ContextCompat
import java.text.Normalizer

/**
 * Repository for loading and managing phone contacts
 * 
 * Handles querying Android's ContactsContract and building Contact objects
 * with normalized names for fuzzy matching.
 * 
 * UPDATED: Prioritizes PHONETIC_NAME over DISPLAY_NAME to avoid Google Contacts server overrides
 */
object ContactRepository {
    private const val TAG = "ContactRepository"
    
    /**
     * Load all phone contacts from the device
     * 
     * @param context Application context
     * @return List of Contact objects with normalized names, or empty list if no permission
     * 
     * This method:
     * 1. Checks READ_CONTACTS permission
     * 2. Queries ContactsContract for all contacts with phone numbers
     * 3. Prioritizes PHONETIC_NAME (caregiver-controlled) over DISPLAY_NAME (server-controlled)
     * 4. Builds Contact objects with normalized names for matching
     * 5. Returns empty list gracefully if permission denied
     */
    fun loadContacts(context: Context): List<Contact> {
        // Check for READ_CONTACTS permission
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "READ_CONTACTS permission not granted, returning empty contact list")
            return emptyList()
        }
        
        val contacts = mutableListOf<Contact>()
        
        try {
            // Query contacts with phone numbers
            // NOW INCLUDES: PHONETIC_NAME as priority field
            val cursor = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.PHONETIC_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                null,
                null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
            )
            
            cursor?.use {
                val displayNameIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val phoneticNameIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.PHONETIC_NAME)
                val numberIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                
                while (it.moveToNext()) {
                    val displayName = it.getString(displayNameIndex) ?: continue
                    val phoneticName = it.getString(phoneticNameIndex) // May be null
                    val phoneNumber = it.getString(numberIndex) ?: continue
                    
                    // Skip empty names or numbers
                    if (displayName.isBlank() || phoneNumber.isBlank()) continue
                    
                    // PRIORITY: Use PHONETIC_NAME if available, otherwise DISPLAY_NAME
                    // This allows caregivers to override server-side Google profile names
                    val nameToUse = if (!phoneticName.isNullOrBlank()) {
                        Log.d(TAG, "Using PHONETIC_NAME for $displayName: '$phoneticName'")
                        phoneticName
                    } else {
                        displayName
                    }
                    
                    val normalizedName = normalizeName(nameToUse)
                    
                    contacts.add(
                        Contact(
                            displayName = displayName, // Keep original for display
                            phoneNumber = phoneNumber,
                            normalizedName = normalizedName,
                            phoneticVariants = emptyList() // Will be populated from cache later
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
    
    /**
     * Normalize a contact name for fuzzy matching
     * 
     * Steps:
     * 1. Convert to lowercase
     * 2. Remove accents/diacritics (Greek: άâ†'α, έâ†'ε, etc.)
     * 3. Trim whitespace
     * 
     * @param name The contact's display name or phonetic name
     * @return Normalized name ready for fuzzy matching
     */
    private fun normalizeName(name: String): String {
        // Convert to lowercase
        var normalized = name.lowercase()
        
        // Remove accents/diacritics using NFD normalization
        // This converts accented characters to base + accent, then removes accents
        normalized = Normalizer.normalize(normalized, Normalizer.Form.NFD)
            .replace("\\p{M}".toRegex(), "") // Remove diacritical marks
        
        // Trim whitespace
        normalized = normalized.trim()
        
        return normalized
    }
    
    /**
     * Reload contacts from device (bypasses cache)
     * 
     * Use this when user manually refreshes or when cache needs updating
     */
    fun reloadContacts(context: Context): List<Contact> {
        Log.i(TAG, "Forcing contact reload from device")
        return loadContacts(context)
    }
}
