package org.fossify.keyboard.helpers

/**
 * Maps a keyboard language (see LANGUAGE_* constants) to the dictionary language code
 * ("<lang>.json") served by the external Dictionnary app. Returns null when no dictionary
 * exists for that keyboard language.
 */
fun dictionaryLangForKeyboardLanguage(language: Int): String? = KEYBOARD_LANGUAGE_TO_DICTIONARY[language]

private val KEYBOARD_LANGUAGE_TO_DICTIONARY = mapOf(
    LANGUAGE_ENGLISH_QWERTY to "en_US",
    LANGUAGE_ENGLISH_QWERTZ to "en_US",
    LANGUAGE_ENGLISH_DVORAK to "en_US",
    LANGUAGE_ENGLISH_ASSET to "en_US",
    LANGUAGE_ENGLISH_COLEMAK to "en_US",
    LANGUAGE_ENGLISH_COLEMAKDH to "en_US",
    LANGUAGE_ENGLISH_NIRO to "en_US",
    LANGUAGE_ENGLISH_SOUL to "en_US",
    LANGUAGE_ENGLISH_WORKMAN to "en_US",
    LANGUAGE_FRENCH_AZERTY to "fr_FR",
    LANGUAGE_FRENCH_BEPO to "fr_FR",
    LANGUAGE_KABYLE_AZERTY to "fr_FR",
    LANGUAGE_GERMAN to "de_DE",
    LANGUAGE_GERMAN_QWERTZ to "de_DE",
    LANGUAGE_SPANISH to "es_ES",
    LANGUAGE_ITALIAN to "it_IT",
    LANGUAGE_PORTUGUESE to "pt_PT",
    LANGUAGE_PORTUGUESE_HCESAR to "pt_PT",
    LANGUAGE_DUTCH to "nl_NL",
    LANGUAGE_POLISH to "pl_PL",
    LANGUAGE_SWEDISH to "sv_SE",
    LANGUAGE_DANISH to "da_DK",
    LANGUAGE_NORWEGIAN to "nb_NO",
    LANGUAGE_RUSSIAN to "ru_RU",
    LANGUAGE_UKRAINIAN to "uk_UA",
    LANGUAGE_CZECH_QWERTY to "cs_CZ",
    LANGUAGE_CZECH_QWERTZ to "cs_CZ",
    LANGUAGE_TURKISH to "tr_TR",
    LANGUAGE_TURKISH_Q to "tr_TR",
    LANGUAGE_GREEK to "el_GR",
    LANGUAGE_HEBREW to "he_IL",
    LANGUAGE_ARABIC to "ar_AR",
    LANGUAGE_BENGALI to "bn_BD",
    LANGUAGE_LATVIAN to "lv_LV",
    LANGUAGE_LITHUANIAN to "lt_LT",
    LANGUAGE_ROMANIAN to "ro_RO",
    LANGUAGE_SLOVENIAN to "sl_SI",
    LANGUAGE_BULGARIAN to "bg_BG",
    LANGUAGE_ESPERANTO to "eo"
)
