package org.fossify.dictionnary.helpers

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Downloads the dictionary files. This is the only place in the pair of apps where the
 * network is used: the Dictionnary app holds the INTERNET permission, the keyboard app
 * never touches the network and only reads the files through the content provider.
 *
 * The sources are the small pre-built JSON files committed in this repository (see
 * [DICTIONARY_BASE_URL], generated offline from the fastText Common Crawl word vectors by
 * dictionnary/tools/generate_dictionaries.py): [ [word, log10(frequency)], ... ], most
 * frequent first. They are a few hundred KB each, downloaded as-is, no conversion on device.
 */
object DictionaryDownloader {
    private val executor = Executors.newCachedThreadPool()
    private val running = ConcurrentHashMap<String, Boolean>()
    private val listeners = ConcurrentHashMap<String, MutableList<(Boolean) -> Unit>>()

    fun dictionaryFile(context: Context, lang: String): File =
        File(context.filesDir, "$PATH_DICTIONARIES/$lang.json")

    fun isDownloaded(context: Context, lang: String): Boolean {
        val file = dictionaryFile(context, lang)
        return file.isFile && file.length() > 0
    }

    fun isRunning(lang: String): Boolean = running.containsKey(lang)

    fun isValidLang(lang: String): Boolean =
        lang.matches(Regex("[a-z]{2,3}(_[A-Za-z0-9]{2,8})*"))

    fun enqueueDownload(context: Context, lang: String, onComplete: ((Boolean) -> Unit)? = null) {
        if (!isValidLang(lang) || dictionarySourceUrl(lang) == null) {
            onComplete?.invoke(false)
            return
        }
        if (isDownloaded(context, lang)) {
            onComplete?.invoke(true)
            return
        }
        if (onComplete != null) {
            listeners.getOrPut(lang) { mutableListOf() }.add(onComplete)
        }
        if (running.putIfAbsent(lang, true) != null) {
            return
        }
        val appContext = context.applicationContext
        executor.execute {
            val success = try {
                downloadBlocking(appContext, lang)
            } finally {
                running.remove(lang)
            }
            val callbacks = listeners.remove(lang) ?: emptyList()
            callbacks.forEach { it(success) }
        }
    }

    private fun downloadBlocking(context: Context, lang: String): Boolean {
        val file = dictionaryFile(context, lang)
        file.parentFile?.mkdirs()
        val tmpFile = File(file.parentFile, file.name + ".tmp")

        // offline path first: dictionaries bundled in the app assets
        // (dictionnary/dictionaries, symlinked into src/main/assets/dictionaries)
        val fromAssets = try {
            context.assets.open("$PATH_ASSETS_DICTIONARIES/$lang.json").use { input ->
                tmpFile.outputStream().use { output -> input.copyTo(output) }
            }
            tmpFile.isFile && tmpFile.length() > 0
        } catch (_: Exception) {
            false
        }
        if (fromAssets) {
            return finalize(tmpFile, file)
        }
        tmpFile.delete()

        val sourceUrl = dictionarySourceUrl(lang) ?: return false
        val connection = URL(sourceUrl).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 15000
        connection.readTimeout = 30000
        connection.instanceFollowRedirects = true
        var success = false
        try {
            // the source is already the final JSON: plain stream copy, write to .tmp then rename
            connection.inputStream.use { input ->
                tmpFile.outputStream().use { output -> input.copyTo(output) }
            }
            success = finalize(tmpFile, file)
        } catch (_: Exception) {
            success = false
        } finally {
            if (!success) {
                tmpFile.delete()
            }
            connection.disconnect()
        }
        return success
    }

    private fun finalize(tmpFile: File, file: File): Boolean {
        if (!tmpFile.isFile || tmpFile.length() == 0L) {
            return false
        }
        if (file.exists()) {
            file.delete()
        }
        return tmpFile.renameTo(file)
    }
}
