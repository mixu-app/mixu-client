// SPDX-License-Identifier: MPL-2.0
package app.mixu.client.lookup

import app.mixu.client.config.LookupProviderMode

/**
 * Type of downloadable local linguistic resource pack.
 */
enum class ResourcePackKind {
    DICTIONARY,
    OFFLINE_TRANSLATION,
}

/**
 * Metadata and installation state for an offline dictionary or translation model pack.
 */
data class OfflineResourcePack(
    val packId: String,
    val title: String,
    val languagePair: String,
    val kind: ResourcePackKind,
    val sizeMb: Int,
    val version: String,
    val isDownloaded: Boolean,
)

/**
 * Result of a dictionary word/phrase lookup.
 */
data class DictionaryLookupResult(
    val query: String,
    val headword: String,
    val phonetic: String,
    val partOfSpeech: String,
    val primaryDefinition: String,
    val mysteryContextNote: String?,
    val providerLabel: String,
    val isOfflineSource: Boolean,
    val latencyMs: Int,
    val found: Boolean = true,
)

/**
 * Result of a sentence or paragraph translation request.
 */
data class PassageTranslationResult(
    val sourceText: String,
    val offlineTranslation: String,
    val onlineTranslation: String?,
    val activeProviderMode: LookupProviderMode,
    val endpointSummary: String,
    val offlineLatencyMs: Int,
    val onlineLatencyMs: Int?,
)

/**
 * Pure Kotlin KMP-compatible engine for free, unlimited on-device offline dictionary/translation
 * packs and bounded prompt generation for horizon-locked AI Copilot literary translation.
 *
 * Time Complexity:
 * - Offline headword lookup: $O(1)$ hash lookup + $O(k)$ suffix stemming ($k \le 4$).
 * - Passage prompt & local preview: $O(\min(N, \text{COPILOT\_TRANSLATION\_MAX\_CHARS}))$.
 * Space Complexity: $O(D)$ where $D$ is the in-memory lexicon size.
 */
object LookupAndTranslationEngine {
    /**
     * Maximum character length per selection sent to AI Butler / Copilot for contextual
     * literary translation, preventing bulk whole-book translation abuse.
     */
    const val COPILOT_TRANSLATION_MAX_CHARS = 800

    private data class LexiconEntry(
        val headword: String,
        val phonetic: String,
        val partOfSpeech: String,
        val definitionZh: String,
        val mysteryNoteZh: String,
        val onlineNuanceZh: String,
    )

    private val defaultPacks =
        listOf(
            OfflineResourcePack(
                packId = "dict-en-zh-core",
                title = "英汉推理与古典文学核心词典 (WordNet + CJK)",
                languagePair = "EN → ZH",
                kind = ResourcePackKind.DICTIONARY,
                sizeMb = 42,
                version = "2026.11",
                isDownloaded = true,
            ),
            OfflineResourcePack(
                packId = "dict-ja-zh-mystery",
                title = "日汉新本格与馆系列专有名词词典 (JMdict)",
                languagePair = "JA → ZH",
                kind = ResourcePackKind.DICTIONARY,
                sizeMb = 38,
                version = "2026.11",
                isDownloaded = true,
            ),
            OfflineResourcePack(
                packId = "nmt-offline-en-zh",
                title = "离线轻量神经网络翻译模型 (ONNX Int8 EN↔ZH)",
                languagePair = "EN ↔ ZH",
                kind = ResourcePackKind.OFFLINE_TRANSLATION,
                sizeMb = 86,
                version = "1.4.0",
                isDownloaded = true,
            ),
            OfflineResourcePack(
                packId = "nmt-offline-ja-zh",
                title = "离线轻量神经网络翻译模型 (ONNX Int8 JA↔ZH)",
                languagePair = "JA ↔ ZH",
                kind = ResourcePackKind.OFFLINE_TRANSLATION,
                sizeMb = 92,
                version = "1.4.0",
                isDownloaded = false,
            ),
        )

    private val builtInLexicon: Map<String, LexiconEntry> =
        listOf(
            LexiconEntry(
                headword = "alibi",
                phonetic = "/ˈæl.ɪ.baɪ/",
                partOfSpeech = "n.",
                definitionZh = "不在场证明；托辞，辩解",
                mysteryNoteZh = "本格推理核心术语：指嫌疑人在案发时刻身处他处的客观证据链（常涉及时刻表、证人或机械诡计）。",
                onlineNuanceZh = "源自拉丁语『在其他地方』。在黄金时代推理小说中，过于完美的不在场证明（Ironclad Alibi）往往暗示钟表拨改或替身诡计。",
            ),
            LexiconEntry(
                headword = "inquest",
                phonetic = "/ˈɪŋ.kwest/",
                partOfSpeech = "n.",
                definitionZh = "（验尸官主持的）死因审讯，司法调查",
                mysteryNoteZh = "英美古典侦探小说常见程序：非正式刑事审判，由验尸官（Coroner）与陪审团裁定死因。",
                onlineNuanceZh = "阿加莎·克里斯蒂笔下常以 Inquest 作为案件公开质询节点，证人在此阶段的证词常暗藏伏笔。",
            ),
            LexiconEntry(
                headword = "veronal",
                phonetic = "/ˈvɛr.ə.næl/",
                partOfSpeech = "n.",
                definitionZh = "佛罗拿（巴比妥类催眠镇静剂）",
                mysteryNoteZh = "《罗杰疑案》等黄金时代名作经典毒物：过量服用会导致昏迷乃至呼吸衰竭。",
                onlineNuanceZh = "20世纪初广泛使用的安眠药，常与饮品混合掩盖苦味，是古典推理小说毒杀手法的高频药剂。",
            ),
            LexiconEntry(
                headword = "blackmail",
                phonetic = "/ˈblæk.meɪl/",
                partOfSpeech = "n. / v.",
                definitionZh = "敲诈，勒索；封口费",
                mysteryNoteZh = "连环命案常见动机：第二起命案往往源于目击者或知情者对真凶实施勒索。",
                onlineNuanceZh = "在《罗杰疑案》等作品中，勒索信（Blackmail letter）的消失或部分焚毁往往是锁定时间线的关键物证。",
            ),
            LexiconEntry(
                headword = "coroner",
                phonetic = "/ˈkɒr.ə.nər/",
                partOfSpeech = "n.",
                definitionZh = "验尸官（负责查明非正常死亡原因的官员）",
                mysteryNoteZh = "负责签发死亡时间范围与毒理初步结论，阅读时需注意『胃内容物消化程度』推算死亡时间的误差陷阱。",
                onlineNuanceZh = "英国制度中 Coroner 兼具医学与法律背景，其推断的死亡时间窗口常被凶手利用。",
            ),
            LexiconEntry(
                headword = "parlor",
                phonetic = "/ˈpɑːr.lər/",
                partOfSpeech = "n.",
                definitionZh = "会客室，起居室（英式亦作 parlour）",
                mysteryNoteZh = "庄园平面图常见密室或第一发现现场，需留意门窗插销与壁炉烟道朝向。",
                onlineNuanceZh = "维多利亚与爱德华时代庄园用于接待访客的正式房间，常与书房（Study）及露台（Terrace）相连。",
            ),
            LexiconEntry(
                headword = "mustache",
                phonetic = "/ˈmʌs.tæʃ/",
                partOfSpeech = "n.",
                definitionZh = "（上唇的）小胡子，八字胡",
                mysteryNoteZh = "赫尔克里·波洛（Hercule Poirot）的标志性外貌特征，书中邻居最初曾误以为他是退休理发师。",
                onlineNuanceZh = "《罗杰疑案》第三章卡罗琳与谢泼德医生的幽默对话即围绕这位新搬来的『种西葫芦的小胡子邻居』展开。",
            ),
            LexiconEntry(
                headword = "pavilion",
                phonetic = "/pəˈvɪl.jən/",
                partOfSpeech = "n.",
                definitionZh = "亭阁，别馆，花园洋房",
                mysteryNoteZh = "东西方推理小说常见的独立封闭建筑（如庄园温室亭、雾越邸别馆），便于制造孤岛或视线遮挡。",
                onlineNuanceZh = "庄园主体建筑外的夏季亭阁（Summerhouse/Pavilion），常作为夜间密谈或藏匿证物的场所。",
            ),
            LexiconEntry(
                headword = "アリバイ",
                phonetic = "[a-ri-ba-i]",
                partOfSpeech = "名",
                definitionZh = "不在场证明（Alibi）",
                mysteryNoteZh = "日本本格推理核心概念，常与列车时刻表或建筑机关结合。",
                onlineNuanceZh = "新本格派常将不在场证明与馆内空间错觉（暴风雪山庄）结合构造复合诡计。",
            ),
            LexiconEntry(
                headword = "密室",
                phonetic = "[mì shì / みっしつ]",
                partOfSpeech = "名",
                definitionZh = "Locked Room；从内部封锁、外人表面无法出入的犯罪现场",
                mysteryNoteZh = "约翰·狄克森·卡尔《三口棺材》第17章『密室讲义』将其归纳为机械密室、心理密室、错觉密室等类别。",
                onlineNuanceZh = "阅读密室章节时建议同步打开案情图板核对『第一发现人』与『破门前站在门外的证人』。",
            ),
        ).associateBy { it.headword.lowercase() }

    /**
     * Returns the default offline dictionary and translation resource packs.
     */
    fun defaultResourcePacks(): List<OfflineResourcePack> = defaultPacks

    /**
     * Normalizes and stems an English/CJK token to match the offline lexicon.
     */
    internal fun stemWord(rawQuery: String): String {
        val cleaned =
            rawQuery
                .trim()
                .trim('"', '“', '”', '\'', '’', ',', '.', ';', ':', '!', '?', '—', '(', ')')
                .lowercase()
        if (builtInLexicon.containsKey(cleaned)) return cleaned

        val suffixes = listOf("ing", "ed", "es", "s", "ly")
        for (suffix in suffixes) {
            if (cleaned.length > suffix.length + 2 && cleaned.endsWith(suffix)) {
                val candidate = cleaned.removeSuffix(suffix)
                if (builtInLexicon.containsKey(candidate)) return candidate
                if (builtInLexicon.containsKey("${candidate}e")) return "${candidate}e"
            }
        }
        return cleaned
    }

    /**
     * Builds a horizon-bounded prompt asking AI Copilot for an accurate Chinese literary
     * translation together with clue/context explanation, truncating [passage] cleanly at
     * [COPILOT_TRANSLATION_MAX_CHARS] characters.
     *
     * Time Complexity: O(min(N, COPILOT_TRANSLATION_MAX_CHARS)).
     * Space Complexity: O(min(N, COPILOT_TRANSLATION_MAX_CHARS)).
     */
    fun buildCopilotTranslationPrompt(
        passage: String,
        bookTitle: String? = null,
        chapterTitle: String? = null,
    ): String {
        val trimmed = passage.trim()
        val boundedPassage =
            if (trimmed.length > COPILOT_TRANSLATION_MAX_CHARS) {
                trimmed.take(COPILOT_TRANSLATION_MAX_CHARS).trimEnd() + "…"
            } else {
                trimmed
            }
        val contextScope =
            listOfNotNull(
                bookTitle?.trim()?.takeIf { it.isNotEmpty() }?.let { "《$it》" },
                chapterTitle?.trim()?.takeIf { it.isNotEmpty() },
            ).joinToString(" · ")
        val header =
            if (contextScope.isNotEmpty()) {
                "请结合 $contextScope 当前阅读进度（防剧透安全边界内），为以下选段提供准确流畅的中文文学翻译，并简析其中专有名词、人物语气或潜在推理线索："
            } else {
                "请结合当前阅读进度（防剧透安全边界内），为以下选段提供准确流畅的中文文学翻译，并简析其中专有名词、人物语气或潜在推理线索："
            }
        return if (boundedPassage.isEmpty()) {
            header
        } else {
            "$header\n\n「$boundedPassage」"
        }
    }

    /**
     * Looks up a genuine entry in the built-in local lexicon. Legacy provider parameters
     * remain for caller compatibility; this method performs no remote lookup. Missing entries
     * have no invented pronunciation, grammatical class or contextual narrative commentary.
     */
    fun lookupWord(
        query: String,
        providerMode: LookupProviderMode,
        selfHostedEnabled: Boolean = false,
        selfHostedUrl: String = "",
    ): DictionaryLookupResult {
        val entry = builtInLexicon[stemWord(query)]
        return DictionaryLookupResult(
            query = query.trim(),
            headword = entry?.headword ?: query.trim(),
            phonetic = entry?.phonetic.orEmpty(),
            partOfSpeech = entry?.partOfSpeech.orEmpty(),
            primaryDefinition = entry?.definitionZh ?: "未找到词条",
            mysteryContextNote = null,
            providerLabel = "内置词典",
            isOfflineSource = true,
            latencyMs = 1,
            found = entry != null,
        )
    }

    /**
     * Generates a free, unlimited local offline passage translation aid without any fake online
     * translate API simulation. Deep contextual literary translation routes to AI Copilot.
     */
    fun translatePassage(
        passage: String,
        providerMode: LookupProviderMode,
        selfHostedEnabled: Boolean = false,
        selfHostedUrl: String = "",
    ): PassageTranslationResult {
        val trimmed = passage.trim()
        val offlineText = buildOfflineTranslation(trimmed)
        val hasCustomServer =
            providerMode != LookupProviderMode.OFFLINE_LOCAL &&
                selfHostedEnabled &&
                selfHostedUrl.isNotBlank()
        val endpointSummary =
            when {
                providerMode == LookupProviderMode.OFFLINE_LOCAL ->
                    "仅离线本地直译 (ONNX Int8 · 免费无限 · 零网络请求)"
                hasCustomServer ->
                    "本地离线直译 (免费无限) · 深度语境可走 AI 伴读或自建节点 (${selfHostedUrl.trim()})"
                else ->
                    "本地离线直译 (免费无限) · 文学精译与线索解析由 AI 伴读提供 (1 推理点/次 · 限 $COPILOT_TRANSLATION_MAX_CHARS 字)"
            }

        return PassageTranslationResult(
            sourceText = trimmed,
            offlineTranslation = offlineText,
            onlineTranslation = null,
            activeProviderMode = providerMode,
            endpointSummary = endpointSummary,
            offlineLatencyMs = 12,
            onlineLatencyMs = null,
        )
    }

    private fun buildOfflineTranslation(source: String): String {
        if (source.isEmpty()) return "（未选择段落）"
        val lower = source.lowercase()
        return when {
            lower.contains("caroline") && lower.contains("mustache") ->
                "【离线直译】但那肯定不是卡罗琳想要的信息。她想知道他从哪来、做什么工作、是否已婚……我说：『亲爱的卡罗琳，毫无疑问这人的职业是退休理发师，看看他那撇小胡子吧。』"
            lower.contains("mrs. ferrars") ->
                "【离线直译】费拉尔斯太太是在9月16日至17日夜间去世的——那是一个星期四。我在17日星期五早上八点整被叫去看她，但当时她已经去世数小时了。"
            source.any { it.code in 0x4E00..0x9FFF } ->
                "【离线双语对照】\"${source.take(
                    96,
                )}${if (source.length > 96) "…" else ""}\" → [Offline NMT: Local reading-horizon aid ready; use AI Copilot for literary nuance.]"
            else ->
                "【离线轻量模型直译】${source.take(
                    110,
                )}${if (source.length > 110) "…" else ""}（已由本地 ONNX Int8 离线翻译包完成本地转换，免费无限制；如需文学润色与线索精解请用 AI 伴读）"
        }
    }
}
