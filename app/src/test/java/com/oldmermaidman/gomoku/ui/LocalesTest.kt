package com.oldmermaidman.gomoku.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The language of the device decides which language the app starts in, so the mapping
 * is worth testing: it is the one part of the picker that has no screen to look at.
 */
class LocalesTest {

    @Test
    fun `a device language the app ships is used as is`() {
        assertEquals("ru", Locales.match("ru-RU"))
        assertEquals("ru", Locales.match("ru"))
        assertEquals("uk", Locales.match("uk-UA"))
        assertEquals("be", Locales.match("be-BY"))
        assertEquals("kk", Locales.match("kk-KZ"))
        assertEquals("en", Locales.match("en-GB"))
        assertEquals("es", Locales.match("es-419"))
        assertEquals("de", Locales.match("de-AT"))
        assertEquals("fi", Locales.match("fi-FI"))
        assertEquals("hi", Locales.match("hi-IN"))
        assertEquals("ja", Locales.match("ja-JP"))
    }

    @Test
    fun `a script or a region does not change the language`() {
        assertEquals("zh", Locales.match("zh-Hans-CN"))
        assertEquals("zh", Locales.match("zh-Hant-TW"))
        assertEquals("ru", Locales.match("RU-ru"))
        assertEquals("uk", Locales.match("uk_UA"))
    }

    @Test
    fun `anything else falls back to the default resources`() {
        assertEquals(Locales.FALLBACK, Locales.match("fr-FR"))
        assertEquals(Locales.FALLBACK, Locales.match("pt"))
        assertEquals(Locales.FALLBACK, Locales.match(""))
        assertEquals(Locales.FALLBACK, Locales.match(null))
        assertTrue(Locales.SUPPORTED.contains(Locales.FALLBACK))
    }

    @Test
    fun `every shipped language is listed once and has a name`() {
        assertEquals(Locales.SUPPORTED.size, Locales.SUPPORTED.toSet().size)
        for (tag in Locales.SUPPORTED) {
            assertTrue("no name for $tag", Locales.labelRes(tag) != 0)
        }
    }
}
