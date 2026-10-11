package app.lingleaf.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** SPEC S14 and "Translation language pick". The iOS app runs the same cases. */
class TranslationLanguagesTest {
    private val supported = listOf("es", "pt", "fr", "de", "ar", "zh", "ja", "th")

    @Test
    fun `picks the first preferred language that is supported`() {
        assertEquals("pt", TranslationLanguages.pick(listOf("pt-BR", "es-MX"), supported))
    }

    @Test
    fun `skips English`() {
        assertEquals("fr", TranslationLanguages.pick(listOf("en-US", "fr-FR"), supported))
        assertEquals("es", TranslationLanguages.pick(listOf("en-GB", "en-US", "es"), supported))
    }

    @Test
    fun `skips unsupported languages`() {
        assertEquals("de", TranslationLanguages.pick(listOf("sw-KE", "de-DE"), supported))
    }

    @Test
    fun `any Chinese becomes Simplified Chinese`() {
        assertEquals("zh", TranslationLanguages.pick(listOf("zh-Hant-TW"), supported))
        assertEquals("zh", TranslationLanguages.pick(listOf("zh-Hans-CN"), supported))
        assertEquals("zh-Hans", TranslationLanguages.pick(listOf("zh-Hant-HK"), listOf("es", "zh-Hans")))
    }

    @Test
    fun `accepts underscores and any case`() {
        assertEquals("es", TranslationLanguages.pick(listOf("ES_mx"), supported))
    }

    @Test
    fun `no candidate means ask`() {
        assertNull(TranslationLanguages.pick(listOf("en-US"), supported))
        assertNull(TranslationLanguages.pick(listOf("en-US", "sw-KE"), supported))
        assertNull(TranslationLanguages.pick(emptyList(), supported))
    }

    @Test
    fun `suggestions start with the region's language`() {
        assertEquals(listOf("pt", "es", "fr", "zh", "ar"), TranslationLanguages.suggestions("BR", supported))
        assertEquals(listOf("ja", "es", "pt", "fr", "zh"), TranslationLanguages.suggestions("JP", supported))
        assertEquals(listOf("ar", "es", "pt", "fr", "zh"), TranslationLanguages.suggestions("eg", supported))
    }

    @Test
    fun `suggestions don't repeat the region's language`() {
        assertEquals(listOf("es", "pt", "fr", "zh", "ar"), TranslationLanguages.suggestions("MX", supported))
        assertEquals(listOf("fr", "es", "pt", "zh", "ar"), TranslationLanguages.suggestions("FR", supported))
    }

    @Test
    fun `suggestions without a known region use the default order`() {
        assertEquals(listOf("es", "pt", "fr", "zh", "ar"), TranslationLanguages.suggestions("US", supported))
        assertEquals(listOf("es", "pt", "fr", "zh", "ar"), TranslationLanguages.suggestions(null, supported))
    }

    @Test
    fun `suggestions keep only supported languages`() {
        assertEquals(listOf("zh-Hans", "es"), TranslationLanguages.suggestions("CN", listOf("es", "zh-Hans")))
        assertEquals(listOf("es", "fr"), TranslationLanguages.suggestions("KR", listOf("es", "fr")))
        // Same region table as iOS: Belgium → Dutch, Chad → French, Singapore → Chinese.
        assertEquals(listOf("nl", "es", "fr"), TranslationLanguages.suggestions("BE", listOf("nl", "es", "fr")))
        assertEquals(listOf("fr", "es", "ar"), TranslationLanguages.suggestions("TD", listOf("fr", "es", "ar")))
        assertEquals(listOf("zh", "es"), TranslationLanguages.suggestions("SG", listOf("es", "zh")))
    }
}
