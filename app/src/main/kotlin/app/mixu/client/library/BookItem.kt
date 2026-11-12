// SPDX-License-Identifier: MPL-2.0
package app.mixu.client.library

/**
 * Domain representation of an imported narrative work in the reader library.
 *
 * @property id Unique identifier for the book or edition.
 * @property title The full title of the literary work.
 * @property author Author or creator attribution.
 * @property coverPlaceholderColor 32-bit ARGB color value for cover generation.
 * @property totalChapters Total number of chapters or major narrative divisions.
 * @property lastReadChapterTitle Title or label of the most recently accessed chapter.
 * @property lastReadOrdinal Ordinal index of the most recently read chapter (1-based, 0 if unread).
 * @property progressPercent Reading progress integer in range 0..100.
 * @property lastReadEpochMs Timestamp of the most recent reading session in milliseconds.
 */
data class BookItem(
    val id: String,
    val title: String,
    val author: String,
    val coverPlaceholderColor: Long = 0xFF1E3A8AL,
    val totalChapters: Int = 0,
    val lastReadChapterTitle: String? = null,
    val lastReadOrdinal: Int = 0,
    val progressPercent: Int = 0,
    val lastReadEpochMs: Long = 0L,
    val isFavorite: Boolean = false,
    val collections: Set<String> = emptySet(),
    val tags: List<String> = emptyList(),
    val coverPath: String? = null,
) {
    val isStarted: Boolean
        get() = progressPercent > 0 || lastReadOrdinal > 0

    val isCompleted: Boolean
        get() = progressPercent >= 100

    val initialLetter: String
        get() = title.firstOrNull()?.uppercase() ?: "?"

    companion object {
        const val FAVORITES_FILTER_KEY = "__FAVORITES__"

        fun defaultCollectionsFor(
            id: String,
            title: String,
            author: String,
        ): Set<String> {
            val combined = "$id $title $author".lowercase()
            val tags = linkedSetOf<String>()
            if (combined.contains("christie") ||
                combined.contains("ackroyd") ||
                combined.contains("styles") ||
                combined.contains("doyle") ||
                combined.contains("baskerville") ||
                combined.contains("poe") ||
                combined.contains("morgue") ||
                combined.contains("leroux") ||
                combined.contains("yellow")
            ) {
                tags.add("欧美黄金古典")
            }
            if (combined.contains("clocktower") ||
                combined.contains("钟塔") ||
                combined.contains("pavilion") ||
                combined.contains("雾越") ||
                combined.contains("manor") ||
                combined.contains("庄园") ||
                combined.contains("blackwood")
            ) {
                tags.add("暴风雪山庄 & 馆系列")
            }
            if (combined.contains("森博嗣") ||
                combined.contains("全部成为") ||
                combined.contains("train") ||
                combined.contains("夜行")
            ) {
                tags.add("新本格 & 时刻表")
            }
            if (tags.isEmpty()) {
                tags.add("本格推理")
            }
            return tags
        }

        val SAFE_THEME_AND_GENRE_TAGS =
            listOf(
                "校园",
                "恋爱",
                "青春",
                "历史",
                "科幻设定",
                "奇幻架空",
                "恐怖",
                "民俗怪谈",
                "日常之谜",
                "密室杀人",
                "暴风雪山庄",
                "不在场证明",
                "孤岛与馆系列",
                "时刻表诡计",
                "新本格",
                "社会派",
                "黄金古典",
                "童谣杀人",
                "无人生还模式",
                "本格推理",
            )

        val SPOILER_GUARDED_TRICK_TAGS =
            setOf(
                "叙述性诡计",
                "Meta元叙事",
                "实体书机关",
                "多重伪解答",
                "时间循环诡计",
                "逆转终局",
            )

        val PRESET_MYSTERY_TAGS =
            SAFE_THEME_AND_GENRE_TAGS + SPOILER_GUARDED_TRICK_TAGS

        /**
         * Two-tier progress-gated tag inference:
         * - When [isCompleted] is `false` (`progressPercent < 100`), returns only safe theme,
         *   setting, and subgenre tags (`校园`, `恋爱`, `历史`, `科幻设定`, `奇幻架空`, `恐怖`, etc.)
         *   and strictly withholds [SPOILER_GUARDED_TRICK_TAGS] (`叙述性诡计`, `Meta元叙事`, etc.).
         * - When [isCompleted] is `true` (`progressPercent >= 100`), unlocks and appends the
         *   core trick/meta tags.
         *
         * Time complexity: O(K) where K is keyword pattern count. Space complexity: O(T).
         */
        fun spoilerSafeTagsFor(
            id: String,
            title: String,
            author: String,
            isCompleted: Boolean = false,
        ): List<String> {
            val result = LinkedHashSet<String>()
            val cleanAuthor =
                author
                    .trim()
                    .replace(Regex("^\\[[^\\]]+\\]\\s*"), "")
                    .replace(Regex("^【[^】]+】\\s*"), "")
            if (cleanAuthor.isNotBlank() &&
                !cleanAuthor.equals("Unknown Author", ignoreCase = true) &&
                !cleanAuthor.equals("未知作者", ignoreCase = true)
            ) {
                result.add(cleanAuthor)
            }
            val combined = "$id $title $author".lowercase()

            // 1. Safe cross-genre & atmosphere tags (safe for UNREAD / READING books)
            if (combined.contains("冰菓") ||
                combined.contains("冰果") ||
                combined.contains("米泽穗信") ||
                combined.contains("时间停止的校园") ||
                combined.contains("校舍") ||
                combined.contains("校园") ||
                combined.contains("解密由我") ||
                combined.contains("放学后") ||
                combined.contains("古典部")
            ) {
                result.add("校园")
                result.add("青春")
            }
            if (combined.contains("冰菓") ||
                combined.contains("冰果") ||
                combined.contains("米泽穗信") ||
                combined.contains("解密由我") ||
                combined.contains("日常") ||
                combined.contains("咖啡")
            ) {
                result.add("日常之谜")
            }
            if (combined.contains("爱的成人式") ||
                combined.contains("干胡桃") ||
                combined.contains("樱树抽芽") ||
                combined.contains("歌野晶午") ||
                combined.contains("恋爱") ||
                combined.contains("情书")
            ) {
                result.add("恋爱")
                result.add("青春")
            }
            if (combined.contains("时间的女儿") ||
                combined.contains("铁伊") ||
                combined.contains("tey") ||
                combined.contains("大唐狄公案") ||
                combined.contains("高罗佩") ||
                combined.contains("gulik") ||
                combined.contains("历史") ||
                combined.contains("长安")
            ) {
                result.add("历史")
            }
            if (combined.contains("死了七次的男人") ||
                combined.contains("西泽保彦") ||
                combined.contains("人格转移") ||
                combined.contains("科幻") ||
                combined.contains("机械") ||
                combined.contains("时间机器")
            ) {
                result.add("科幻设定")
            }
            if (combined.contains("折断的龙骨") ||
                combined.contains("米泽穗信") &&
                combined.contains("龙骨") ||
                combined.contains("魔女狩猎") ||
                combined.contains("纸城境介") ||
                combined.contains("奇幻") ||
                combined.contains("魔法") ||
                combined.contains("架空")
            ) {
                result.add("奇幻架空")
            }
            if (combined.contains("三津田信三") ||
                combined.contains("作祟") ||
                combined.contains("刀城言耶") ||
                combined.contains("恐怖") ||
                combined.contains("怪谈") ||
                combined.contains("京极夏彦") ||
                combined.contains("姑获鸟") ||
                combined.contains("魍魉")
            ) {
                result.add("恐怖")
                result.add("民俗怪谈")
            }

            // 2. Safe classic mystery subgenre tags
            if (combined.contains("密室") ||
                combined.contains("yellow") ||
                combined.contains("黄色房间") ||
                combined.contains("morgue") ||
                combined.contains("莫格街") ||
                combined.contains("全部成为") ||
                combined.contains("钟塔") ||
                combined.contains("clocktower") ||
                combined.contains("龙骨")
            ) {
                result.add("密室杀人")
            }
            if (combined.contains("暴风雪") ||
                combined.contains("雾越") ||
                combined.contains("pavilion") ||
                combined.contains("庄园") ||
                combined.contains("manor") ||
                combined.contains("馆") ||
                combined.contains("孤岛") ||
                combined.contains("棺") ||
                combined.contains("时间停止的校园") ||
                combined.contains("死了七次的男人") ||
                combined.contains("歪曲创世纪")
            ) {
                result.add("暴风雪山庄")
            }
            if (combined.contains("train") ||
                combined.contains("夜行") ||
                combined.contains("不在场") ||
                combined.contains("时刻表")
            ) {
                result.add("不在场证明")
            }
            if (combined.contains("christie") ||
                combined.contains("ackroyd") ||
                combined.contains("styles") ||
                combined.contains("doyle") ||
                combined.contains("baskerville") ||
                combined.contains("poe") ||
                combined.contains("leroux")
            ) {
                result.add("黄金古典")
            }
            if (combined.contains("森博嗣") ||
                combined.contains("全部成为") ||
                combined.contains("博士") ||
                combined.contains("数学") ||
                combined.contains("馆") ||
                combined.contains("道尾秀介") ||
                combined.contains("向日葵") ||
                combined.contains("歪曲创世纪") ||
                combined.contains("积木镜介")
            ) {
                result.add("新本格")
            }
            if (combined.contains("1367") ||
                combined.contains("13.67") ||
                combined.contains("陈浩基") ||
                combined.contains("临场") ||
                combined.contains("鲸鱼")
            ) {
                result.add("社会派")
            }
            if (result.size < 2) {
                result.add("本格推理")
            }

            // 3. Spoiler-guarded trick tags: unlocked ONLY when isCompleted == true (progress == 100%)
            if (isCompleted) {
                if (combined.contains("ackroyd") ||
                    combined.contains("罗杰") ||
                    combined.contains("二重身") ||
                    combined.contains("悲剧") ||
                    combined.contains("杀意") ||
                    combined.contains("三幕") ||
                    combined.contains("爱的成人式") ||
                    combined.contains("干胡桃") ||
                    combined.contains("樱树抽芽") ||
                    combined.contains("歌野晶午") ||
                    combined.contains("向日葵") ||
                    combined.contains("道尾秀介") ||
                    combined.contains("作祟")
                ) {
                    result.add("叙述性诡计")
                }
                if (combined.contains("歪曲创世纪") ||
                    combined.contains("积木镜介") ||
                    combined.contains("meta")
                ) {
                    result.add("Meta元叙事")
                }
                if (combined.contains("幸福之书") ||
                    combined.contains("泡坂妻夫")
                ) {
                    result.add("实体书机关")
                    result.add("Meta元叙事")
                }
                if (combined.contains("死了七次的男人") ||
                    combined.contains("西泽保彦")
                ) {
                    result.add("时间循环诡计")
                }
                if (combined.contains("魔女狩猎") ||
                    combined.contains("毒巧克力") ||
                    combined.contains("伪解答")
                ) {
                    result.add("多重伪解答")
                }
            } else {
                result.removeAll(SPOILER_GUARDED_TRICK_TAGS)
            }
            return result.toList()
        }

        fun defaultTagsFor(
            id: String,
            title: String,
            author: String,
        ): List<String> =
            spoilerSafeTagsFor(id = id, title = title, author = author, isCompleted = false)

        val SAMPLE_BOOKS =
            listOf(
                BookItem(
                    id = "urn:mixu:book:styles",
                    title = "The Mysterious Affair at Styles",
                    author = "Agatha Christie",
                    coverPlaceholderColor = 0xFF1E3A8AL,
                    totalChapters = 13,
                    lastReadChapterTitle = "Chapter 4: Poirot Investigates",
                    lastReadOrdinal = 4,
                    progressPercent = 35,
                    lastReadEpochMs = 1714800000000L,
                ),
                BookItem(
                    id = "urn:mixu:book:baskervilles",
                    title = "The Hound of the Baskervilles",
                    author = "Sir Arthur Conan Doyle",
                    coverPlaceholderColor = 0xFF2E7D32L,
                    totalChapters = 15,
                    lastReadChapterTitle = "Chapter 11: The Man on the Tor",
                    lastReadOrdinal = 11,
                    progressPercent = 73,
                    lastReadEpochMs = 1714886400000L,
                ),
                BookItem(
                    id = "urn:mixu:book:morgue",
                    title = "The Murders in the Rue Morgue",
                    author = "Edgar Allan Poe",
                    coverPlaceholderColor = 0xFF8E24AAL,
                    totalChapters = 3,
                    lastReadChapterTitle = "Part 3: The Confession of the Sailor",
                    lastReadOrdinal = 3,
                    progressPercent = 100,
                    lastReadEpochMs = 1714713600000L,
                ),
                BookItem(
                    id = "urn:mixu:book:red-headed",
                    title = "The Red-Headed League",
                    author = "Sir Arthur Conan Doyle",
                    coverPlaceholderColor = 0xFFD84315L,
                    totalChapters = 4,
                    lastReadChapterTitle = "Chapter 1: The Jabez Wilson Case",
                    lastReadOrdinal = 1,
                    progressPercent = 25,
                    lastReadEpochMs = 1714627200000L,
                ),
                BookItem(
                    id = "urn:mixu:book:moonstone",
                    title = "The Moonstone",
                    author = "Wilkie Collins",
                    coverPlaceholderColor = 0xFF37474FL,
                    totalChapters = 27,
                    lastReadChapterTitle = null,
                    lastReadOrdinal = 0,
                    progressPercent = 0,
                    lastReadEpochMs = 0L,
                ),
            )
    }
}
