package org.matrix.vector.manager.ui.theme

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.text.TextUtils
import android.content.res.Resources
import android.os.LocaleList
import android.view.View
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Locale
import org.matrix.vector.manager.BuildConfig
import org.matrix.vector.manager.di.ServiceLocator

/**
 * The app's language, chosen in the app rather than by the system.
 *
 * Android has a per-app language API, and the manager cannot use it: `setApplicationLocales` is
 * keyed on an installed package, and parasitically this one is never installed — it is running
 * inside `com.android.shell`, whose language is not ours to set. Asking the framework would either
 * do nothing or change the host's language, and neither is acceptable.
 *
 * So the override is applied in composition instead. A configuration carrying the chosen locale is
 * provided down the tree along with a context created from it, which is what `stringResource` reads,
 * so every string below re-resolves the moment the choice changes — with no activity restart, and
 * no effect on any other app in the process.
 *
 * The empty string means "whatever the system says", and is the default: an override is a
 * preference, not a fact, and someone who has never opened this sheet should keep following their
 * phone.
 */
@Composable
fun LocalizedContent(content: @Composable () -> Unit) {
    val tag by ServiceLocator.settings.appLocale.collectAsStateWithLifecycle()
    val base = LocalConfiguration.current
    val context = LocalContext.current

    val localized =
        remember(tag, base) {
            if (tag.isBlank()) null
            else
                Configuration(base).apply {
                    setLocales(LocaleList.forLanguageTags(tag))
                }
        }

    if (localized == null) {
        // No override: leave the tree exactly as the system built it rather than re-providing
        // identical values, so the common case costs nothing.
        content()
        return
    }

    val localizedContext = remember(localized) { LocalizedContext(context, localized) }

    CompositionLocalProvider(
        LocalConfiguration provides localized,
        LocalContext provides localizedContext,
        LocalLayoutDirection provides localized.layoutDirection(),
    ) {
        // The whole app is about to say something different, and a cut makes that read as a
        // glitch. Crossfading it makes the change look like the thing the user just asked for.
        AnimatedContent(
            targetState = tag,
            transitionSpec = {
                (fadeIn(tween(320)) + scaleIn(tween(320), initialScale = 0.985f))
                    .togetherWith(fadeOut(tween(180)))
            },
            label = "language",
        ) { _ ->
            content()
        }
    }
}

/**
 * The same override, for anything drawn in its own window.
 *
 * A bottom sheet, a dialog and a dropdown are not part of the activity's view tree — each gets its
 * own `AndroidComposeView`, and every one of those re-provides `LocalContext` and
 * `LocalConfiguration` from the window's own context on the way in. That silently overwrites
 * whatever [LocalizedContent] provided, so the app would read in the chosen language while its
 * filter menus and sheets stayed in the phone's. Re-establishing the override inside the popup's
 * composition is the only place the value survives.
 *
 * No crossfade here: the window is appearing anyway, and animating its contents on top of that
 * reads as a stutter.
 */
@Composable
fun LocalizedOverlay(content: @Composable () -> Unit) {
    val tag by ServiceLocator.settings.appLocale.collectAsStateWithLifecycle()
    val base = LocalConfiguration.current
    val context = LocalContext.current

    if (tag.isBlank()) {
        content()
        return
    }

    val localized =
        remember(tag, base) { Configuration(base).apply { setLocales(LocaleList.forLanguageTags(tag)) } }
    val localizedContext = remember(localized) { LocalizedContext(context, localized) }

    CompositionLocalProvider(
        LocalConfiguration provides localized,
        LocalContext provides localizedContext,
        LocalLayoutDirection provides localized.layoutDirection(),
        content = content,
    )
}

/**
 * Which way the chosen language runs.
 *
 * Compose does not read this from the configuration we provide — `LocalLayoutDirection` is set once
 * from the host view, which is the activity's, so choosing Arabic would have translated every
 * string and then laid them out left-to-right. Providing it alongside the configuration mirrors the
 * whole tree at once: `Row`, `start`/`end` padding, alignment and the auto-mirrored icons all
 * follow it, so no screen needs its own RTL handling.
 */
private fun Configuration.layoutDirection(): LayoutDirection {
    val locale = locales.takeIf { it.size() > 0 }?.get(0) ?: Locale.getDefault()
    return if (TextUtils.getLayoutDirectionFromLocale(locale) == View.LAYOUT_DIRECTION_RTL) {
        LayoutDirection.Rtl
    } else {
        LayoutDirection.Ltr
    }
}

/**
 * A context that answers with the chosen language but is still the activity.
 *
 * Not the bare `createConfigurationContext(config)`: what that returns is a fresh context that does
 * *not* wrap the activity, and Compose resolves `LocalActivityResultRegistryOwner` — and
 * `LocalActivity`, and the back-press dispatcher — by walking `ContextWrapper.getBaseContext()`
 * upwards looking for the activity. From a detached context that walk finds nothing, so
 * `rememberLauncherForActivityResult` throws the moment a screen that installs or uninstalls
 * anything enters composition.
 *
 * Wrapping instead of replacing keeps that chain intact and overrides only what has to change.
 */
private class LocalizedContext(base: Context, config: Configuration) : ContextWrapper(base) {
    private val localized: Resources = base.createConfigurationContext(config).resources

    override fun getResources(): Resources = localized
}

/**
 * Every language this app is actually translated into.
 *
 * Listed at build time from the resource folders that carry our own `strings.xml`, so a language
 * appears the moment a translator's folder lands and nobody has to maintain a list by hand.
 *
 * Deliberately *not* `AssetManager.getLocales()`: it reports every locale any dependency ships a
 * resource for — AndroidX alone contributes dozens — along with the pseudo-locales, so the picker
 * would offer Afrikaans, Azerbaijani and "Éñĝļîšĥ" in an app that has none of them.
 */
fun availableLocales(): List<Locale> =
    BuildConfig.TRANSLATIONS.split(',')
        .filter { it.isNotBlank() }
        .map { Locale.forLanguageTag(it) }
        .sortedBy { it.getDisplayName(it).lowercase(it) }

/** The language's name in itself — "Deutsch", not "German". A reader looking for their own. */
fun Locale.nativeName(): String =
    getDisplayName(this).replaceFirstChar { if (it.isLowerCase()) it.titlecase(this) else it.toString() }

/**
 * The locale the composition is actually using.
 *
 * `DateFormat.getDateInstance()` reads `Locale.getDefault()`, which is the *process* default — the
 * host application's, and unaffected by the in-composition override. Without this a reader who has
 * chosen 中文 still gets "29 janv. 2026" beside it.
 */
@Composable
fun currentLocale(): Locale =
    LocalConfiguration.current.locales.takeIf { it.size() > 0 }?.get(0) ?: Locale.getDefault()
