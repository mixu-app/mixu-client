// SPDX-License-Identifier: MPL-2.0
package app.mixu.client.lookup

import app.mixu.client.config.LookupProviderMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LookupAndTranslationEngineTest {
    @Test
    fun `lookup returns genuine local entry without unsolicited narrative commentary`() {
        for (mode in LookupProviderMode.entries) {
            val result =
                LookupAndTranslationEngine.lookupWord(
                    query = "alibis,",
                    providerMode = mode,
                    selfHostedEnabled = true,
                    selfHostedUrl = "https://dictionary.invalid",
                )
            assertTrue(result.found)
            assertTrue(result.isOfflineSource)
            assertEquals("alibi", result.headword)
            assertEquals("不在场证明；托辞，辩解", result.primaryDefinition)
            assertNull(result.mysteryContextNote)
        }
    }

    @Test
    fun `unknown proper name and blank query do not invent dictionary fields or remote results`() {
        for (query in listOf("Velmora", "")) {
            for (mode in LookupProviderMode.entries) {
                val result =
                    LookupAndTranslationEngine.lookupWord(
                        query,
                        mode,
                        selfHostedEnabled = true,
                        selfHostedUrl = "https://dictionary.invalid",
                    )
                assertFalse(result.found)
                assertEquals(query, result.headword)
                assertEquals("", result.phonetic)
                assertEquals("", result.partOfSpeech)
                assertEquals("未找到词条", result.primaryDefinition)
                assertNull(result.mysteryContextNote)
                assertTrue(result.isOfflineSource)
            }
        }
    }

    @Test
    fun `translatePassage keeps offline aid free and removes fake online translation mock`() {
        val sample =
            "\"My dear Caroline,\" I said. \"There's no doubt at all about what the man's profession has been. Look at that mustache of his.\""

        val offlineOnly =
            LookupAndTranslationEngine.translatePassage(
                passage = sample,
                providerMode = LookupProviderMode.OFFLINE_LOCAL,
            )
        assertTrue(offlineOnly.offlineTranslation.contains("离线直译"))
        assertNull(offlineOnly.onlineTranslation)
        assertNull(offlineOnly.onlineLatencyMs)
        assertTrue(offlineOnly.endpointSummary.contains("免费无限"))

        val hybrid =
            LookupAndTranslationEngine.translatePassage(
                passage = sample,
                providerMode = LookupProviderMode.HYBRID_AUTO,
            )
        assertTrue(hybrid.offlineTranslation.contains("离线直译"))
        assertNull(hybrid.onlineTranslation)
        assertNull(hybrid.onlineLatencyMs)
        assertTrue(hybrid.endpointSummary.contains("AI 伴读"))
        assertTrue(hybrid.endpointSummary.contains("1 推理点/次"))
    }

    @Test
    fun `buildCopilotTranslationPrompt includes horizon context and truncates at 800 chars`() {
        val shortPrompt =
            LookupAndTranslationEngine.buildCopilotTranslationPrompt(
                passage = "Mrs. Ferrars died on the night of the 16th-17th September.",
                bookTitle = "The Murder of Roger Ackroyd",
                chapterTitle = "Chapter 1",
            )
        assertTrue(shortPrompt.contains("《The Murder of Roger Ackroyd》"))
        assertTrue(shortPrompt.contains("Chapter 1"))
        assertTrue(shortPrompt.contains("防剧透"))
        assertTrue(shortPrompt.contains("中文文学翻译"))
        val expectedExcerpt = "Mrs. Ferrars died on the night of the 16th-17th September."
        assertTrue(shortPrompt.contains(expectedExcerpt))

        val prefix800 = "A".repeat(LookupAndTranslationEngine.COPILOT_TRANSLATION_MAX_CHARS)
        val overflowSuffix = "B".repeat(250)
        val longPrompt =
            LookupAndTranslationEngine.buildCopilotTranslationPrompt(
                passage = "  $prefix800$overflowSuffix  ",
            )
        assertEquals(800, LookupAndTranslationEngine.COPILOT_TRANSLATION_MAX_CHARS)
        assertTrue(longPrompt.contains("「$prefix800…」"))
        assertFalse(longPrompt.contains("B"))

        val blankPrompt =
            LookupAndTranslationEngine.buildCopilotTranslationPrompt(
                passage = "   ",
                bookTitle = null,
                chapterTitle = null,
            )
        assertTrue(blankPrompt.contains("中文文学翻译"))
        assertFalse(blankPrompt.contains("「"))
    }
}
