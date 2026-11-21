package org.fossify.keyboard.helpers

import org.json.JSONArray
import kotlin.math.abs
import kotlin.math.min

const val MAX_SUGGESTIONS = 3
private const val MAX_LENGTH_DIFF = 2
private const val MAX_EDIT_DISTANCE = 2
private const val MIN_WORD_LENGTH = 2

/**
 * Pure, Android-free word index built from a dictionary JSON file
 * ([ [word, log10(frequency)], ... ], most frequent first).
 *
 * Keys are lowercased, so lookups are case-insensitive: the fastText-sourced dictionaries
 * preserve case and proper nouns like "Paris" sometimes exist only in capitalized form. A
 * display map keeps the original-cased form to show in the suggestion strip (an all-lowercase
 * form is preferred when the dictionary holds both cases of the same word).
 *
 * Extracted from [SpellChecker] so the matching logic can be unit-tested on the JVM.
 */
class DictionaryIndex private constructor(
    private val ranks: Map<String, Int>,
    private val display: Map<String, String>
) {
    val size: Int get() = ranks.size

    fun isValidWord(word: String): Boolean {
        val w = word.lowercase().replace('’', '\'')
        if (ranks.containsKey(w)) {
            return true
        }
        // elided/contracted word ("l'homme", "don't"): valid when the part after the last
        // apostrophe is a known word
        val apostropheIndex = w.lastIndexOf('\'')
        return apostropheIndex >= 0 && ranks.containsKey(w.substring(apostropheIndex + 1))
    }

    /**
     * The most frequent words of the dictionary, used to predict the word following a space
     * before the user types its first letter. The dictionary holds unigram frequencies only,
     * so the most probable next word is simply the most frequent one (optionally excluding
     * the word that was just typed).
     */
    fun nextWordSuggestions(exclude: String? = null, limit: Int = MAX_SUGGESTIONS): List<String> {
        val excluded = exclude?.lowercase()
        return ranks.entries
            .asSequence()
            .filter { it.key != excluded }
            .sortedByDescending { it.value }
            .take(limit)
            .map { display[it.key] ?: it.key }
            .toList()
    }

    fun findSuggestions(input: String): List<String> {
        val inputLower = input.lowercase().replace('’', '\'')
        // an apostrophe inside the typed word (elision/contraction like "l'ho" or "don'"):
        // look up the part after the last apostrophe, then re-attach the prefix so the
        // word stays whole ("l'homme") instead of being split in two
        val split = (inputLower.lastIndexOf('\'') + 1).coerceAtLeast(0)
        val prefix = inputLower.substring(0, split)
        val lookup = inputLower.substring(split)
        // after an apostrophe ("d'a") even a single letter is enough, the prefix already
        // constrains the match; without one, keep the usual minimum word length
        if (lookup.isEmpty() || (prefix.isEmpty() && lookup.length < MIN_WORD_LENGTH)) return emptyList()

        val prefixMatches = ranks.keys
            .filter { word -> word.startsWith(lookup) && word != lookup }
            .sortedByDescending { ranks[it] ?: 0 }
            .take(MAX_SUGGESTIONS)
            .map { prefix + (display[it] ?: it) }

        if (prefixMatches.isNotEmpty()) {
            return prefixMatches
        }

        val firstChar = lookup[0]
        val lookupLen = lookup.length
        return ranks.keys
            .filter { word -> word[0] == firstChar && abs(word.length - lookupLen) <= MAX_LENGTH_DIFF }
            .map { word -> word to levenshteinDistance(lookup, word) }
            .filter { it.second <= MAX_EDIT_DISTANCE }
            .sortedWith(compareBy({ it.second }, { -(ranks[it.first] ?: 0) }))
            .take(MAX_SUGGESTIONS)
            .map { prefix + (display[it.first] ?: it.first) }
    }

    private fun levenshteinDistance(a: String, b: String, maxDistance: Int = MAX_EDIT_DISTANCE): Int {
        val m = a.length
        val n = b.length
        val dp = Array(m + 1) { IntArray(n + 1) }

        for (i in 0..m) dp[i][0] = i
        for (j in 0..n) dp[0][j] = j

        for (i in 1..m) {
            var rowMin = Int.MAX_VALUE
            for (j in 1..n) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[i][j] = min(
                    min(dp[i - 1][j] + 1, dp[i][j - 1] + 1),
                    dp[i - 1][j - 1] + cost
                )
                rowMin = min(rowMin, dp[i][j])
            }
            if (rowMin > maxDistance) return maxDistance + 1
        }

        return dp[m][n]
    }

    companion object {
        val EMPTY = DictionaryIndex(emptyMap(), emptyMap())

        /** parses wordfreq-style JSON: [ [word, log10(frequency)], ... ], most frequent first */
        fun fromJson(json: String): DictionaryIndex {
            val ranks = HashMap<String, Int>()
            val display = HashMap<String, String>()
            val jsonArray = JSONArray(json)
            for (i in 0 until jsonArray.length()) {
                val word = jsonArray.getJSONArray(i).getString(0)
                val key = word.lowercase()
                val rank = jsonArray.length() - i
                ranks[key] = maxOf(ranks[key] ?: 0, rank)
                // prefer an all-lowercase form for display when both cases of a word exist
                if (!display.containsKey(key) || word == key) {
                    display[key] = word
                }
            }
            return DictionaryIndex(ranks, display)
        }
    }
}
