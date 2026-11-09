// SPDX-License-Identifier: MPL-2.0
package app.mixu.client.copilot

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StreamingMarkdownParserTest {
    @Test
    fun `healStreamingMarkdown auto-closes unclosed bold and backtick delimiters`() {
        val unclosedBold = "- **藏书总量"
        assertEquals("- **藏书总量**", StreamingMarkdownParser.healStreamingMarkdown(unclosedBold))

        val unclosedCode = "已写入 `sync_outbox"
        assertEquals(
            "已写入 `sync_outbox`",
            StreamingMarkdownParser.healStreamingMarkdown(unclosedCode),
        )

        val unclosedFence = "```kotlin\nval x = 1"
        assertEquals(
            "```kotlin\nval x = 1\n```",
            StreamingMarkdownParser.healStreamingMarkdown(unclosedFence),
        )
    }

    @Test
    fun `parseBlocks extracts headings lists tables quotes and code blocks`() {
        val md =
            """
            ### 图库与推理藏书结构
            - **藏书总量**：`16` 本
            - **核心诡计**：`#密室杀人`
            
            | 维度 | 状态 |
            |---|---|
            | 认知边界 | 第 1~1 节 |
            
            > 当前页焦点段落
            
            ```json
            {"tags": ["密室杀人"]}
            ```
            """.trimIndent()

        val blocks = StreamingMarkdownParser.parseBlocks(md)
        assertEquals(5, blocks.size)
        assertTrue(blocks[0] is MarkdownBlockNode.Heading)
        assertTrue(blocks[1] is MarkdownBlockNode.BulletList)
        assertTrue(blocks[2] is MarkdownBlockNode.TableBlock)
        assertTrue(blocks[3] is MarkdownBlockNode.QuoteBlock)
        assertTrue(blocks[4] is MarkdownBlockNode.CodeBlock)
    }

    @Test
    fun `parseInline styles bold inline code italic and strikethrough`() {
        val annotated =
            StreamingMarkdownParser.parseInline(
                text = "这是 **防剧透** 与 `sync_outbox` 以及 *推理* 和 ~~剧透~~",
                codeBackground = Color.LightGray,
                codeTextColor = Color.Blue,
                primaryColor = Color.Black,
            )
        assertTrue(annotated.text.contains("防剧透"))
        assertTrue(annotated.text.contains("sync_outbox"))
        assertTrue(annotated.spanStyles.size >= 4)
    }
}
