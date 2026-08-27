package com.steve1316.uma_android_automation.types

import net.ricecode.similarity.JaroWinklerStrategy
import net.ricecode.similarity.StringSimilarityServiceImpl

// //////////////////////////////////////////////////////////////////////////////////////////////////
// //////////////////////////////////////////////////////////////////////////////////////////////////
// Thresholds

/** Similarity floor for treating two raw condition reads as the same pill (dedup across scroll passes). */
const val STATUS_DEDUP_THRESHOLD = 0.9

/** Similarity floor for matching a clean canonical condition name against a possibly-noisy raw read. */
const val STATUS_QUERY_THRESHOLD = 0.8

/** Shared, stateless Jaro-Winkler similarity service reused across all match calls. */
private val SIMILARITY_SERVICE = StringSimilarityServiceImpl(JaroWinklerStrategy())

/**
 * The index of the entry in [names] that best Jaro-Winkler matches [candidate], provided it meets [threshold]. Ties resolve to the earliest entry, so a
 * caller whose list is ranked gets the highest-ranked match.
 *
 * @param candidate The raw or canonical name to test.
 * @param names The names to compare against.
 * @param threshold The similarity floor (0.0 to 1.0).
 * @return The best matching index, or null when nothing met the threshold.
 */
fun fuzzyBestMatchIndex(candidate: String, names: List<String>, threshold: Double): Int? {
    if (candidate.isBlank() || names.isEmpty()) return null
    val lowered = candidate.lowercase()
    return names
        .mapIndexed { index, name -> index to SIMILARITY_SERVICE.score(lowered, name.lowercase()) }
        .filter { it.second >= threshold }
        .maxByOrNull { it.second }
        ?.first
}

/**
 * Returns true when [candidate] Jaro-Winkler matches any entry in [names] at or above [threshold].
 * Pure and list-free: used both to dedup raw OCR reads and to answer canonical-name membership queries.
 * Kept separate from [fuzzyBestMatchIndex] so a membership check can stop at the first hit instead of scoring the whole list.
 *
 * @param candidate The raw or canonical name to test.
 * @param names The names to compare against.
 * @param threshold The similarity floor (0.0 to 1.0).
 * @return True when at least one name meets the threshold.
 */
fun fuzzyMatchesAny(candidate: String, names: List<String>, threshold: Double): Boolean {
    if (candidate.isBlank() || names.isEmpty()) return false
    val lowered = candidate.lowercase()
    return names.any { SIMILARITY_SERVICE.score(lowered, it.lowercase()) >= threshold }
}
