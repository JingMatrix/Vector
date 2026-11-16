package org.matrix.vector.ui.locale

import kotlinx.coroutines.flow.StateFlow

/**
 * The chosen in-app language and the languages on offer, so the shared locale plumbing and the
 * [LanguageSheet] do not reach into either app's settings.
 *
 * [appLocale] is a BCP-47 tag, empty meaning "follow the system". [availableTags] is the set of
 * languages the host is actually translated into — hard-listed by each app from its own resource
 * folders rather than guessed from `AssetManager`, which reports every locale a dependency ships.
 */
interface LocaleController {
    val appLocale: StateFlow<String>

    val availableTags: List<String>

    fun setAppLocale(tag: String)

    /**
     * Who to thank for each language, keyed by tag.
     *
     * A reviewed list rather than a translatable string: a name here is rendered beside a *link*,
     * and translation rights are cheaper to obtain than commit rights, so a URL arriving through
     * the translation pipeline is a URL nobody reviewed shown under the project's name. Empty is a
     * correct state -- the rows simply carry no credit.
     */
    val translators: Map<String, List<Translator>>
        get() = emptyMap()

    /** Whether foreign modules in the Store are automatically translated. */
    val autoTranslate: StateFlow<Boolean>
        get() = kotlinx.coroutines.flow.MutableStateFlow(false)

    fun setAutoTranslate(enabled: Boolean) {}

    /** Explicit translation target language tag override, or empty to follow app locale. */
    val translationTargetLanguage: StateFlow<String>
        get() = kotlinx.coroutines.flow.MutableStateFlow("")

    fun setTranslationTargetLanguage(tag: String) {}
}
