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
    fun `lookupWord resolves stemmed headword in offline and online modes`() {
        val offlineResult =
            LookupAndTranslationEngine.lookupWord(
                query = "alibis,",
                providerMode = LookupProviderMode.OFFLINE_LOCAL,
            )
        assertEquals("alibi", offlineResult.headword)
        assertTrue(offlineResult.isOfflineSource)
        assertTrue(offlineResult.primaryDefinition.contains("不在场证明"))
        assertNotNull(offlineResult.mysteryContextNote)

        val onlineResult =
            LookupAndTranslationEngine.lookupWord(
                query = "mustache",
                providerMode = LookupProviderMode.ONLINE_API,
                selfHostedEnabled = true,
                selfHostedUrl = "https://api.reader-node.example.com",
            )
        assertEquals("mustache", onlineResult.headword)
        assertFalse(onlineResult.isOfflineSource)
        assertTrue(onlineResult.providerLabel.contains("https://api.reader-node.example.com"))
        assertTrue(onlineResult.primaryDefinition.contains("语境深度解析"))
    }

    @Test
    fun `translatePassage returns offline-only or hybrid offline plus online translations`() {
        val sample =
            "\"My dear Caroline,\" I said. \"There's no doubt at all about what the man's profession has been. Look at that mustache of his.\""

        val offlineOnly =
            LookupAndTranslationEngine.translatePassage(
                passage = sample,
                providerMode = LookupProviderMode.OFFLINE_LOCAL,
            )
        assertTrue(offlineOnly.offlineTranslation.contains("离线直译"))
        assertNull(offlineOnly.onlineTranslation)

        val hybrid =
            LookupAndTranslationEngine.translatePassage(
                passage = sample,
                providerMode = LookupProviderMode.HYBRID_AUTO,
            )
        assertTrue(hybrid.offlineTranslation.contains("离线直译"))
        assertNotNull(hybrid.onlineTranslation)
        assertTrue(hybrid.onlineTranslation.contains("在线文学润色"))
    }
}
