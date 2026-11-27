package com.oldmermaidman.gomoku

import com.oldmermaidman.gomoku.ui.Locales
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Guards the translations, because a missing key is invisible until somebody switches
 * the language: Android then quietly shows the English string in the middle of another
 * language, and a lost `%1$s` breaks a label or a formatted number.
 *
 * The three checks below are the ones the README promises: every shipped language
 * carries exactly the keys of the default file, keeps their format specifiers, and is
 * declared both in the resources and in the per-app language configuration.
 */
class LocalizationTest {

    /** The unit tests run from the module directory, but the IDE may use the root. */
    private val res: File = listOf(File("src/main/res"), File("app/src/main/res"))
        .firstOrNull { it.isDirectory }
        ?: error("no resource directory from ${File("").absolutePath}")

    private val stringTag = Regex(
        """<string\s+name="([^"]+)"([^>]*)>(.*?)</string>""",
        RegexOption.DOT_MATCHES_ALL
    )

    /** `%1$s`, `%1$d`, `%2$02d` and the escaped percent of `%%`. */
    private val specifier = Regex("""%\d+\$[sd]|%%""")

    private data class Entry(val value: String, val translatable: Boolean)

    private fun parse(file: File): Map<String, Entry> {
        check(file.isFile) { "missing $file" }
        return stringTag.findAll(file.readText()).associate { match ->
            match.groupValues[1] to Entry(
                value = match.groupValues[3],
                translatable = !match.groupValues[2].contains("translatable=\"false\"")
            )
        }
    }

    private fun defaultStrings(): Map<String, Entry> = parse(File(res, "values/strings.xml"))

    /** Keys a translation must carry: everything the default file does not pin down. */
    private fun translatableKeys(): Set<String> =
        defaultStrings().filterValues { it.translatable }.keys

    private fun localeDirectories(): List<String> =
        (res.listFiles { file -> file.isDirectory && file.name.startsWith("values-") } ?: emptyArray())
            .map { it.name.removePrefix("values-") }
            .sorted()

    @Test
    fun `every shipped language has exactly the keys of the default file`() {
        val expected = translatableKeys()

        // English is the default resource set — values/strings.xml — so it has no
        // values-en directory of its own.
        assertEquals(
            (Locales.SUPPORTED - Locales.FALLBACK).sorted(),
            localeDirectories()
        )

        for (tag in localeDirectories()) {
            val actual = parse(File(res, "values-$tag/strings.xml")).keys
            assertEquals("keys of $tag", expected, actual)
        }
    }

    @Test
    fun `format specifiers survive translation`() {
        val source = defaultStrings().filterValues { it.translatable }

        for (tag in localeDirectories()) {
            val translated = parse(File(res, "values-$tag/strings.xml"))
            for ((key, entry) in source) {
                val expected = specifier.findAll(entry.value).map { it.value }.toList()
                val actual = specifier.findAll(translated[key]?.value.orEmpty()).map { it.value }.toList()
                assertEquals("$tag: $key", expected, actual)
            }
        }
    }

    @Test
    fun `the per-app language list matches the shipped resources`() {
        val declared = Regex("""<locale\s+android:name="([^"]+)"""")
            .findAll(File(res, "xml/locales_config.xml").readText())
            .map { it.groupValues[1] }
            .toList()
        assertEquals(Locales.SUPPORTED, declared)
    }
}
