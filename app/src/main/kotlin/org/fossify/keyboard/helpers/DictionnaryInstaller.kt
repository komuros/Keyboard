package org.fossify.keyboard.helpers

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.keyboard.R
import java.io.File

/**
 * UI side of the external Dictionnary app (org.fossify.dictionnary): launching its download
 * flow and getting the result back via startActivityForResult
 * (see [launchDictionaryDownload] / [handleDownloadResult]), opening it, and proposing to
 * install it when it is missing. The provider/storage side lives in [DictionnaryClient].
 */
object DictionnaryInstaller {
    /** request code used when picking the Dictionnary APK file with the system picker */
    const val REQUEST_PICK_DICTIONNARY_APK = 1002

    /** intent launching the Dictionnary app to download [lang] and report back via activity result */
    fun downloadIntent(context: Context, lang: String): Intent =
        Intent(DICTIONNARY_ACTION_DOWNLOAD).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
            putExtra(DICTIONNARY_EXTRA_LANG, lang)
            putExtra(DICTIONNARY_EXTRA_CALLING_PACKAGE, context.packageName)
        }

    /**
     * Launches the Dictionnary app so it downloads the dictionary for [lang]. The result comes
     * back in onActivityResult: RESULT_OK with [DICTIONNARY_EXTRA_RESULT_URI] pointing to the
     * downloaded file (the Dictionnary app grants us read access to that URI).
     */
    fun launchDictionaryDownload(activity: Activity, lang: String, requestCode: Int) {
        try {
            activity.startActivityForResult(downloadIntent(activity, lang), requestCode)
        } catch (_: Exception) {
            proposeInstallDictionnary(activity, lang)
        }
    }

    /**
     * Handles the activity result of [launchDictionaryDownload]: copies the file the Dictionnary
     * app exposed (and granted us) into our local storage. Returns the local file or null.
     */
    fun handleDownloadResult(context: Context, resultCode: Int, data: Intent?): File? {
        if (resultCode != Activity.RESULT_OK || data == null) {
            return null
        }
        val lang = data.getStringExtra(DICTIONNARY_EXTRA_LANG) ?: return null
        val uriString = data.getStringExtra(DICTIONNARY_EXTRA_RESULT_URI)
        val uri = uriString?.let { Uri.parse(it) } ?: DictionnaryClient.dictionaryFileUri(lang)
        return DictionnaryClient.copyFromUri(context, uri, lang)
    }

    /** opens the launcher activity of the installed Dictionnary app, if any */
    fun openDictionnaryApp(activity: Activity): Boolean {
        val packageName = DictionnaryClient.dictionnaryPackageName(activity) ?: return false
        val launchIntent = activity.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        return try {
            activity.startActivity(launchIntent)
            true
        } catch (_: Exception) {
            false
        }
    }

    /** lets the user pick the Dictionnary APK file with the system file picker */
    fun pickDictionnaryApk(activity: Activity, requestCode: Int) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/vnd.android.package-archive"
        }
        try {
            activity.startActivityForResult(intent, requestCode)
        } catch (_: Exception) {
        }
    }

    /** hands the picked APK content URI to the system package installer */
    fun installApkFromUri(activity: Activity, uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            activity.startActivity(intent)
        } catch (_: Exception) {
        }
    }

    /**
     * Shows a dialog proposing to install the Dictionnary app (it is not installed yet):
     * either by picking its APK file (system file picker → package installer, works without
     * any store), or by opening its Play Store page.
     */
    fun proposeInstallDictionnary(activity: Activity, lang: String, pickRequestCode: Int) {
        activity.getAlertDialogBuilder()
            .setTitle(R.string.install_dictionnary_title)
            .setMessage(activity.getString(R.string.install_dictionnary_message, lang))
            .setPositiveButton(R.string.install_dictionnary_pick_apk) { _, _ ->
                pickDictionnaryApk(activity, pickRequestCode)
            }
            .setNeutralButton(R.string.open_play_store) { _, _ ->
                openDictionnaryStorePage(activity)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** same as [proposeInstallDictionnary] but without the lang-specific message context */
    private fun proposeInstallDictionnary(activity: Activity, lang: String) =
        proposeInstallDictionnary(activity, lang, REQUEST_PICK_DICTIONNARY_APK)

    private fun openDictionnaryStorePage(activity: Activity) {
        val marketIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$DICTIONNARY_PACKAGE"))
        try {
            activity.startActivity(marketIntent)
        } catch (_: ActivityNotFoundException) {
            activity.startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id=$DICTIONNARY_PACKAGE")
                )
            )
        }
    }
}
