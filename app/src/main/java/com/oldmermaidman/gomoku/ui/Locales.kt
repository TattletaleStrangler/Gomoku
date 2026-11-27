package com.oldmermaidman.gomoku.ui

import android.content.res.Resources
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.oldmermaidman.gomoku.R
import com.oldmermaidman.gomoku.prefs.AppSettings

/**
 * The languages the app ships, and the one place that switches between them.
 *
 * The choice is stored in [AppSettings] rather than in AppCompat's own storage: a
 * fresh install settles on the language of the device on its first launch and is then
 * explicit about it, which is what the picker shows.
 *
 * English is the default resource set, so a device in a language the app does not ship
 * reads English (README.md, «Localization»).
 */
object Locales {

    /**
     * Shipped tags, in the order of the picker: the closest neighbours first, then the
     * rest of Europe, then the two countries the game itself comes from.
     * Every tag here must have a `values-<tag>` directory and a line in
     * `res/xml/locales_config.xml` — LocalizationTest enforces both.
     */
    val SUPPORTED: List<String> = listOf("ru", "uk", "be", "kk", "en", "es", "de", "fi", "hi", "zh", "ja")

    /** What a device whose language the app does not ship gets. */
    const val FALLBACK = "en"

    /** The language of the device, as Android reports it, e.g. `ru-RU`. */
    fun deviceLanguage(): String {
        val locales = Resources.getSystem().configuration.locales
        return if (locales.size() > 0) locales[0].toLanguageTag() else FALLBACK
    }

    /**
     * Which shipped language a device locale maps to: the language part of the tag when
     * the app ships it, [FALLBACK] otherwise. `zh-Hans-CN` becomes `zh`, `de-AT` — `de`,
     * `fr-FR` — `en`. Pure, so it is covered by a unit test.
     */
    fun match(deviceLanguageTag: String?): String {
        val language = deviceLanguageTag
            ?.substringBefore('-')
            ?.substringBefore('_')
            ?.lowercase()
        return SUPPORTED.firstOrNull { it == language } ?: FALLBACK
    }

    /** Switches the app to [tag]; [AppSettings.LANGUAGE_SYSTEM] hands the choice back. */
    fun apply(tag: String) {
        AppCompatDelegate.setApplicationLocales(
            if (tag == AppSettings.LANGUAGE_SYSTEM) {
                LocaleListCompat.getEmptyLocaleList()
            } else {
                LocaleListCompat.forLanguageTags(tag)
            }
        )
    }

    /** The name of a language in that language: a language name is never translated. */
    fun labelRes(tag: String): Int = when (tag) {
        "ru" -> R.string.language_ru
        "uk" -> R.string.language_uk
        "be" -> R.string.language_be
        "kk" -> R.string.language_kk
        "es" -> R.string.language_es
        "de" -> R.string.language_de
        "fi" -> R.string.language_fi
        "hi" -> R.string.language_hi
        "zh" -> R.string.language_zh
        "ja" -> R.string.language_ja
        else -> R.string.language_en
    }

    /**
     * Called once, before the first screen exists.
     *
     * The per-app language of the system (Android 13+, the picker in the system settings
     * that `locales_config.xml` feeds) wins when it says something, so the two pickers
     * cannot drift apart. Otherwise the stored choice is applied, and a fresh install
     * settles on the language of the device.
     */
    fun install(settings: AppSettings) {
        val systemTag = AppCompatDelegate.getApplicationLocales()[0]?.language
            ?.takeIf { SUPPORTED.contains(it) }

        val tag = when {
            systemTag != null -> systemTag
            settings.hasLanguage() -> settings.language
            else -> match(deviceLanguage())
        }

        if (tag != settings.language) settings.language = tag
        apply(settings.language)
    }
}
