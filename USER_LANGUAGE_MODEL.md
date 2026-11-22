# Apprentissage local du langage (user language model on-device)

Plan d'implementation d'un apprentissage directement dans le clavier, **100 % local** :
aucune donnee ne quitte l'appareil. Le module `:app` n'a aucune permission `INTERNET`
(voir DICTIONNARY.md), donc c'est garanti par construction, et les donnees sont stockees
dans le `filesDir` prive de l'app, lisible par aucune autre application.

Principe repris de Gboard (federated learning), mais sans serveur : un modele **bigramme
local** (unigrammes = frequences de mots, bigrammes = mot precedent -> mot suivant)
appris de la frappe reelle de l'utilisateur, combine au dictionnaire statique telecharge
par l'app Dictionnary.

```
frappe -> SimpleKeyboardIME (mot valide a l'espace / ponctuation / suggestion acceptee)
                |  jamais si isPasswordField() ou si le reglage est off
                v
     UserLanguageModel.kt            unigrammes + bigrammes en memoire,
       filesDir/user_language_model.json   sauvegarde JSON differee (debounce, thread arriere-plan)
                |
                v
     SpellChecker.kt  ->  score = rang dictionnaire + PERSONAL_WEIGHT * boost personnel
     barre de suggestions : suggestions de frappe + prediction du mot suivant (bigramme)
```

## Etape 1 — Nouveau fichier `UserLanguageModel.kt`

Chemin : `app/src/main/kotlin/org/fossify/keyboard/helpers/UserLanguageModel.kt`

```kotlin
package org.fossify.keyboard.helpers

import android.content.Context
import org.fossify.commons.helpers.ensureBackgroundThread
import org.json.JSONObject
import java.io.File
import kotlin.math.log10

private const val MAX_ENTRIES = 20_000
private const val SAVE_DEBOUNCE_MS = 15_000L

/**
 * On-device language model learned from what the user actually types.
 * Unigrams (word counts) + bigrams (previous -> next). Persisted as JSON in the app's
 * private storage. The keyboard app has no INTERNET permission: nothing ever leaves the device.
 */
class UserLanguageModel(context: Context) {
    private val appContext = context.applicationContext
    private val file = File(appContext.filesDir, "user_language_model.json")

    private val unigrams = HashMap<String, Long>()
    private val bigrams = HashMap<String, HashMap<String, Long>>()

    @Volatile private var dirty = false
    private var lastSaveTs = 0L

    init { ensureBackgroundThread { load() } }

    @Synchronized
    fun recordWord(word: String, previousWord: String?) {
        val w = word.lowercase().trim()
        if (w.length < 2 || !w.any { it.isLetter() }) return
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
        return log10(1.0 + count)
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
        scheduleSave(0L)
    }

    private fun pruneIfNeeded() {
        if (unigrams.size > MAX_ENTRIES) {
            val sorted = unigrams.entries.sortedBy { it.value }
            for (entry in sorted) {
                if (unigrams.size <= MAX_ENTRIES) break
                unigrams.remove(entry.key)
            }
        }
        for (m in bigrams.values) {
            if (m.size > MAX_ENTRIES) {
                val sorted = m.entries.sortedBy { it.value }
                for (entry in sorted) {
                    if (m.size <= MAX_ENTRIES) break
                    m.remove(entry.key)
                }
            }
        }
    }

    private fun scheduleSave(delayMs: Long = SAVE_DEBOUNCE_MS) {
        if (!dirty) return
        val now = System.currentTimeMillis()
        if (delayMs > 0 && now - lastSaveTs < delayMs) return
        lastSaveTs = now
        ensureBackgroundThread { save() }
    }

    private fun load() {
        synchronized(this) {
            if (!file.isFile) return
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
            }
        }
    }

    private fun save() {
        synchronized(this) {
            if (!dirty) return
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
            }
        }
    }
}
```

Notes :
- `recordWord` ne touche qu'a la memoire (O(1)) : aucun risque d'ANR dans l'IME.
- La sauvegarde disque est debounce + `ensureBackgroundThread` (meme pattern que le chargement
  du dictionnaire dans `SpellChecker`).
- `clear()` vide les deux maps et re-ecrit le fichier (utilise par le bouton "effacer").

## Etape 2 — Constante dans `Constants.kt`

`app/src/main/kotlin/org/fossify/keyboard/helpers/Constants.kt`, apres
`DICTIONARY_LANGUAGE_PROPOSED` (ligne ~36) :

```kotlin
const val LEARN_FROM_TYPING = "learn_from_typing"
```

## Etape 3 — Preference dans `Config.kt`

`app/src/main/kotlin/org/fossify/keyboard/helpers/Config.kt`, apres `autoCorrectOnSpace`
(ligne ~125). Opt-in explicite, **defaut false** (choix vie privee pour un clavier FOSS) :

```kotlin
var learnFromTyping: Boolean
    get() = prefs.getBoolean(LEARN_FROM_TYPING, false)
    set(learnFromTyping) = prefs.edit().putBoolean(LEARN_FROM_TYPING, learnFromTyping).apply()
```

## Etape 4 — Scoring combine dans `SpellChecker.kt`

`app/src/main/kotlin/org/fossify/keyboard/helpers/SpellChecker.kt`

1. Ajouter en haut (a cote des autres constantes privees) :

```kotlin
private const val PERSONAL_WEIGHT = 5_000.0
```

2. Ajouter une reference vers le modele utilisateur (parametre du constructeur ou propriete) :

```kotlin
class SpellChecker(
    context: Context,
    private val dictionaryLang: String = DEFAULT_DICTIONARY_LANG,
    private val userLanguageModel: UserLanguageModel? = null,
)
```

3. Dans `findSuggestions`, remplacer les deux `sortedByDescending { dict[it] ?: 0 }` /
   `compareBy({ it.second }, { -(dict[it.first] ?: 0) })` par un score combine :

```kotlin
// prefix matches
val prefixMatches = dict.keys
    .filter { word -> word.startsWith(inputLower) && word != inputLower }
    .sortedByDescending { word -> combinedScore(word, 0) }
    .take(MAX_SUGGESTIONS)

// levenshtein matches
.sortedWith(compareBy({ it.second }, { -combinedScore(it.first, 0) }))
```

avec :

```kotlin
private fun combinedScore(word: String, editDistance: Int): Double {
    val rank = dictionary?.get(word) ?: 0
    val personal = userLanguageModel?.personalBoost(word) ?: 0.0
    return rank + PERSONAL_WEIGHT * personal - editDistance
}
```

4. Exposer la prediction du mot suivant :

```kotlin
fun nextWordSuggestions(previousWord: String, limit: Int = MAX_SUGGESTIONS): List<String> =
    userLanguageModel?.nextWordSuggestions(previousWord, limit) ?: emptyList()
```

Calibrage : les rangs du dictionnaire vont jusqu'a ~25 000 (`MAX_DICTIONARY_WORDS` dans le
module dictionnary). `personalBoost` vaut `log10(1 + count)` ; avec `PERSONAL_WEIGHT = 5000`,
un mot tape 1 000 fois (boost ~3) gagne ~15 000 points, enough pour remonter au-dessus de
mots frequents du dictionnaire mais pas au-dessus de tout. A ajuster apres test reel.

## Etape 5 — Hooks dans `SimpleKeyboardIME.kt`

`app/src/main/kotlin/org/fossify/keyboard/services/SimpleKeyboardIME.kt`

1. Champs (a cote de `spellChecker` / `wordBuffer`, ligne ~140) :

```kotlin
private val userLanguageModel by lazy { UserLanguageModel(this) }
private var lastCommittedWord: String? = null
```

2. Passer le modele au SpellChecker dans `setupSpellChecker()` :

```kotlin
spellChecker = SpellChecker(this, dictionaryLang, userLanguageModel)
```

3. Reset du contexte dans `onStartInput` (nouveau champ = on oublie le mot precedent) :

```kotlin
wordBuffer.clear()
lastCommittedWord = null
clearSpellSuggestions()
```

4. Fonction d'apprentissage (ne jamais apprendre dans un champ mot de passe) :

```kotlin
private fun learnWord(word: String) {
    if (!config.learnFromTyping || isPasswordField()) return
    userLanguageModel.recordWord(word, lastCommittedWord)
    lastCommittedWord = word.lowercase()
}
```

5. Appeler `learnWord` avec le mot **final** (celui qui part dans le texte) :

- dans `handleAutoCorrectSpace()` : si auto-correct applique -> `learnWord(topSuggestion!!)`,
  sinon -> `learnWord(currentWord)` si non vide ;
- dans `applySuggestion(word)` -> `learnWord(word)` ;
- dans `onKey`, branche `else` quand le caractere n'est pas une lettre : si c'est une
  ponctuation de fin de mot (`.`, `!`, `?`, `,`, `;`, `:`) et `wordBuffer` non vide ->
  `learnWord(wordBuffer.toString())` puis `lastCommittedWord = null` (debut de phrase).

6. (Optionnel, etape ulterieure) prediction du mot suivant : quand `wordBuffer` est vide,
   afficher `spellChecker?.nextWordSuggestions(lastCommittedWord.orEmpty())` dans la barre
   de suggestions.

## Etape 6 — UI dans les reglages

1. `app/src/main/res/layout/activity_settings.xml` : ajouter un holder switch apres le bloc
   `settings_dictionnary_holder` (ligne ~368-386), sur le meme pattern que
   `settings_auto_correct_holder` :

```xml
<RelativeLayout
    android:id="@+id/settings_learn_from_typing_holder"
    style="@style/SettingsHolderSwitchStyle"
    android:layout_width="match_parent"
    android:layout_height="wrap_content">

    <org.fossify.commons.views.MyMaterialSwitch
        android:id="@+id/settings_learn_from_typing"
        style="@style/SettingsSwitchStyle"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:text="@string/learn_from_typing" />

</RelativeLayout>
```

plus un holder texte pour le bouton d'effacement (pattern `SettingsHolderTextViewStyle`) :
`settings_clear_learned_data_holder` + `settings_clear_learned_data`.

2. `app/src/main/res/values/strings.xml` (apres `auto_correct_on_space`, ligne ~49) :

```xml
<string name="learn_from_typing">Learn from my typing (stored locally only)</string>
<string name="clear_learned_data">Clear learned typing data</string>
<string name="learned_data_cleared">Learned typing data cleared</string>
```

3. `SettingsActivity.kt` :
- appeler `setupLearnFromTyping()` dans le bloc d'init a cote de `setupShowSuggestions()` /
  `setupAutoCorrect()` (lignes ~80-81) ;
- ajouter, sur le pattern de `setupAutoCorrect()` (ligne ~440) :

```kotlin
private fun setupLearnFromTyping() {
    binding.apply {
        settingsLearnFromTyping.isChecked = config.learnFromTyping
        settingsLearnFromTypingHolder.setOnClickListener {
            settingsLearnFromTyping.toggle()
            config.learnFromTyping = settingsLearnFromTyping.isChecked
        }
        settingsClearLearnedDataHolder.setOnClickListener {
            UserLanguageModel(this@SettingsActivity).clear()
            toast(R.string.learned_data_cleared)
        }
    }
}
```

Note : instancier un `UserLanguageModel` ici ne fait que charger le fichier en arriere-plan ;
`clear()` vide et re-ecrit le fichier. Le `SimpleKeyboardIME` garde sa propre instance en
memoire — prevoir soit de partager une instance (singleton) soit de recharger apres effacement.

## Regles de confidentialite (obligatoires)

- **Jamais dans un champ mot de passe** : `isPasswordField()` existe deja dans
  `SimpleKeyboardIME` (couvre `TYPE_TEXT_VARIATION_PASSWORD`, `VISIBLE_PASSWORD`,
  `WEB_PASSWORD`, `TYPE_NUMBER_VARIATION_PASSWORD`). `learnWord` retourne immediatement si vrai.
- Envisager d'exclure aussi `TYPE_TEXT_VARIATION_EMAIL_ADDRESS`, `TYPE_TEXT_VARIATION_URI`,
  `TYPE_TEXT_VARIATION_WEB_EDIT_TEXT`.
- **Opt-in** : `learnFromTyping` defaut `false`, active manuellement dans les reglages.
- **Effacement** : bouton "Clear learned typing data" dans les reglages ; la desinstallation
  de l'app supprime tout (`filesDir`).
- Aucune permission reseau ajoutee ; aucun log ; le fichier JSON reste dans le sandbox privé.

## Performance / robustesse

- `recordWord` = increment de `HashMap` en memoire uniquement (O(1)), appelable sans risque
  depuis le thread de l'IME.
- Sauvegarde disque debounce (15 s) sur `ensureBackgroundThread`, ecriture via fichier `.tmp`
  puis renommage (meme pattern que `DictionaryDownloader`).
- Pruning a `MAX_ENTRIES = 20 000` par map (on retire les compteurs les plus faibles).
- Chargement initial en arriere-plan dans `init`.

## Ordre de travail conseille

1. Etapes 1-3 (modele + pref) -> `./gradlew :app:assembleDebug`.
2. Etape 5 (hooks dans l'IME, sans consommer le modele) -> build + test manuel : taper,
   verifier que `filesDir/user_language_model.json` apparait et se remplit.
3. Etape 4 (scoring combine dans SpellChecker) -> test manuel : un mot rare mais souvent tape
   doit remonter dans les suggestions.
4. Etape 6 (UI reglages) -> verifier le switch opt-in et le bouton d'effacement.
5. Optionnel : prediction du mot suivant via bigrammes dans la barre de suggestions.
6. Verification finale : `./gradlew :app:assembleDebug` + `./gradlew :app:testDebugUnitTest`,
   puis test sur appareil/emulateur (champs texte + champ mot de passe pour confirmer
   qu'aucun apprentissage n'y a lieu).

## Limites connues

- Modele n-gramme local, pas un reseau de neurones : excellent pour la personnalisation
  (mots rares personnels, habitudes "mot suivant"), moins bon pour le contexte long.
- Pour aller vers un vrai petit LLM on-device : piste LiteRT-LM / Gemini Nano (Pixel recents),
  mais overkill pour de la prediction de frappe (latence clavier).
- Le modele est global (toutes langues confondues) : si plusieurs langues clavier sont
  utilisees, envisager un fichier par langue (`user_language_model_<lang>.json`) en passant
  `dictionaryLang` a `UserLanguageModel`.
