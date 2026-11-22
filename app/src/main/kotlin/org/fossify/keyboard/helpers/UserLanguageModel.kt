package org.fossify.keyboard.helpers

import android.content.Context
import org.fossify.commons.helpers.ensureBackgroundThread
import org.json.JSONObject
import java.io.File
import kotlin.math.log10

private const val MAX_ENTRIES = 20_000
private const val SAVE_DEBOUNCE_MS = 15_000L
private const val FILE_NAME = "user_language_model.json"
private const val LOG_OFFSET = 1.0

/**
 * On-device language model learned from what the user actually types: unigrams (word counts)
 * + bigrams (previous word -> next word). Persisted as JSON in the app's private filesDir.
 *
 * The keyboard app has no INTERNET permission, so nothing ever leaves the device. Learning is
 * opt-in ([LEARN_FROM_TYPING], default false) and never happens in password fields (guarded by
 * the caller in SimpleKeyboardIME).
 *
 * [recordWord] only mutates in-memory maps (O(1)), safe to call from the IME thread; disk
 * writes are debounced and run on a background thread, via a .tmp file + rename.
 */
class UserLanguageModel private constructor(private val file: File, private val asyncIo: Boolean) {

    private val unigrams = HashMap<String, Long>()
    private val bigrams = HashMap<String, HashMap<String, Long>>()

    @Volatile
    private var dirty = false
    private var lastSaveTs = 0L

    init {
        if (asyncIo) ensureBackgroundThread { load() } else load()
    }

    /** test constructor: synchronous IO on an explicit file */
    constructor(file: File) : this(file, asyncIo = false)

    @Synchronized
    fun recordWord(word: String, previousWord: String?) {
        val w = word.lowercase().trim()
        if (w.length < 2 || !w.any { it.isLetter() }) {
            return
        }
        unigrams[w] = (unigrams[w] ?: 0L) + 1L
        previousWord?.lowercase()?.trim()?.takeIf { it.isNotEmpty() }?.let { prev ->
            val m = bigrams.getOrPut(prev) { HashMap() }
            m[w] = (m[w] ?: 0L) + 1L
        }
        pruneIfNeeded()
        dirty = true
        scheduleSave()
    }

    /** personal log-frequency boost, 0.0 when the word was never typed */
    @Synchronized
    fun personalBoost(word: String): Double {
        val count = unigrams[word.lowercase()] ?: return 0.0
        return log10(LOG_OFFSET + count)
    }

    /** most likely next words after [previousWord], most frequent first */
    @Synchronized
    fun nextWordSuggestions(previousWord: String, limit: Int = 3): List<String> {
        val m = bigrams[previousWord.lowercase()] ?: return emptyList()
        return m.entries.sortedByDescending { it.value }.take(limit).map { it.key }
    }

    @Synchronized
    fun clear() {
        unigrams.clear()
        bigrams.clear()
        dirty = true
        if (asyncIo) {
            scheduleSave(0L)
        } else {
            save()
        }
    }

    private fun pruneIfNeeded() {
        pruneToMax(unigrams)
        bigrams.values.forEach { pruneToMax(it) }
    }

    private fun pruneToMax(map: HashMap<String, Long>) {
        if (map.size <= MAX_ENTRIES) {
            return
        }
        // drop the lowest-count entries first
        val sorted = map.entries.sortedBy { it.value }
        for (entry in sorted) {
            if (map.size <= MAX_ENTRIES) {
                break
            }
            map.remove(entry.key)
        }
    }

    private fun scheduleSave(delayMs: Long = SAVE_DEBOUNCE_MS) {
        if (!dirty || !asyncIo) {
            return
        }
        val now = System.currentTimeMillis()
        if (delayMs > 0 && now - lastSaveTs < delayMs) {
            return
        }
        lastSaveTs = now
        ensureBackgroundThread { save() }
    }

    @Synchronized
    internal fun load() {
        if (!file.isFile) {
            return
        }
        try {
            val root = JSONObject(file.readText())
            root.optJSONObject("unigrams")?.let { obj ->
                obj.keys().forEachRemaining { unigrams[it] = obj.getLong(it) }
            }
            root.optJSONObject("bigrams")?.let { obj ->
                obj.keys().forEachRemaining { prev ->
                    val inner = obj.getJSONObject(prev)
                    val m = HashMap<String, Long>()
                    inner.keys().forEachRemaining { next -> m[next] = inner.getLong(next) }
                    bigrams[prev] = m
                }
            }
        } catch (_: Exception) {
            // corrupted file: start fresh
        }
    }

    @Synchronized
    internal fun save() {
        if (!dirty) {
            return
        }
        try {
            val root = JSONObject()
            root.put("unigrams", JSONObject(unigrams.toMap()))
            val b = JSONObject()
            bigrams.forEach { (prev, m) -> b.put(prev, JSONObject(m.toMap())) }
            root.put("bigrams", b)
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(root.toString())
            tmp.renameTo(file)
            dirty = false
        } catch (_: Exception) {
            // never crash the IME on IO errors
        }
    }

    companion object {
        @Volatile
        private var instance: UserLanguageModel? = null

        /**
         * Shared per-process instance: the keyboard app runs the IME service and the settings
         * activity in the same process, so clearing from the settings screen also affects the
         * instance used by the IME.
         */
        fun get(context: Context): UserLanguageModel =
            instance ?: synchronized(this) {
                instance ?: UserLanguageModel(
                    File(context.applicationContext.filesDir, FILE_NAME),
                    asyncIo = true
                ).also { instance = it }
            }
    }
}
