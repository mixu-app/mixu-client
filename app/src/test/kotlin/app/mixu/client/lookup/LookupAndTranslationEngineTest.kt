// SPDX-License-Identifier: MPL-2.0
package app.mixu.client.lookup

import app.mixu.client.config.LookupProviderMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LookupAndTranslationEngineTest {
    @Test
    fun `lookupWord keeps offline lookup free and routes unknown context cleanly`() {
        val offlineResult =
            LookupAndTranslationEngine.lookupWord(
                query = "alibis,",
                providerMode = LookupProviderMode.OFFLINE_LOCAL,
            )
        assertEquals("alibi", offlineResult.headword)
        assertTrue(offlineResult.isOfflineSource)
        assertEquals(1, offlineResult.latencyMs)
        assertTrue(offlineResult.primaryDefinition.contains("不在场证明"))
        assertNotNull(offlineResult.mysteryContextNote)

        val hybridFallback =
            LookupAndTranslationEngine.lookupWord(
                query = "unlistedterm",
                providerMode = LookupProviderMode.HYBRID_AUTO,
            )
        assertEquals("unlistedterm", hybridFallback.headword)
        assertTrue(hybridFallback.isOfflineSource)
        assertTrue(hybridFallback.primaryDefinition.contains("免费无限制"))
        assertTrue(hybridFallback.mysteryContextNote.orEmpty().contains("AI 管家"))

        val selfHostedResult =
            LookupAndTranslationEngine.lookupWord(
                query = "mustache",
                providerMode = LookupProviderMode.ONLINE_API,
                selfHostedEnabled = true,
                selfHostedUrl = "https://api.reader-node.example.com",
            )
        assertEquals("mustache", selfHostedResult.headword)
        assertFalse(selfHostedResult.isOfflineSource)
        assertTrue(selfHostedResult.providerLabel.contains("https://api.reader-node.example.com"))
        assertTrue(selfHostedResult.primaryDefinition.contains("推理词库补充"))
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
