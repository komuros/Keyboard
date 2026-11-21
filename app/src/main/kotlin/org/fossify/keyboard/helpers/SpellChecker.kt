package org.fossify.keyboard.helpers

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.fossify.commons.helpers.ensureBackgroundThread
import org.json.JSONException
import java.io.IOException
import java.util.Collections

private const val DEBOUNCE_DELAY_MS = 100L
private const val CACHE_SIZE = 20

/**
 * Dictionary based spell checker used for word suggestions and auto-correct.
 *
 * The dictionary file is not bundled with the keyboard and the keyboard app has no internet
 * permission: the file is provided by the external Dictionnary app through a content URI
 * (see [DictionnaryClient]), copied locally, and loaded from that local copy. The actual
 * matching logic lives in [DictionaryIndex] (pure Kotlin, unit-tested); this class only
 * handles loading, debouncing and the suggestion cache.
 */
class SpellChecker(context: Context, private val dictionaryLang: String = DEFAULT_DICTIONARY_LANG) {
    private val appContext = context.applicationContext

    // null until the dictionary is loaded: isValidWord() then answers true (no auto-correct)
    @Volatile
    private var index: DictionaryIndex? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingRunnable: Runnable? = null
    @Suppress("MagicNumber")
    private val cache: MutableMap<String, List<String>> = Collections.synchronizedMap(
        object : LinkedHashMap<String, List<String>>(CACHE_SIZE, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<String>>) = size > CACHE_SIZE
        }
    )

    var onSuggestionsReady: ((List<String>) -> Unit)? = null
    var onDictionaryLoaded: (() -> Unit)? = null

    init {
        ensureBackgroundThread {
            loadDictionaryFromLocalCopy()
            mainHandler.post { onDictionaryLoaded?.invoke() }
        }
    }

    fun checkWord(word: String) {
        pendingRunnable?.let { mainHandler.removeCallbacks(it) }

        pendingRunnable = Runnable {
            ensureBackgroundThread {
                val suggestions = findSuggestions(word)
                mainHandler.post {
                    onSuggestionsReady?.invoke(suggestions)
                }
            }
        }

        mainHandler.postDelayed(pendingRunnable!!, DEBOUNCE_DELAY_MS)
    }

    fun clear() {
        pendingRunnable?.let { mainHandler.removeCallbacks(it) }
        mainHandler.post {
            onSuggestionsReady?.invoke(emptyList())
        }
    }

    fun isValidWord(word: String): Boolean = index?.isValidWord(word) ?: true

    /** see [DictionaryIndex.nextWordSuggestions] */
    fun nextWordSuggestions(exclude: String? = null): List<String> =
        index?.nextWordSuggestions(exclude) ?: emptyList()

    fun destroy() {
        pendingRunnable?.let { mainHandler.removeCallbacks(it) }
        onSuggestionsReady = null
        onDictionaryLoaded = null
    }

    /** reloads the dictionary, fetching it from the Dictionnary app first if needed */
    fun reload() {
        ensureBackgroundThread {
            loadDictionaryFromLocalCopy()
            mainHandler.post { onDictionaryLoaded?.invoke() }
        }
    }

    private fun loadDictionaryFromLocalCopy() {
        loadDictionary()
        if (index?.size == 0) {
            // no local copy yet: get the file from the Dictionnary app via its content
            // URI, keep a local copy of it, then load it
            DictionnaryClient.copyDictionaryToLocal(appContext, dictionaryLang)
            loadDictionary()
        }
    }

    private fun loadDictionary() {
        index = try {
            val file = DictionnaryClient.localDictionaryFile(appContext, dictionaryLang)
            if (file.isFile) {
                DictionaryIndex.fromJson(file.bufferedReader().use { it.readText() })
            } else {
                DictionaryIndex.EMPTY
            }
        } catch (_: IOException) {
            DictionaryIndex.EMPTY
        } catch (_: JSONException) {
            DictionaryIndex.EMPTY
        }
    }

    private fun findSuggestions(input: String): List<String> {
        val inputLower = input.lowercase()
        cache[inputLower]?.let { return it }
        val result = index?.findSuggestions(input) ?: emptyList()
        cache[inputLower] = result
        return result
    }
}
