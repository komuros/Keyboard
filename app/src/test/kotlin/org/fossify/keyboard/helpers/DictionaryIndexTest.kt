package org.fossify.keyboard.helpers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [DictionaryIndex], the pure matching logic behind suggestions and
 * auto-correct. Fixture (wordfreq-style, most frequent first):
 *
 * | word    | rank |
 * |---------|------|
 * | the     | 10   |
 * | homme   | 9    |
 * | hommage | 8    |
 * | accord  | 7    |
 * | Paris   | 6    |
 * | london  | 5    |
 * | London  | 4    |
 * | hui     | 3    |
 * | trump   | 2    |
 * | trumpet | 1    |
 *
 * 10 entries, 9 lowercase keys ("london"/"London" merge).
 */
class DictionaryIndexTest {

    private val json = """
        [["the",-1.0],["homme",-1.1],["hommage",-1.2],["accord",-1.3],["Paris",-1.4],
         ["london",-1.5],["London",-1.6],["hui",-1.7],["trump",-1.8],["trumpet",-1.9]]
    """.trimIndent()

    private val index = DictionaryIndex.fromJson(json)

    @Test
    fun `parses all entries and merges case variants`() {
        // 10 entries, but london/London merge into one lowercase key
        assertEquals(9, index.size)
    }

    @Test
    fun `prefix match is case-insensitive and keeps display case`() {
        // only the capitalized "Paris" exists: it must still be found and displayed capitalized
        assertEquals(listOf("Paris"), index.findSuggestions("pari"))
        assertEquals(listOf("Paris"), index.findSuggestions("PARI"))
    }

    @Test
    fun `lowercase display form is preferred when both cases exist`() {
        assertEquals(listOf("london"), index.findSuggestions("lond"))
    }

    @Test
    fun `apostrophe keeps the word whole and re-attaches the prefix`() {
        assertEquals(listOf("l'homme", "l'hommage"), index.findSuggestions("l'ho"))
        assertEquals(listOf("d'accord"), index.findSuggestions("d'ac"))
        // a single letter after the apostrophe is enough (top 3 by rank)
        assertEquals(listOf("l'homme", "l'hommage", "l'hui"), index.findSuggestions("l'h"))
    }

    @Test
    fun `apostrophe at the end or too short input gives no suggestions`() {
        assertEquals(emptyList<String>(), index.findSuggestions("l'"))
        assertEquals(emptyList<String>(), index.findSuggestions("t"))
    }

    @Test
    fun `levenshtein fallback for close misspellings`() {
        // no prefix match: fall back to edit distance <= 2, closest first
        // lev("trampi","trump") = 1, lev("trampi","trumpet") = 3 -> only "trump" qualifies
        assertEquals(listOf("trump"), index.findSuggestions("trampi"))
        // the exact typed word is not excluded from the levenshtein branch (pre-existing
        // behavior): it comes first with distance 0, then "trump" at distance 2
        assertEquals(listOf("trumpet", "trump"), index.findSuggestions("trumpet"))
    }

    @Test
    fun `isValidWord handles case and apostrophes`() {
        assertTrue(index.isValidWord("Paris"))
        assertTrue(index.isValidWord("paris"))
        assertTrue(index.isValidWord("l'homme"))       // suffix "homme" is a known word
        assertTrue(index.isValidWord("D'ACCORD"))      // case-insensitive + suffix "accord"
        assertFalse(index.isValidWord("zzzz"))
        assertFalse(index.isValidWord("l'zzzz"))
    }

    @Test
    fun `nextWordSuggestions returns most frequent words, excluding the given one`() {
        assertEquals(listOf("the", "homme", "hommage"), index.nextWordSuggestions())
        assertEquals(listOf("the", "hommage", "accord"), index.nextWordSuggestions(exclude = "homme"))
    }

    @Test
    fun `personal boost can promote a rare word to the top`() {
        // "trumpet" (rank 1) vs "trump" (rank 2) for prefix "tr": rank wins without boost…
        assertEquals(listOf("trump", "trumpet"), index.findSuggestions("tr"))
        // …and a personal boost flips the order
        val boosted = index.findSuggestions("tr") { if (it == "trumpet") 100.0 else 0.0 }
        assertEquals(listOf("trumpet", "trump"), boosted)
    }

    @Test
    fun `empty index validates nothing and suggests nothing`() {
        assertEquals(0, DictionaryIndex.EMPTY.size)
        assertFalse(DictionaryIndex.EMPTY.isValidWord("the"))
        assertEquals(emptyList<String>(), DictionaryIndex.EMPTY.findSuggestions("the"))
        assertEquals(emptyList<String>(), DictionaryIndex.EMPTY.nextWordSuggestions())
    }
}
