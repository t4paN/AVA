package com.t4paN.AVA

/**
 * Represents a phone contact with fuzzy matching support
 * 
 * @property displayName The contact's name as shown in the phone's contacts
 * @property phoneNumber The contact's phone number
 * @property normalizedName Phonetically normalized name for matching (lowercase, no accents, digraphs collapsed)
 * @property routing VoIP routing type (VIBER, WHATSAPP, SIGNAL) or empty for regular calls
 * @property phoneticVariants Caregiver-added variants for failed transcriptions
 */
data class Contact(
    val displayName: String,
    val phoneNumber: String,
    val normalizedName: String,
    val routing: String = "",
    val phoneticVariants: List<String> = emptyList()
)
