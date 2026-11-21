package org.fossify.dictionnary.helpers

// authority of the content provider used to share the downloaded dictionary files
const val AUTHORITY = "org.fossify.dictionnary.provider"

// signature level permission, shared with the keyboard app when both are signed with the same key
const val ACCESS_PERMISSION = "org.fossify.dictionnary.ACCESS_DICTIONARIES"

// base URL of the pre-built dictionary files committed in this repository
// (dictionnary/dictionaries/<lang>.json). They are generated offline from the fastText Common
// Crawl word vectors (https://fasttext.cc/docs/en/crawl-vectors.html) by
// dictionnary/tools/generate_dictionaries.py and are small (~0.5 MB each, wordfreq-style JSON:
// [ [word, log10(frequency)], ... ], most frequent first). Only this app is allowed to reach
// the network; the keyboard app never touches it and only reads the files through the content
// provider.
const val DICTIONARY_BASE_URL =
    "https://raw.githubusercontent.com/komuros/Keyboard/auto-correct-with-dictionnary-app/dictionnary/dictionaries"

// languages this app knows how to download and serve, the file name is "<lang>.json"
val AVAILABLE_DICTIONARIES = listOf(
    "en_US", "fr_FR", "de_DE", "es_ES", "it_IT", "pt_PT", "nl_NL", "pl_PL", "sv_SE", "da_DK",
    "nb_NO", "ru_RU", "uk_UA", "cs_CZ", "tr_TR", "el_GR", "he_IL", "ar_AR", "bn_BD", "lv_LV",
    "lt_LT", "ro_RO", "sl_SI", "bg_BG", "eo"
)

// pre-built "<lang>.json" file served from this repository, null when the language is unknown
fun dictionarySourceUrl(lang: String): String? =
    if (lang in AVAILABLE_DICTIONARIES) "$DICTIONARY_BASE_URL/$lang.json" else null

// provider paths
const val PATH_DICTIONARIES = "dictionaries"
const val PATH_FILES = "files"

// assets folder holding the pre-built dictionaries (dictionnary/dictionaries, symlinked into
// src/main/assets) used as an offline source before hitting the network
const val PATH_ASSETS_DICTIONARIES = "dictionaries"

// provider "call" methods
const val METHOD_DOWNLOAD = "download"
const val METHOD_LIST = "list"

// activity action used by the keyboard app to ask for a download and get the result back
const val ACTION_DOWNLOAD_DICTIONARY = "org.fossify.dictionnary.action.DOWNLOAD_DICTIONARY"

// intent extras
const val EXTRA_LANG = "lang"
const val EXTRA_STARTED = "started"
const val EXTRA_LANGUAGES = "languages"
const val EXTRA_CALLING_PACKAGE = "calling_package"
const val EXTRA_RESULT_URI = "result_uri"
const val EXTRA_ERROR = "error"

// cursor columns returned by query()
const val COLUMN_ID = "_id"
const val COLUMN_LANG = "lang"
const val COLUMN_NAME = "name"
const val COLUMN_URI = "uri"
const val COLUMN_SIZE = "size"
const val COLUMN_DOWNLOADED = "downloaded"

// uri matcher codes
const val CODE_DICTIONARIES = 1
const val CODE_DICTIONARY = 2
const val CODE_FILE = 3

fun dictionaryFileUri(lang: String): String =
    "content://$AUTHORITY/$PATH_FILES/$PATH_DICTIONARIES/$lang.json"
