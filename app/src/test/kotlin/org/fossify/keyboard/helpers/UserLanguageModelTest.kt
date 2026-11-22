package org.fossify.keyboard.helpers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class UserLanguageModelTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun newModel(file: File = tempFolder.newFile("user_language_model.json")): UserLanguageModel =
        UserLanguageModel(file)

    @Test
    fun `recordWord builds unigrams and bigrams`() {
        val model = newModel()
        model.recordWord("Je", "")
        model.recordWord("vais", "je")
        model.recordWord("vais", "je")
        model.recordWord("paris", "vais")

        assertTrue(model.personalBoost("vais") > model.personalBoost("paris"))
        assertEquals(0.0, model.personalBoost("jamais"), 0.0)
        assertEquals(listOf("vais"), model.nextWordSuggestions("je"))
        assertEquals(listOf("paris"), model.nextWordSuggestions("vais"))
        assertEquals(emptyList<String>(), model.nextWordSuggestions("paris"))
    }

    @Test
    fun `recordWord ignores too short or non-letter words`() {
        val model = newModel()
        model.recordWord("a", null)
        model.recordWord("123", null)
        model.recordWord("ok", null)
        assertEquals(0.0, model.personalBoost("a"), 0.0)
        assertTrue(model.personalBoost("ok") > 0.0)
    }

    @Test
    fun `persists to disk and reloads`() {
        val file = tempFolder.newFile("user_language_model.json")
        val model = UserLanguageModel(file)
        model.recordWord("hello", null)
        model.recordWord("world", "hello")
        model.save()

        val reloaded = UserLanguageModel(file)
        assertEquals(model.personalBoost("hello"), reloaded.personalBoost("hello"), 0.0001)
        assertEquals(listOf("world"), reloaded.nextWordSuggestions("hello"))
    }

    @Test
    fun `clear empties the model and the file`() {
        val file = tempFolder.newFile("user_language_model.json")
        val model = UserLanguageModel(file)
        model.recordWord("hello", null)
        model.recordWord("world", "hello")
        model.save()
        model.clear()

        assertEquals(0.0, model.personalBoost("hello"), 0.0)
        assertEquals(emptyList<String>(), model.nextWordSuggestions("hello"))

        val reloaded = UserLanguageModel(file)
        assertEquals(0.0, reloaded.personalBoost("hello"), 0.0)
    }
}
