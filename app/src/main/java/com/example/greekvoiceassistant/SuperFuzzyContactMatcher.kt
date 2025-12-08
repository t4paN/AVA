package com.example.greekvoiceassistant

import android.util.Log
import kotlin.math.max
import kotlin.math.min

/**
 * Multi-layered fuzzy contact matcher for rough Greek speech transcriptions
 *
 * NOW WITH: Integrated fuzzy intent detection
 *
 * Flow:
 * 1. Clean transcription (removes ��, normalizes)
 * 2. Detect intent (fuzzy match command words)
 * 3. Strip matched command tokens
 * 4. Match contacts with cleaned name
 *
 * Architecture: All-in-one for reusing similarity functions and Greek normalization
 */
object SuperFuzzyContactMatcher {
    private const val TAG = "FuzzyMatcher"

    // Minimum confidence threshold for a match
    private const val MIN_CONFIDENCE = 0.4

    // Minimum gap between best and second-best match (prevents ambiguity)
    private const val MIN_CONFIDENCE_GAP = 0.10

    // Threshold for fuzzy intent detection
    private const val INTENT_SIMILARITY_THRESHOLD = 0.7

    // Greek accent removal map (more explicit than Normalizer)
    private val accentMap = mapOf(
        'ά' to 'α', 'έ' to 'ε', 'ή' to 'η', 'ί' to 'ι', 'ό' to 'ο',
        'ύ' to 'υ', 'ώ' to 'ω', 'ϊ' to 'ι', 'ϋ' to 'υ',
        'Ά' to 'Α', 'Έ' to 'Ε', 'Ή' to 'Η', 'Ί' to 'Ι', 'Ό' to 'Ο',
        'Ύ' to 'Υ', 'Ώ' to 'Ω', 'Ϊ' to 'Ι', 'Ϋ' to 'Υ'
    )

    /**
     * Call intent patterns - both clean and garbled variants
     *
     * Clean variants:
     * - κλήση, κάλεσε, τηλεφώνησε, etc.
     *
     * Garbled prefixes (after �� is removed):
     * - ιση, υσι, ισθη, υση, ισι, ιθη (common garbled transcriptions of "κλήση")
     */
    private val callIntentPatterns = listOf(
        // Perfect transcriptions
        "κλήση", "κλιση", "κληση", "κλησι", "κλησί",
        "κάλεσε", "καλεσε", "καλέστε", "καλεστε",
        "πάρε", "παρε", "πάρτε", "παρτε",
        "τηλεφώνησε", "τηλεφωνησε", "τηλέφωνο", "τηλεφωνο",
        "καλώ", "καλω", "call", "καλ",

        // Garbled prefixes (common after �� removal)
        "ιση", "ισι", "ιθη", "ισθη", "ισθι",
        "υσι", "υση", "υσθι", "υθη",
        "κλυσι", "κλυσί", "κλιση", "κλίση",
        "λιση", "λησι", "λυσι"
    )

    /**
     * Intent types the matcher can recognize
     */
    enum class Intent {
        CALL
    }

    /**
     * Result of a contact matching attempt with detailed breakdown
     */
    data class MatchResult(
        val contact: Contact,
        val confidence: Double,
        val breakdown: String // Debugging: shows scoring breakdown
    )

    /**
     * Find the best matching contact for a transcription
     *
     * @param transcription Raw Greek transcription from Whisper
     * @param contacts List of contacts to search (from ContactRepository)
     * @return MatchResult if confident match found, null otherwise
     */
    fun findBestMatch(transcription: String, contacts: List<Contact>): MatchResult? {
        if (contacts.isEmpty()) {
            Log.w(TAG, "No contacts available for matching")
            return null
        }

        Log.d(TAG, "=== MATCHING START ===")
        Log.d(TAG, "Raw transcription: '$transcription'")
        Log.d(TAG, "Total contacts: ${contacts.size}")

        // Step 1: Clean transcription (removes ��, normalizes)
        val cleaned = cleanTranscription(transcription)
        Log.d(TAG, "After cleaning: '$cleaned'")

        // Step 2: Detect intent and strip command (NEW!)
        val (intent, stripped) = detectAndStripIntent(cleaned)

        if (intent == null) {
            Log.w(TAG, "No call intent detected - aborting match")
            return null
        }

        Log.d(TAG, "Intent detected: $intent")
        Log.d(TAG, "After intent stripping: '$stripped'")

        if (stripped.isBlank()) {
            Log.w(TAG, "No contact name after intent stripping")
            return null
        }

        // Step 3: Tokenize and merge (creates bigrams for compound names)
        val originalTokens = stripped.split(" ").filter { it.isNotEmpty() }
        val mergedTokens = mergeTokens(originalTokens)
        Log.d(TAG, "Original tokens: $originalTokens")
        Log.d(TAG, "Merged tokens: $mergedTokens")

        // Step 4: Score all contacts (only log scores > 0.5)
        Log.d(TAG, "=== SCORING CONTACTS ===")
        val scored = contacts.map { contact ->
            val (score, breakdown) = matchContact(originalTokens, mergedTokens, contact)

            // Only log scores above threshold
            if (score > 0.5) {
                Log.d(TAG, "  '${contact.displayName}' -> ${String.format("%.3f", score)} | $breakdown")
            }

            MatchResult(
                contact = contact,
                confidence = score,
                breakdown = breakdown
            )
        }.sortedByDescending { it.confidence }

        if (scored.isEmpty()) return null

        // Step 5: Deduplicate by display name (keep highest score)
        val deduplicated = scored
            .groupBy { it.contact.displayName }
            .map { (_, results) -> results.first() }
            .sortedByDescending { it.confidence }

        // Show top 5 matches
        Log.d(TAG, "=== TOP 5 MATCHES (after deduplication) ===")
        deduplicated.take(5).forEachIndexed { index, result ->
            Log.d(TAG, "${index + 1}. '${result.contact.displayName}' = ${String.format("%.3f", result.confidence)}")
        }

        val best = deduplicated[0]
        val second = deduplicated.getOrNull(1)

        // Step 6: Check minimum confidence threshold
        if (best.confidence < MIN_CONFIDENCE) {
            Log.i(TAG, "REJECTED: Best match '${best.contact.displayName}' has score ${String.format("%.3f", best.confidence)} < threshold $MIN_CONFIDENCE")
            return null
        }

        // Step 7: Check confidence gap (avoid ambiguous matches)
        if (second != null) {
            val gap = best.confidence - second.confidence
            if (gap < MIN_CONFIDENCE_GAP) {
                Log.i(TAG, "AMBIGUOUS: '${best.contact.displayName}' (${String.format("%.3f", best.confidence)}) vs '${second.contact.displayName}' (${String.format("%.3f", second.confidence)}), gap ${String.format("%.3f", gap)} < $MIN_CONFIDENCE_GAP")
                return null
            }
        }

        Log.i(TAG, "✓ MATCHED: '${best.contact.displayName}' with confidence ${String.format("%.3f", best.confidence)}")
        Log.d(TAG, "Breakdown: ${best.breakdown}")
        Log.d(TAG, "=== MATCHING END ===")

        return best
    }

    // --- INTENT DETECTION ---

    /**
     * Detect intent and strip command words
     *
     * Returns: Pair(Intent?, cleanedText)
     * - Intent is null if no command detected
     * - cleanedText has command tokens removed
     *
     * Strategy:
     * 1. Check first 1-2 tokens for call intent patterns
     * 2. Use fuzzy matching (allows garbled transcriptions)
     * 3. Remove matched tokens from text
     */
    private fun detectAndStripIntent(text: String): Pair<Intent?, String> {
        val tokens = text.split(" ").filter { it.isNotEmpty() }

        if (tokens.isEmpty()) {
            return Pair(null, text)
        }

        // Check first token
        val firstToken = tokens[0]
        val firstMatch = callIntentPatterns.any { pattern ->
            similarity(firstToken, pattern) >= INTENT_SIMILARITY_THRESHOLD
        }

        if (firstMatch) {
            Log.d(TAG, "Call intent detected in first token: '$firstToken'")
            val remaining = tokens.drop(1).joinToString(" ")
            return Pair(Intent.CALL, remaining)
        }

        // Check first two tokens combined (for "κλήση δημήτρης" type transcriptions)
        if (tokens.size >= 2) {
            val firstTwo = tokens[0] + tokens[1]
            val combinedMatch = callIntentPatterns.any { pattern ->
                similarity(firstTwo, pattern) >= INTENT_SIMILARITY_THRESHOLD
            }

            if (combinedMatch) {
                Log.d(TAG, "Call intent detected in first two tokens: '$firstTwo'")
                val remaining = tokens.drop(2).joinToString(" ")
                return Pair(Intent.CALL, remaining)
            }
        }

        Log.d(TAG, "No call intent detected")
        return Pair(null, text)
    }

    // --- CLEANING / NORMALIZATION ---

    /**
     * Clean transcription: normalize, remove noise (��), deduplicate repeated chars
     */
    private fun cleanTranscription(text: String): String {
        val noAccents = normalizeGreek(text)
        val noNoise = noAccents.replace(Regex("[^α-ω ]"), " ") // Removes ��
        val dedup = noNoise.replace(Regex("([α-ω])\\1{2,}"), "$1") // "δημμμιτρης" → "δημιτρης"
        return dedup.trim().replace(Regex("\\s+"), " ")
    }

    /**
     * Normalize Greek: lowercase + remove accents
     */
    private fun normalizeGreek(text: String): String =
        text.lowercase().map { accentMap[it] ?: it }.joinToString("")

    // --- TOKEN MERGING ---

    /**
     * Merge tokens to handle compound names
     *
     * Example: ["δημήτρης", "παπαδόπουλος"]
     * Result: ["δημήτρης", "παπαδόπουλος", "δημήτρηςπαπαδόπουλος"]
     */
    private fun mergeTokens(tokens: List<String>): List<String> {
        val merged = tokens.toMutableList()
        for (i in 0 until tokens.size - 1) {
            merged.add(tokens[i] + tokens[i + 1])
        }
        return merged.distinct()
    }

    // --- CONTACT MATCHING LOGIC ---

    /**
     * Match transcription tokens against a contact
     *
     * Weighted scoring:
     * - 60% best token-to-token match (uses merged tokens)
     * - 30% full string match (uses original tokens only)
     * - 10% prefix bonus
     *
     * @param originalTokens The original tokens without merging
     * @param mergedTokens All tokens including bigrams
     * @param contact Contact to match against
     * @return Pair(confidence score, breakdown string)
     */
    private fun matchContact(
        originalTokens: List<String>,
        mergedTokens: List<String>,
        contact: Contact
    ): Pair<Double, String> {
        val normalizedContact = normalizeGreek(contact.displayName)
        val contactTokens = normalizedContact.split(" ").filter { it.isNotEmpty() }

        var totalScore = 0.0
        val breakdown = mutableListOf<String>()

        // 1. Best token-to-token score (60% weight)
        var bestTokenScore = 0.0
        var bestTokenMatch = ""
        for (spoken in mergedTokens) {
            for (ct in contactTokens) {
                val lev = similarity(spoken, ct)

                // Substring bonus only if substantial overlap (>50% of shorter string)
                val minLen = min(spoken.length, ct.length)
                val sub = if ((ct.contains(spoken) || spoken.contains(ct)) &&
                    (spoken.length >= minLen * 0.5 || ct.length >= minLen * 0.5)) {
                    0.15
                } else {
                    0.0
                }

                val score = min(lev + sub, 1.0) // Cap at 1.0
                if (score > bestTokenScore) {
                    bestTokenScore = score
                    bestTokenMatch = "'$spoken'~'$ct'=${"%.3f".format(score)}"
                }
            }
        }
        breakdown.add("Tok:$bestTokenMatch")
        totalScore += bestTokenScore * 0.6

        // 2. Full string comparison (30% weight)
        val fullSpoken = originalTokens.joinToString("")
        val fullContact = contactTokens.joinToString("")

        val fullScore = similarity(fullSpoken, fullContact)
        breakdown.add("Full:'$fullSpoken'~'$fullContact'=${"%.3f".format(fullScore)}")
        totalScore += fullScore * 0.3

        // 3. Prefix bonus (10% weight)
        val firstToken = originalTokens.firstOrNull() ?: ""
        if (fullContact.startsWith(firstToken) && firstToken.isNotEmpty()) {
            breakdown.add("Pre:+0.1")
            totalScore += 0.1
        }

        return totalScore to breakdown.joinToString("|")
    }

    // --- SIMILARITY FUNCTIONS ---

    /**
     * Similarity with phonetic boost
     *
     * Tries both regular and phonetically simplified versions,
     * returns best score
     */
    private fun similarity(a: String, b: String): Double {
        val lev = levenshteinSimilarity(a, b)
        val ph = levenshteinSimilarity(phoneticSimplify(a), phoneticSimplify(b))
        return maxOf(lev, ph)
    }

    /**
     * Phonetic simplification for Greek
     *
     * Maps similar-sounding vowels:
     * - η, υ, ει, οι → ι
     * - ω → ο
     */
    private fun phoneticSimplify(s: String): String = s
        .replace("ω", "ο")
        .replace("η", "ι")
        .replace("υ", "ι")
        .replace("ei", "ι")
        .replace("οι", "ι")

    /**
     * Calculate Levenshtein similarity (1.0 = identical, 0.0 = completely different)
     */
    private fun levenshteinSimilarity(s1: String, s2: String): Double {
        if (s1 == s2) return 1.0
        if (s1.isEmpty() || s2.isEmpty()) return 0.0

        val dist = levenshteinDistance(s1, s2)
        val maxLen = max(s1.length, s2.length)
        return 1.0 - (dist.toDouble() / maxLen)
    }

    /**
     * Calculate Levenshtein distance (edit distance)
     */
    private fun levenshteinDistance(s1: String, s2: String): Int {
        val dp = Array(s1.length + 1) { IntArray(s2.length + 1) }

        for (i in 0..s1.length) dp[i][0] = i
        for (j in 0..s2.length) dp[0][j] = j

        for (i in 1..s1.length) {
            for (j in 1..s2.length) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = min(
                    min(
                        dp[i - 1][j] + 1,      // deletion
                        dp[i][j - 1] + 1       // insertion
                    ),
                    dp[i - 1][j - 1] + cost    // substitution
                )
            }
        }
        return dp[s1.length][s2.length]
    }
}