package org.fossify.keyboard.helpers

import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import org.fossify.keyboard.extensions.safeStorageContext
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Client for the external Dictionnary app (org.fossify.dictionnary).
 *
 * The keyboard app itself has no internet permission: the Dictionnary app downloads the
 * dictionary files and serves them through a content provider. Here we can check whether a
 * dictionary is already available (locally or in the Dictionnary app), ask that provider to
 * (re)download a language, and keep a local copy of the downloaded file in our own files
 * dir (see [copyDictionaryToLocal] / [copyFromUri]).
 *
 * Launching the Dictionnary app UI (download flow, install proposal) lives in
 * [DictionnaryInstaller].
 */
object DictionnaryClient {
    private const val TAG = "DictionnaryClient"
    private const val PATH_DICTIONARIES = "dictionaries"
    private const val PATH_FILES = "files"
    private const val METHOD_DOWNLOAD = "download"
    private const val COLUMN_DOWNLOADED = "downloaded"
    private const val POLL_INTERVAL_MS = 500L
    private const val MAX_POLLS = 60

    fun dictionaryFileUri(lang: String): Uri =
        Uri.parse("content://$DICTIONNARY_AUTHORITY/$PATH_FILES/$PATH_DICTIONARIES/$lang.json")

    /** local copy of the dictionary inside the keyboard app's own storage */
    fun localDictionaryFile(context: Context, lang: String): File =
        File(context.safeStorageContext.filesDir, "$PATH_DICTIONARIES/$lang.json")

    fun isLocalDictionaryAvailable(context: Context, lang: String): Boolean {
        val file = localDictionaryFile(context, lang)
        return file.isFile && file.length() > 0
    }

    /** package name of the installed Dictionnary app (release or debug), null if not installed */
    fun dictionnaryPackageName(context: Context): String? {
        val packageManager = context.packageManager
        return listOf(DICTIONNARY_PACKAGE, "$DICTIONNARY_PACKAGE.debug").firstOrNull { packageName ->
            try {
                packageManager.getLaunchIntentForPackage(packageName) != null
            } catch (_: Exception) {
                false
            }
        }
    }

    fun isDictionnaryInstalled(context: Context): Boolean = dictionnaryPackageName(context) != null

    /** asks the Dictionnary app's provider to (re)download the given language */
    fun requestDownload(context: Context, lang: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            // ContentResolver.call() only exists from API 29; on older devices the download
            // is triggered through the Dictionnary app's activity flow instead
            return
        }
        try {
            context.contentResolver.call(DICTIONNARY_AUTHORITY, METHOD_DOWNLOAD, lang, null)
        } catch (e: SecurityException) {
            Log.w(TAG, "could not ask $DICTIONNARY_AUTHORITY to download $lang", e)
        }
    }

    /** whether the Dictionnary app already has the file, as reported by its provider */
    fun isDownloadedRemotely(context: Context, lang: String): Boolean = try {
        val uri = Uri.parse("content://$DICTIONNARY_AUTHORITY/$PATH_DICTIONARIES/$lang")
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            cursor.moveToFirst() && cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_DOWNLOADED)) == 1
        } ?: false
    } catch (_: Exception) {
        false
    }

    /**
     * Makes sure a local copy of the dictionary exists in the keyboard app. If there is none,
     * the Dictionnary app is asked to download it, we wait for the download to finish, then the
     * file exposed at its content URI is opened and copied into our own files dir.
     *
     * Blocking, must be called from a background thread. Returns the local file or null.
     */
    fun copyDictionaryToLocal(context: Context, lang: String): File? {
        val localFile = localDictionaryFile(context, lang)
        if (localFile.isFile && localFile.length() > 0) {
            return localFile
        }

        if (!isDictionnaryInstalled(context)) {
            return null
        }

        requestDownload(context, lang)
        var downloaded = isDownloadedRemotely(context, lang)
        var polls = 0
        while (!downloaded && polls < MAX_POLLS) {
            Thread.sleep(POLL_INTERVAL_MS)
            downloaded = isDownloadedRemotely(context, lang)
            polls++
        }
        if (!downloaded) {
            return null
        }

        return copyFromUri(context, dictionaryFileUri(lang), lang)
    }

    /** copies the file exposed at the given content URI into our local dictionaries dir */
    fun copyFromUri(context: Context, uri: Uri, lang: String): File? {
        val localFile = localDictionaryFile(context, lang)
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                copyStreamToFile(input, localFile)
            }
        } catch (e: IOException) {
            Log.e(TAG, "could not read dictionary from $uri", e)
            null
        } catch (e: SecurityException) {
            Log.e(TAG, "no read permission on $uri", e)
            null
        }
    }

    private fun copyStreamToFile(input: InputStream, localFile: File): File? {
        localFile.parentFile?.mkdirs()
        val tmpFile = File(localFile.parentFile, localFile.name + ".tmp")
        tmpFile.outputStream().use { output -> input.copyTo(output) }
        return if (tmpFile.isFile && tmpFile.length() > 0) {
            if (localFile.exists()) {
                localFile.delete()
            }
            tmpFile.renameTo(localFile)
            localFile
        } else {
            tmpFile.delete()
            null
        }
    }
}
