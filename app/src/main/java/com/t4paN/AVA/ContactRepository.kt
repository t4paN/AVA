//ContactRepository.kt

package com.t4paN.AVA

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
 * Routing keywords (viber, whatsapp, signal) in surname position are
 * extracted and stripped from the matchable name.
 */
object ContactRepository {
    private const val TAG = "ContactRepository"

    // Routing keywords - last word of contact name triggers routing
    private val routingKeywords = mapOf(
        "viber" to "VIBER",
        "whatsapp" to "WHATSAPP",
        "signal" to "SIGNAL"
    )

    /**
     * Load all phone contacts from the device
     *
     * @param context Application context
     * @return List of Contact objects with normalized names, or empty list if no permission
     *
     * This method:
     * 1. Checks READ_CONTACTS permission
     * 2. Queries ContactsContract for all contacts with phone numbers
     * 3. Extracts routing keywords from surname position
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
     * Steps:
     * 1. Convert to lowercase
     * 2. Remove accents/diacritics (Greek: ά→α, έ→ε, etc.)
     * 3. Trim whitespace
     *
     * @param name The contact's display name (with routing already stripped)
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
