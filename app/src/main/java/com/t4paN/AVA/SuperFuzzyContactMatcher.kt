// SuperFuzzyContactMatcher.kt
package com.t4paN.AVA

import android.util.Log
import kotlin.math.max
import kotlin.math.min

/**
 * Multi-layered fuzzy contact matcher for rough Greek speech transcriptions
 *
 * NOW WITH:
 * - Unified phonetic normalization (ει→ι, οι→ι, αι→ε, η→ι, υ→ι, ω→ο, γγ→γκ)
 * - Improved intent boundary detection (finds ισι/ισε pattern in first 6 chars)
 * - Flashlight and Radio intent detection
 * - Multi-word fallback (2+ words = CALL by default)
 * - Token-level length factor (penalizes tiny tokens matching huge contact names)
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
     * Call intent patterns - trimmed since normalization + boundary detection handles most cases
     * These catch patterns that don't contain the ισι/ισε boundary
     */
    private val callIntentPatterns = listOf(
        // Main normalized forms (κλισι caught by boundary detection)
        "καλεσε", "καλεστε",
        "παρε", "παρτε",
        "τιλεφονισε", "τιλεφονο",
        "καλο", "call", "καλ"
    )

    /**
     * Flashlight intent patterns (normalized forms)
     */
    private val flashlightIntentPatterns = listOf(
        "φακος", "φακο"
    )

    /**
     * Radio intent patterns (normalized forms)
     */
    private val radioIntentPatterns = listOf(
        "ραδιοφονο", "ραδιοφονα",
        "ραδιο", "radio"
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

        // Step 1: Clean transcription (removes ��, normalizes phonetically)
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
     * 2. Try boundary detection for CALL (look for ισι/ισε pattern in first 6 chars)
     * 3. Try fuzzy pattern matching for call commands
     * 4. Multi-word fallback: 2+ words = CALL (strip first word)
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

            // STEP 1b: Try boundary detection for single merged word (e.g., "κλισιξα")
            val boundaryResult = tryBoundaryDetection(text)
            if (boundaryResult != null) {
                Log.d(TAG, "Call intent detected via boundary in single word: '$text'")
                return boundaryResult
            }

            // Single word with no special command = reject
            Log.d(TAG, "Single word with no recognized intent")
            return Pair(null, text)
        }

        // STEP 2: Try boundary detection for CALL (handles merged/garbled κλήση)
        val boundaryResult = tryBoundaryDetection(text)
        if (boundaryResult != null) {
            Log.d(TAG, "Call intent detected via boundary detection")
            return boundaryResult
        }

        // STEP 3: Try pattern matching for CALL commands (multi-word)

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

        // STEP 4: Multi-word fallback (assume CALL if 2+ words but no pattern match)
        if (tokens.size >= 2) {
            Log.d(TAG, "No pattern match, but 2+ words - assuming CALL intent, stripping '${tokens[0]}'")
            val remaining = tokens.drop(1).joinToString(" ")
            return Pair(Intent.CALL, remaining)
        }

        Log.d(TAG, "No intent detected")
        return Pair(null, text)
    }

    /**
     * Try to detect CALL intent via boundary detection
     * 
     * Looks for ισι, ισε, or ισαι pattern within first 6 characters (ignoring spaces)
     * Returns the remaining text after the pattern as the contact name
     */
    private fun tryBoundaryDetection(text: String): Pair<Intent, String>? {
        // Build a mapping from no-space index to original index
        val noSpaceToOriginal = mutableListOf<Int>()
        for ((idx, char) in text.withIndex()) {
            if (char != ' ') {
                noSpaceToOriginal.add(idx)
            }
        }
        
        val noSpaceText = text.replace(" ", "")
        
        if (noSpaceText.length < 3) return null
        
        // Only look in first 6 characters (without spaces)
        val searchArea = noSpaceText.take(6)
        
        // Look for ισαι first (4 chars, more specific)
        val isaiIdx = searchArea.indexOf("ισαι")
        if (isaiIdx != -1) {
            // Cut after ισαι (4 chars)
            val cutAfterNoSpace = isaiIdx + 4
            if (cutAfterNoSpace <= noSpaceToOriginal.size) {
                val cutAfterOriginal = if (cutAfterNoSpace < noSpaceToOriginal.size) {
                    noSpaceToOriginal[cutAfterNoSpace]
                } else {
                    text.length
                }
                val remaining = text.substring(cutAfterOriginal).trim()
                Log.d(TAG, "Boundary detection: found 'ισαι' at $isaiIdx, remaining: '$remaining'")
                return Pair(Intent.CALL, remaining)
            }
        }
        
        // Look for ισι (3 chars)
        val isiIdx = searchArea.indexOf("ισι")
        if (isiIdx != -1) {
            // Cut after ισι (3 chars)
            val cutAfterNoSpace = isiIdx + 3
            if (cutAfterNoSpace <= noSpaceToOriginal.size) {
                val cutAfterOriginal = if (cutAfterNoSpace < noSpaceToOriginal.size) {
                    noSpaceToOriginal[cutAfterNoSpace]
                } else {
                    text.length
                }
                val remaining = text.substring(cutAfterOriginal).trim()
                Log.d(TAG, "Boundary detection: found 'ισι' at $isiIdx, remaining: '$remaining'")
                return Pair(Intent.CALL, remaining)
            }
        }
        
        // Look for ισε (3 chars) - from αι→ε normalization
        val iseIdx = searchArea.indexOf("ισε")
        if (iseIdx != -1) {
            // Cut after ισε (3 chars)
            val cutAfterNoSpace = iseIdx + 3
            if (cutAfterNoSpace <= noSpaceToOriginal.size) {
                val cutAfterOriginal = if (cutAfterNoSpace < noSpaceToOriginal.size) {
                    noSpaceToOriginal[cutAfterNoSpace]
                } else {
                    text.length
                }
                val remaining = text.substring(cutAfterOriginal).trim()
                Log.d(TAG, "Boundary detection: found 'ισε' at $iseIdx, remaining: '$remaining'")
                return Pair(Intent.CALL, remaining)
            }
        }
        
        return null
    }

    // --- CLEANING / NORMALIZATION ---
    
    // Placeholder for protecting άι from digraph conversion
    private const val AI_PLACEHOLDER = "\u0000AI\u0000"

    /**
     * Clean transcription: normalize phonetically, remove noise (��), deduplicate repeated chars
     * 
     * Applies full phonetic normalization so all downstream matching uses consistent forms:
     * - άι protected (two syllables, not digraph) - e.g. τσάι, Μάιος
     * - ει → ι, οι → ι, αι → ε (digraphs)
     * - η → ι, υ → ι (vowel equivalents)
     * - ω → ο (vowel equivalent)
     * - γγ → γκ (consonant cluster)
     * 
     * MUST match ContactRepository.normalizeName() exactly!
     */
    fun cleanTranscription(text: String): String {
        // Step 1: protect άι (two syllables, not digraph) BEFORE lowercasing
        val protected = text.replace("άι", AI_PLACEHOLDER).replace("Άι", AI_PLACEHOLDER)
        
        // Step 2: lowercase + remove accents
        val noAccents = normalizeGreek(protected)
        
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
        
        // Step 5: remove non-Greek chars (removes ��)
        val noNoise = restored.replace(Regex("[^α-ω ]"), " ")
        
        // Step 6: deduplicate repeated chars ("ελεννι" → "ελενι")
        val dedup = noNoise.replace(Regex("([α-ω])\\1+"), "$1")
        
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

    // --- LENGTH FACTOR ---

    /**
     * Calculate length ratio factor for token-level matching.
     *
     * Penalizes when a short transcription token matches against a much longer
     * contact name. This prevents tiny common words (3 chars) from getting
     * high scores against long multi-word contact names (25+ chars).
     *
     * Examples:
     * - "test" (4) vs "test" (4) → ratio 1.0 → factor 1.15 (bonus)
     * - "test" (4) vs "tester" (6) → ratio 0.67 → factor 1.00 (neutral)
     * - "foo" (3) vs "verylongname" (12) → ratio 0.25 → factor 0.60 (big penalty)
     *
     * Returns a multiplier between 0.60 (extreme mismatch) and 1.15 (perfect match)
     */
    private fun calculateLengthFactor(transcriptionLen: Int, contactLen: Int): Double {
        if (transcriptionLen == 0 || contactLen == 0) return 1.0

        // Ratio of shorter to longer (always 0.0 to 1.0)
        val ratio = minOf(transcriptionLen, contactLen).toDouble() / maxOf(transcriptionLen, contactLen)

        return when {
            ratio >= 0.85 -> 1.15  // Near-equal length: 15% bonus
            ratio >= 0.70 -> 1.05  // Close enough: 5% bonus
            ratio >= 0.55 -> 0.90  // Getting suspicious: 10% penalty
            ratio >= 0.40 -> 0.75  // Significant mismatch: 25% penalty
            else -> 0.60           // Extreme mismatch: 40% penalty
        }
    }

    // --- CONTACT MATCHING LOGIC ---

    /**
     * Match transcription tokens against a contact
     * ENFORCES left-to-right token order AND character order
     * APPLIES length factor at token level to penalize tiny matches in huge names
     */
    private fun matchContact(
        originalTokens: List<String>,
        mergedTokens: List<String>,
        contact: Contact
    ): Pair<Double, String> {
        val normalizedContact = contact.normalizedName
        val contactTokens = normalizedContact.split(" ").filter { it.isNotEmpty() }
        val fullContactConcat = contactTokens.joinToString("")

        var totalScore = 0.0
        val breakdown = mutableListOf<String>()

        // 1. Best token-to-token score WITH POSITION ENFORCEMENT AND LENGTH FACTOR (60% weight)
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

                val rawScore = min(lev + sub, 1.0) // Cap at 1.0

                // Apply length factor: compare spoken token against FULL contact name
                // This penalizes tiny tokens matching inside huge contact names
                val lengthFactor = calculateLengthFactor(spoken.length, ct.length)
                val score = rawScore * lengthFactor

                if (score > bestScoreForThisToken) {
                    bestScoreForThisToken = score
                    bestMatchForThisToken = "'$spoken'~'$ct'=${"%.3f".format(rawScore)}*${"%.2f".format(lengthFactor)}"
                    bestContactIdx = ctIdx
                }
            }

            // For single-token contacts, also try full concatenation
            if (contactTokens.size == 1) {
                val fullSpokenConcat = originalTokens.joinToString("")
                val rawScore = similarity(fullSpokenConcat, contactTokens[0])
                val lengthFactor = calculateLengthFactor(fullSpokenConcat.length, contactTokens[0].length)
                val singleTokenScore = rawScore * lengthFactor

                if (singleTokenScore > bestTokenScore) {
                    bestTokenScore = singleTokenScore
                    bestTokenMatch = "'$fullSpokenConcat'~'${contactTokens[0]}'=${"%.3f".format(rawScore)}*${"%.2f".format(lengthFactor)}"
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
        totalScore += bestTokenScore * 0.4

        // 2. Full string comparison WITH LENGTH FACTOR (40% weight)
        val fullSpoken = originalTokens.joinToString("")

        val rawFullScore = similarity(fullSpoken, fullContactConcat)
        val fullLengthFactor = calculateLengthFactor(fullSpoken.length, fullContactConcat.length)
        val fullScore = rawFullScore * fullLengthFactor

        breakdown.add("Full:'$fullSpoken'~'$fullContactConcat'=${"%.3f".format(rawFullScore)}*${"%.2f".format(fullLengthFactor)}")
        totalScore += fullScore * 0.4

        // 3. Prefix bonus (20% weight) - first 2 chars must match
        val firstToken = originalTokens.firstOrNull() ?: ""
        if (firstToken.length >= 2 && fullContactConcat.length >= 2 &&
            firstToken.substring(0, 2) == fullContactConcat.substring(0, 2)) {
            breakdown.add("Pre:+0.2")
            totalScore += 0.2
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
     * Similarity score using Levenshtein distance
     * 
     * Note: phoneticSimplify removed since normalization now happens in cleanTranscription()
     */
    private fun similarity(a: String, b: String): Double {
        return levenshteinSimilarity(a, b)
    }

    /*
     * COMMENTED OUT - phoneticSimplify logic moved to cleanTranscription()
     * Keeping for reference in case we need phonetic-only matching later
     *
     * private fun phoneticSimplify(s: String): String = s
     *     .replace("γγ", "γκ")
     *     .replace("ω", "ο")
     *     .replace("η", "ι")
     *     .replace("υ", "ι")
     *     .replace("ει", "ι")
     *     .replace("οι", "ι")
     *     .replace("αι", "ε")
     */

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
