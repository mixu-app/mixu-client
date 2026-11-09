// SPDX-License-Identifier: MPL-2.0
@file:Suppress("ktlint:standard:function-naming")

package app.mixu.client.copilot

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mixu.client.theme.MixuTheme
import kotlinx.coroutines.delay

/**
 * Block-level AST nodes produced by [StreamingMarkdownParser] for zero-flicker incremental
 * Markdown rendering during token-by-token LLM streaming.
 */
sealed interface MarkdownBlockNode {
    data class Heading(
        val level: Int,
        val text: String,
    ) : MarkdownBlockNode

    data class Paragraph(
        val text: String,
    ) : MarkdownBlockNode

    data class BulletList(
        val items: List<String>,
    ) : MarkdownBlockNode

    data class CodeBlock(
        val language: String,
        val code: String,
    ) : MarkdownBlockNode

    data class QuoteBlock(
        val text: String,
    ) : MarkdownBlockNode

    data class TableBlock(
        val headers: List<String>,
        val rows: List<List<String>>,
    ) : MarkdownBlockNode
}

/**
 * Incremental streaming-safe Markdown parser & auto-closer.
 *
 * When an LLM streams tokens chunk-by-chunk, inline delimiters (`**`, `` ` ``, `~~`) or fenced
 * code blocks (```` ``` ````) at the active tail are often temporarily unclosed. Standard full-pass
 * parsers treat unclosed delimiters as literal characters until the closing delimiter arrives,
 * causing visual layout flicker. [healStreamingMarkdown] balances unclosed tail delimiters before
 * block/inline parsing in O(N) time and O(N) space.
 */
internal object StreamingMarkdownParser {
    fun healStreamingMarkdown(raw: String): String {
        if (raw.isEmpty()) return raw
        var result = raw
        // 1. Balance unclosed fenced code block ```
        val fenceCount = Regex("```").findAll(result).count()
        if (fenceCount % 2 == 1) {
            return "$result\n```"
        }
        // 2. Balance unclosed inline code backtick ` on the last line
        val lastLineStart = result.lastIndexOf('\n') + 1
        val lastLine = result.substring(lastLineStart)
        val backtickCount = lastLine.count { it == '`' }
        if (backtickCount % 2 == 1) {
            result += "`"
        }
        // 3. Balance unclosed bold ** on the last line
        val boldCount = Regex("\\*\\*").findAll(lastLine).count()
        if (boldCount % 2 == 1) {
            result += "**"
        }
        return result
    }

    fun parseBlocks(markdown: String): List<MarkdownBlockNode> {
        val healed = healStreamingMarkdown(markdown)
        val lines = healed.lines()
        val blocks = mutableListOf<MarkdownBlockNode>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                i++
                continue
            }

            // 1. Fenced Code Block
            if (trimmed.startsWith("```")) {
                val lang = trimmed.removePrefix("```").trim()
                val codeLines = mutableListOf<String>()
                i++
                while (i < lines.size && !lines[i].trim().startsWith("```")) {
                    codeLines.add(lines[i])
                    i++
                }
                if (i < lines.size) i++
                blocks.add(
                    MarkdownBlockNode.CodeBlock(
                        language = lang,
                        code = codeLines.joinToString("\n"),
                    ),
                )
                continue
            }

            // 2. Heading (#, ##, ###)
            if (trimmed.startsWith("#")) {
                val level = trimmed.takeWhile { it == '#' }.length.coerceIn(1, 4)
                val headingText = trimmed.drop(level).trim()
                blocks.add(MarkdownBlockNode.Heading(level = level, text = headingText))
                i++
                continue
            }

            // 3. GFM Table (| col1 | col2 |)
            if (trimmed.startsWith("|") &&
                trimmed.endsWith("|") &&
                trimmed.count { it == '|' } >= 3
            ) {
                val tableLines = mutableListOf<String>()
                while (i < lines.size &&
                    lines[i].trim().let { it.startsWith("|") && it.contains("|") }
                ) {
                    tableLines.add(lines[i].trim())
                    i++
                }
                val parsedRows =
                    tableLines
                        .filter { row ->
                            !row
                                .replace("|", "")
                                .replace("-", "")
                                .replace(":", "")
                                .isBlank()
                        }.map { row ->
                            row
                                .removePrefix("|")
                                .removeSuffix("|")
                                .split("|")
                                .map { it.trim() }
                        }
                if (parsedRows.isNotEmpty()) {
                    blocks.add(
                        MarkdownBlockNode.TableBlock(
                            headers = parsedRows.first(),
                            rows = parsedRows.drop(1),
                        ),
                    )
                }
                continue
            }

            // 4. Blockquote (> ...)
            if (trimmed.startsWith(">")) {
                val quoteLines = mutableListOf<String>()
                while (i < lines.size && lines[i].trim().startsWith(">")) {
                    quoteLines.add(lines[i].trim().removePrefix(">").trim())
                    i++
                }
                blocks.add(MarkdownBlockNode.QuoteBlock(quoteLines.joinToString("\n")))
                continue
            }

            // 5. Bullet or Numbered List (- , * , • , 1. )
            if (isListItemLine(trimmed)) {
                val items = mutableListOf<String>()
                while (i < lines.size && isListItemLine(lines[i].trim())) {
                    val itemText =
                        lines[i]
                            .trim()
                            .replaceFirst(Regex("^([-*•]|\\d+\\.)\\s*"), "")
                    items.add(itemText)
                    i++
                }
                blocks.add(MarkdownBlockNode.BulletList(items))
                continue
            }

            // 6. Standard Paragraph
            val paraLines = mutableListOf<String>()
            while (i < lines.size) {
                val cur = lines[i].trim()
                if (cur.isEmpty() ||
                    cur.startsWith("```") ||
                    cur.startsWith("#") ||
                    cur.startsWith(">") ||
                    (cur.startsWith("|") && cur.endsWith("|")) ||
                    isListItemLine(cur)
                ) {
                    break
                }
                paraLines.add(cur)
                i++
            }
            if (paraLines.isNotEmpty()) {
                blocks.add(MarkdownBlockNode.Paragraph(paraLines.joinToString("\n")))
            }
        }
        return blocks
    }

    private fun isListItemLine(trimmed: String): Boolean =
        trimmed.startsWith("• ") ||
            trimmed.startsWith("- ") ||
            trimmed.startsWith("* ") ||
            trimmed.matches(Regex("^\\d+\\.\\s+.*"))

    /**
     * Parses inline Markdown spans (`**bold**`, `*italic*`, `` `code` ``, `~~strike~~`) into an
     * [AnnotatedString] in O(N) time.
     */
    fun parseInline(
        text: String,
        codeBackground: Color,
        codeTextColor: Color,
        primaryColor: Color,
    ): AnnotatedString =
        buildAnnotatedString {
            var idx = 0
            while (idx < text.length) {
                when {
                    // Inline code `...`
                    text.startsWith("`", idx) -> {
                        val end = text.indexOf('`', idx + 1)
                        if (end > idx) {
                            val content = text.substring(idx + 1, end)
                            pushStyle(
                                SpanStyle(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    background = codeBackground,
                                    color = codeTextColor,
                                    fontWeight = FontWeight.Medium,
                                ),
                            )
                            append(" $content ")
                            pop()
                            idx = end + 1
                        } else {
                            append(text[idx])
                            idx++
                        }
                    }
                    // Bold **...**
                    text.startsWith("**", idx) -> {
                        val end = text.indexOf("**", idx + 2)
                        if (end > idx) {
                            val content = text.substring(idx + 2, end)
                            pushStyle(
                                SpanStyle(
                                    fontWeight = FontWeight.Bold,
                                    color = primaryColor,
                                ),
                            )
                            append(content)
                            pop()
                            idx = end + 2
                        } else {
                            append(text[idx])
                            idx++
                        }
                    }
                    // Strikethrough ~~...~~
                    text.startsWith("~~", idx) -> {
                        val end = text.indexOf("~~", idx + 2)
                        if (end > idx) {
                            val content = text.substring(idx + 2, end)
                            pushStyle(SpanStyle(textDecoration = TextDecoration.LineThrough))
                            append(content)
                            pop()
                            idx = end + 2
                        } else {
                            append(text[idx])
                            idx++
                        }
                    }
                    // Italic *...*
                    text.startsWith("*", idx) && !text.startsWith("**", idx) -> {
                        val end = text.indexOf('*', idx + 1)
                        if (end > idx) {
                            val content = text.substring(idx + 1, end)
                            pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                            append(content)
                            pop()
                            idx = end + 1
                        } else {
                            append(text[idx])
                            idx++
                        }
                    }
                    else -> {
                        append(text[idx])
                        idx++
                    }
                }
            }
        }
}

/**
 * Renders Markdown content with optional token-by-token streaming progression.
 */
@Composable
fun StreamingMarkdownBlock(
    markdown: String,
    animateStreaming: Boolean,
    textColor: Color,
    modifier: Modifier = Modifier,
) {
    val colors = MixuTheme.colors
    val eInkPolicy = MixuTheme.einkPolicy
    val shouldStream = animateStreaming && !eInkPolicy.isActive

    var visibleChars by remember(markdown, shouldStream) {
        mutableIntStateOf(if (shouldStream) 18.coerceAtMost(markdown.length) else markdown.length)
    }

    LaunchedEffect(markdown, shouldStream) {
        if (!shouldStream) {
            visibleChars = markdown.length
        } else {
            while (visibleChars < markdown.length) {
                delay(16L)
                visibleChars = (visibleChars + 14).coerceAtMost(markdown.length)
            }
        }
    }

    val isStreamingActive = visibleChars < markdown.length
    val slice =
        remember(markdown, visibleChars, isStreamingActive) {
            val sub = markdown.substring(0, visibleChars.coerceIn(0, markdown.length))
            if (isStreamingActive) "$sub ▍" else sub
        }

    val blocks =
        remember(slice) {
            StreamingMarkdownParser.parseBlocks(slice)
        }

    val codeBg = colors.surfaceVariant.copy(alpha = 0.7f)
    val codeFg = colors.primary

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (block in blocks) {
            when (block) {
                is MarkdownBlockNode.Heading -> {
                    val annotated =
                        remember(block.text, codeBg, codeFg, colors.primary) {
                            StreamingMarkdownParser.parseInline(
                                text = block.text,
                                codeBackground = codeBg,
                                codeTextColor = codeFg,
                                primaryColor = colors.primary,
                            )
                        }
                    Text(
                        text = annotated,
                        style =
                            MixuTheme.typography.bodyMedium.copy(
                                fontSize = if (block.level == 1) 15.sp else 14.sp,
                                fontWeight = FontWeight.Bold,
                            ),
                        color = textColor,
                    )
                }

                is MarkdownBlockNode.Paragraph -> {
                    val annotated =
                        remember(block.text, codeBg, codeFg, colors.primary) {
                            StreamingMarkdownParser.parseInline(
                                text = block.text,
                                codeBackground = codeBg,
                                codeTextColor = codeFg,
                                primaryColor = colors.primary,
                            )
                        }
                    Text(
                        text = annotated,
                        style =
                            MixuTheme.typography.caption.copy(
                                fontSize = 13.sp,
                                lineHeight = 19.sp,
                            ),
                        color = textColor,
                    )
                }

                is MarkdownBlockNode.BulletList -> {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (item in block.items) {
                            val annotated =
                                remember(item, codeBg, codeFg, colors.primary) {
                                    StreamingMarkdownParser.parseInline(
                                        text = item,
                                        codeBackground = codeBg,
                                        codeTextColor = codeFg,
                                        primaryColor = colors.primary,
                                    )
                                }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.Top,
                            ) {
                                Text(
                                    text = "•",
                                    style =
                                        MixuTheme.typography.caption.copy(
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                        ),
                                    color = colors.primary,
                                )
                                Text(
                                    text = annotated,
                                    style =
                                        MixuTheme.typography.caption.copy(
                                            fontSize = 13.sp,
                                            lineHeight = 18.sp,
                                        ),
                                    color = textColor,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }

                is MarkdownBlockNode.QuoteBlock -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(
                            modifier =
                                Modifier
                                    .width(3.dp)
                                    .height(22.dp)
                                    .background(colors.primary, RoundedCornerShape(2.dp)),
                        )
                        val annotated =
                            remember(block.text, codeBg, codeFg, colors.primary) {
                                StreamingMarkdownParser.parseInline(
                                    text = block.text,
                                    codeBackground = codeBg,
                                    codeTextColor = codeFg,
                                    primaryColor = colors.primary,
                                )
                            }
                        Text(
                            text = annotated,
                            style =
                                MixuTheme.typography.caption.copy(
                                    fontSize = 12.sp,
                                    fontStyle = FontStyle.Italic,
                                    lineHeight = 18.sp,
                                ),
                            color = colors.onSurfaceVariant,
                        )
                    }
                }

                is MarkdownBlockNode.CodeBlock -> {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(6.dp),
                        color = colors.surfaceVariant.copy(alpha = 0.65f),
                        border = BorderStroke(0.8.dp, colors.outlineVariant),
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            if (block.language.isNotBlank()) {
                                Text(
                                    text = block.language,
                                    style =
                                        MixuTheme.typography.caption.copy(
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                        ),
                                    color = colors.primary,
                                )
                            }
                            Text(
                                text = block.code,
                                style =
                                    MixuTheme.typography.caption.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.5.sp,
                                        lineHeight = 16.sp,
                                    ),
                                color = colors.onSurface,
                            )
                        }
                    }
                }

                is MarkdownBlockNode.TableBlock -> {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(6.dp),
                        color = colors.surfaceVariant.copy(alpha = 0.35f),
                        border = BorderStroke(0.8.dp, colors.outlineVariant),
                    ) {
                        Column(
                            modifier = Modifier.padding(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Row(modifier = Modifier.fillMaxWidth()) {
                                for (header in block.headers) {
                                    Text(
                                        text = header,
                                        style =
                                            MixuTheme.typography.caption.copy(
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                            ),
                                        color = colors.primary,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                            for (row in block.rows) {
                                Row(modifier = Modifier.fillMaxWidth()) {
                                    for (cell in row) {
                                        Text(
                                            text = cell,
                                            style =
                                                MixuTheme.typography.caption.copy(
                                                    fontSize = 11.sp,
                                                ),
                                            color = textColor,
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
