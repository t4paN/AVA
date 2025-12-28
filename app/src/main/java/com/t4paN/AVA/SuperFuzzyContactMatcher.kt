package com.t4paN.AVA

import android.util.Log
import kotlin.math.max
import kotlin.math.min

/**
 * Multi-layered fuzzy contact matcher for rough Greek speech transcriptions
 * 
 * NOW WITH: 
 * - Flashlight and Radio intent detection
 * - Lowered threshold (0.6) for better garbled command detection
 * - Multi-word fallback (2+ words = CALL by default)
 */
object SuperFuzzyContactMatcher {
    private const val TAG = "FuzzyMatcher"
    
    // Minimum confidence threshold for a match
    private const val MIN_CONFIDENCE = 0.4
    
    // Minimum gap between best and second-best match (prevents ambiguity)
    private const val MIN_CONFIDENCE_GAP = 0.10
    
    // Threshold for fuzzy intent detection (lowered from 0.7 to catch more garbling)
    private const val INTENT_SIMILARITY_THRESHOLD = 0.6
    
    // Greek accent removal map
    private val accentMap = mapOf(
        'ά' to 'α', 'έ' to 'ε', 'ή' to 'η', 'ί' to 'ι', 'ό' to 'ο',
        'ύ' to 'υ', 'ώ' to 'ω', 'ϊ' to 'ι', 'ϋ' to 'υ',
        'Ά' to 'Α', 'Έ' to 'Ε', 'Ή' to 'Η', 'Ί' to 'Ι', 'Ό' to 'Ο',
        'Ύ' to 'Υ', 'Ώ' to 'Ω', 'Ϊ' to 'Ι', 'Ϋ' to 'Υ'
    )
    
    /**
     * Call intent patterns - both clean and garbled variants
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
        "λιση", "λησι", "λυσι",
        
        // More extreme garbling
        "ζαι", "ζη", "ζι", "ζει",
        "σι", "ση", "θι", "θη",
        "κι", "κη", "κει",
        "λι", "λη", "λει",
        
        // Very short remnants
        "κλ", "κα", "πα", "τη"
    )
    
    /**
     * Flashlight intent patterns
     */
    private val flashlightIntentPatterns = listOf(
        "φακός", "φακος", "φακό", "φακο",
        "φάκος", "φάκο"
    )
    
    /**
     * Radio intent patterns
     */
    private val radioIntentPatterns = listOf(
        "ραδιόφωνο", "ραδιοφωνο", "ραδιόφωνα", "ραδιοφωνα",
        "ραδιο", "ράδιο", "radio"
    )
    
    /**
     * Intent types the matcher can recognize
     */
    enum class Intent {
        CALL,
        FLASHLIGHT,
        RADIO
    }
    
    /**
     * Result of a contact matching attempt with detailed breakdown
     */
    data class MatchResult(
        val contact: Contact,
        val confidence: Double,
        val breakdown: String
    )
    
    // Store last ambiguous candidates for debugging
    private var lastAmbiguousCandidates: List<MatchResult>? = null
    
    /**
     * Get the last ambiguous candidates (when match failed due to close scores)
     */
    fun getLastAmbiguousCandidates(): List<MatchResult>? = lastAmbiguousCandidates
    
    /**
     * Clear stored ambiguous candidates
     */
    fun clearAmbiguousCandidates() {
        lastAmbiguousCandidates = null
    }
    
    /**
     * Find the best matching contact for a transcription
     * 
     * NOTE: Only processes CALL intents. Other intents should be handled in RecordingService.
     */
    fun findBestMatch(transcription: String, contacts: List<Contact>): MatchResult? {
        // Clear previous ambiguous candidates
        lastAmbiguousCandidates = null
        
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
        
        // Step 2: Detect intent and strip command
        val (intent, stripped) = detectAndStripIntent(cleaned)
        
        if (intent == null) {
            Log.w(TAG, "No intent detected - aborting match")
            return null
        }
        
        if (intent != Intent.CALL) {
            Log.w(TAG, "Non-CALL intent detected: $intent - aborting contact match")
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
            if (score > 0.250) {
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
                
                // Store ambiguous candidates for debugging
                lastAmbiguousCandidates = deduplicated.take(3)
                
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
     * Strategy (in order):
     * 1. Check single-word special commands (flashlight, radio)
     * 2. Try fuzzy pattern matching for call commands
     * 3. Multi-word fallback: 2+ words = CALL (strip first word)
     * 
     * Returns: Pair(Intent?, cleanedText)
     */
    fun detectAndStripIntent(text: String): Pair<Intent?, String> {
        val tokens = text.split(" ").filter { it.isNotEmpty() }
        
        if (tokens.isEmpty()) {
            return Pair(null, text)
        }
        
        // STEP 1: Check for single-word special commands (flashlight, radio)
        if (tokens.size == 1) {
            val word = tokens[0]
            
            // Check flashlight patterns
            val isFlashlight = flashlightIntentPatterns.any { pattern ->
                similarity(word, pattern) >= INTENT_SIMILARITY_THRESHOLD
            }
            if (isFlashlight) {
                Log.d(TAG, "Flashlight intent detected: '$word'")
                return Pair(Intent.FLASHLIGHT, "")
            }
            
            // Check radio patterns
            val isRadio = radioIntentPatterns.any { pattern ->
                similarity(word, pattern) >= INTENT_SIMILARITY_THRESHOLD
            }
            if (isRadio) {
                Log.d(TAG, "Radio intent detected: '$word'")
                return Pair(Intent.RADIO, "")
            }
            
            // Single word with no special command = reject
            Log.d(TAG, "Single word with no recognized intent")
            return Pair(null, text)
        }
        
        // STEP 2: Try pattern matching for CALL commands (multi-word)
        
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
        
        // Check first two tokens combined
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
        
        // STEP 3: Multi-word fallback (assume CALL if 2+ words but no pattern match)
        if (tokens.size >= 2) {
            Log.d(TAG, "No pattern match, but 2+ words - assuming CALL intent, stripping '${tokens[0]}'")
            val remaining = tokens.drop(1).joinToString(" ")
            return Pair(Intent.CALL, remaining)
        }
        
        Log.d(TAG, "No intent detected")
        return Pair(null, text)
    }
    
    // --- CLEANING / NORMALIZATION ---
    
    /**
     * Clean transcription: normalize, remove noise (��), deduplicate repeated chars
     */
    fun cleanTranscription(text: String): String {
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
     */
    /**
     * Match transcription tokens against a contact
     * ENFORCES left-to-right token order
     */
    /**
     * Match transcription tokens against a contact
     * ENFORCES left-to-right token order AND character order
     */
    private fun matchContact(
        originalTokens: List<String>,
        mergedTokens: List<String>,
        contact: Contact
    ): Pair<Double, String> {
        val normalizedContact = contact.normalizedName
        val contactTokens = normalizedContact.split(" ").filter { it.isNotEmpty() }

        var totalScore = 0.0
        val breakdown = mutableListOf<String>()

        // 1. Best token-to-token score WITH POSITION ENFORCEMENT (60% weight)
        var bestTokenScore = 0.0
        var bestTokenMatch = ""

        // Track which contact token positions we've matched to prevent backwards matching
        val matchedContactIndices = mutableSetOf<Int>()

        for ((spokenIdx, spoken) in mergedTokens.withIndex()) {
            var bestScoreForThisToken = 0.0
            var bestMatchForThisToken = ""
            var bestContactIdx = -1

            for ((ctIdx, ct) in contactTokens.withIndex()) {
                // CRITICAL: Only allow matching if this contact token comes AFTER
                // any previously matched contact tokens (enforces left-to-right)
                val minAllowedIdx = matchedContactIndices.maxOrNull()?.plus(1) ?: 0
                if (ctIdx < minAllowedIdx) {
                    continue // Skip backwards matches
                }

                val lev = similarity(spoken, ct)

                // Substring bonus only if substantial overlap (>50% of shorter string)
                // AND characters appear in forward order (no backwards matching)
                val minLen = min(spoken.length, ct.length)
                val hasSubstring = ct.contains(spoken) || spoken.contains(ct)
                val sub = if (hasSubstring &&
                    (spoken.length >= minLen * 0.5 || ct.length >= minLen * 0.5) &&
                    isForwardMatch(spoken, ct)) {
                    0.15
                } else {
                    0.0
                }
                // For single-token contacts, also try full concatenation
                if (contactTokens.size == 1) {
                    val fullSpokenConcat = originalTokens.joinToString("")
                    val singleTokenScore = similarity(fullSpokenConcat, contactTokens[0])
                    if (singleTokenScore > bestTokenScore) {
                        bestTokenScore = singleTokenScore
                        bestTokenMatch = "'$fullSpokenConcat'~'${contactTokens[0]}'=${"%.3f".format(singleTokenScore)}"
                    }
                }
                val score = min(lev + sub, 1.0) // Cap at 1.0
                if (score > bestScoreForThisToken) {
                    bestScoreForThisToken = score
                    bestMatchForThisToken = "'$spoken'~'$ct'=${"%.3f".format(score)}"
                    bestContactIdx = ctIdx
                }
            }

            // Update best overall match
            if (bestScoreForThisToken > bestTokenScore) {
                bestTokenScore = bestScoreForThisToken
                bestTokenMatch = bestMatchForThisToken
                if (bestContactIdx >= 0) {
                    matchedContactIndices.add(bestContactIdx)
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

    /**
     * Check if characters in 'a' appear in the same forward order in 'b'
     * Prevents backwards matching like "σαρδαμ" matching "μαρ" from "μαρια"
     */
    private fun isForwardMatch(a: String, b: String): Boolean {
        val (shorter, longer) = if (a.length <= b.length) Pair(a, b) else Pair(b, a)

        var longerIdx = 0
        for (ch in shorter) {
            // Find this character in the remaining part of longer string
            val found = longer.indexOf(ch, longerIdx)
            if (found == -1) return false
            longerIdx = found + 1
        }
        return true
    }
    // --- SIMILARITY FUNCTIONS ---
    
    /**
     * Similarity with phonetic boost
     */
    private fun similarity(a: String, b: String): Double {
        val lev = levenshteinSimilarity(a, b)
        val ph = levenshteinSimilarity(phoneticSimplify(a), phoneticSimplify(b))
        return maxOf(lev, ph)
    }
    
    /**
     * Phonetic simplification for Greek
     */
    private fun phoneticSimplify(s: String): String = s
        .replace("γγ", "γκ")
        .replace("ω", "ο")
        .replace("η", "ι")
        .replace("υ", "ι")
        .replace("ει", "ι")
        .replace("οι", "ι")
        .replace("αι", "ε")
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
