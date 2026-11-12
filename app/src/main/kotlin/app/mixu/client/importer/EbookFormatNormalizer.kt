// SPDX-License-Identifier: MPL-2.0
package app.mixu.client.importer

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Result of normalizing a non-EPUB ebook file (`.txt`, `.mobi`, `.azw3`) into a local
 * standards-compliant `.epub` archive.
 */
sealed interface EbookNormalizeOutcome {
    data class Normalized(
        val epubFile: File,
        val originalFormat: String,
    ) : EbookNormalizeOutcome

    data class Failed(
        val error: BookImportError,
    ) : EbookNormalizeOutcome
}

/**
 * Pure-Kotlin local normalizer that converts `.txt`, unencrypted `.mobi`, and unencrypted `.azw3`
 * (PalmDB `BOOKMOBI` / PalmDOC LZ77 / KF8 HTML) publications into standards-compliant `.epub`
 * archives on import so the downstream EPUB reader, Knowledge Horizon firewall, and KG extraction
 * pipeline operate on a unified spine model with zero third-party native dependencies.
 *
 * Time complexity: O(N) where N is the input file byte length.
 * Space complexity: O(N) for the decoded chapter buffer.
 */
object EbookFormatNormalizer {
    private val CHAPTER_HEADING_REGEX =
        Regex(
            "^\\s*(第[零一二三四五六七八九十百千万0-9]+[章节回卷幕篇集][^\\n]{0,40}|" +
                "Chapter\\s+[0-9IVXLC]+[^\\n]{0,40}|" +
                "CHAPTER\\s+[0-9IVXLC]+[^\\n]{0,40}|" +
                "序[章言幕]?\\s*[^\\n]{0,30}|" +
                "楔子\\s*[^\\n]{0,30}|" +
                "尾声\\s*[^\\n]{0,30}|" +
                "终章\\s*[^\\n]{0,30})\\s*$",
        )

    private val HTML_PAGEBREAK_REGEX =
        Regex(
            "<\\s*mbp:pagebreak\\s*/?\\s*>|<\\s*hr[^>]*class=[\"']pagebreak[\"'][^>]*>",
            RegexOption.IGNORE_CASE,
        )
    private val HTML_HEADING_TAG_REGEX =
        Regex(
            "<\\s*h[1-3][^>]*>(.*?)</\\s*h[1-3]\\s*>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
    private val HTML_BLOCK_BREAK_REGEX =
        Regex(
            "</\\s*(p|div|li|blockquote|tr|h[1-6])\\s*>|<\\s*br\\s*/?\\s*>",
            RegexOption.IGNORE_CASE,
        )
    private val HTML_STYLE_OR_SCRIPT_REGEX =
        Regex(
            "<\\s*(style|script|head)[^>]*>.*?</\\s*\\1\\s*>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
    private val HTML_ANY_TAG_REGEX = Regex("<[^>]+>")

    /**
     * Detects whether the staged file is a supported non-EPUB format (`TXT`, `MOBI`, or `AZW3`).
     * Returns `null` when the file should be treated as a native `EPUB`.
     */
    fun detectNonEpubFormat(
        headerBytes: ByteArray,
        fileName: String?,
        declaredMimeType: String?,
    ): String? {
        val lowerName = fileName?.trim()?.lowercase().orEmpty()
        val lowerMime = declaredMimeType?.trim()?.lowercase().orEmpty()

        if (hasBookMobiMagic(headerBytes)) {
            return if (lowerName.endsWith(".azw3") ||
                lowerMime == PublicationFormatValidator.MIME_AZW3
            ) {
                PublicationFormatValidator.FORMAT_AZW3
            } else {
                PublicationFormatValidator.FORMAT_MOBI
            }
        }
        return when {
            lowerName.endsWith(".azw3") || lowerMime == PublicationFormatValidator.MIME_AZW3 ->
                PublicationFormatValidator.FORMAT_AZW3
            lowerName.endsWith(".mobi") || lowerMime == PublicationFormatValidator.MIME_MOBI ->
                PublicationFormatValidator.FORMAT_MOBI
            lowerName.endsWith(".txt") || lowerMime == PublicationFormatValidator.MIME_PLAIN_TEXT ->
                PublicationFormatValidator.FORMAT_TXT
            else -> null
        }
    }

    fun hasBookMobiMagic(headerBytes: ByteArray): Boolean {
        if (headerBytes.size < 68) return false
        val magic = String(headerBytes, 60, 8, Charsets.US_ASCII)
        return magic == "BOOKMOBI"
    }

    /**
     * Normalizes [stagingFile] in-place into a valid `.epub` archive if [formatCode] is
     * `TXT`, `MOBI`, or `AZW3`.
     */
    fun normalizeInPlace(
        stagingFile: File,
        formatCode: String,
        fileName: String?,
    ): EbookNormalizeOutcome =
        when (formatCode) {
            PublicationFormatValidator.FORMAT_TXT -> normalizeTxtFile(stagingFile, fileName)
            PublicationFormatValidator.FORMAT_MOBI,
            PublicationFormatValidator.FORMAT_AZW3,
            -> normalizeMobiOrAzw3File(stagingFile, formatCode, fileName)
            else -> EbookNormalizeOutcome.Normalized(stagingFile, formatCode)
        }

    private fun normalizeTxtFile(
        stagingFile: File,
        fileName: String?,
    ): EbookNormalizeOutcome {
        val rawBytes =
            runCatching { stagingFile.readBytes() }.getOrElse {
                return EbookNormalizeOutcome.Failed(
                    BookImportError.EmptyOrUnreadableFile("Failed to read TXT file bytes"),
                )
            }
        if (rawBytes.isEmpty()) {
            return EbookNormalizeOutcome.Failed(
                BookImportError.EmptyOrUnreadableFile("TXT file is empty"),
            )
        }
        val decodedText = decodePlainTextBytes(rawBytes).trim()
        if (decodedText.isEmpty()) {
            return EbookNormalizeOutcome.Failed(
                BookImportError.EmptyOrUnreadableFile("TXT file contains no readable text"),
            )
        }

        val (inferredTitle, inferredAuthor) = inferTitleAndAuthorFromTxt(fileName, decodedText)
        val chapters = splitPlainTextIntoChapters(decodedText, inferredTitle)
        val language = inferLanguageCode(decodedText)
        val normalizedTemp = File(stagingFile.parentFile, "${stagingFile.name}.normalized.epub")

        return try {
            writeStandardEpubArchive(
                targetFile = normalizedTemp,
                title = inferredTitle,
                author = inferredAuthor,
                language = language,
                identifier = "urn:mixu:txt:${inferredTitle.hashCode().toUInt().toString(16)}",
                chapters = chapters,
            )
            Files.move(
                normalizedTemp.toPath(),
                stagingFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
            EbookNormalizeOutcome.Normalized(stagingFile, PublicationFormatValidator.FORMAT_TXT)
        } catch (e: Exception) {
            normalizedTemp.delete()
            EbookNormalizeOutcome.Failed(
                BookImportError.CorruptArchive("Failed to package TXT into EPUB: ${e.message}"),
            )
        }
    }

    private fun normalizeMobiOrAzw3File(
        stagingFile: File,
        formatCode: String,
        fileName: String?,
    ): EbookNormalizeOutcome {
        val bytes =
            runCatching { stagingFile.readBytes() }.getOrElse {
                return EbookNormalizeOutcome.Failed(
                    BookImportError.EmptyOrUnreadableFile("Failed to read $formatCode file"),
                )
            }
        if (!hasBookMobiMagic(bytes) || bytes.size < 78) {
            return EbookNormalizeOutcome.Failed(
                BookImportError.CorruptArchive(
                    "Invalid $formatCode container: missing PalmDB BOOKMOBI signature",
                ),
            )
        }

        val numRecords = readUInt16BE(bytes, 76)
        val recordTableEnd = 78 + numRecords * 8
        if (numRecords < 2 || bytes.size < recordTableEnd) {
            return EbookNormalizeOutcome.Failed(
                BookImportError.CorruptArchive(
                    "Corrupt $formatCode record offset table (numRecords=$numRecords)",
                ),
            )
        }

        val offsets = IntArray(numRecords)
        for (i in 0 until numRecords) {
            offsets[i] = readUInt32BE(bytes, 78 + i * 8)
            if (offsets[i] < 0 || offsets[i] > bytes.size) {
                return EbookNormalizeOutcome.Failed(
                    BookImportError.CorruptArchive("Out-of-bounds record offset in $formatCode"),
                )
            }
        }

        fun recordSlice(index: Int): ByteArray {
            val start = offsets[index]
            val end =
                if (index + 1 < numRecords) {
                    offsets[index + 1].coerceIn(start, bytes.size)
                } else {
                    bytes.size
                }
            return bytes.copyOfRange(start, end)
        }

        val rec0 = recordSlice(0)
        if (rec0.size < 16) {
            return EbookNormalizeOutcome.Failed(
                BookImportError.CorruptArchive("Truncated PalmDOC header in $formatCode"),
            )
        }

        val compression = readUInt16BE(rec0, 0)
        val textRecordCount = readUInt16BE(rec0, 8).coerceIn(1, numRecords - 1)
        val encryptionType = readUInt16BE(rec0, 12)
        if (encryptionType != 0) {
            return EbookNormalizeOutcome.Failed(
                BookImportError.CorruptArchive(
                    "DRM-encrypted $formatCode files are not supported (encryptionType=$encryptionType)",
                ),
            )
        }
        if (compression != 1 && compression != 2) {
            return EbookNormalizeOutcome.Failed(
                BookImportError.CorruptArchive(
                    "Unsupported $formatCode compression ($compression); only 1 and 2 supported",
                ),
            )
        }

        var charset: Charset = Charsets.UTF_8
        val fallbackNameTitle =
            fileName
                ?.substringAfterLast('/')
                ?.substringBeforeLast('.')
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: "Imported $formatCode Novel"
        var bookTitle = fallbackNameTitle
        var bookAuthor = "Unknown Author"

        if (rec0.size >= 24 && String(rec0, 16, 4, Charsets.US_ASCII) == "MOBI") {
            val mobiHeaderLen = readUInt32BE(rec0, 20)
            val textEncoding = if (rec0.size >= 32) readUInt32BE(rec0, 28) else 65001
            charset =
                when (textEncoding) {
                    1252 -> Charsets.ISO_8859_1
                    else -> Charsets.UTF_8
                }
            if (rec0.size >= 92) {
                val fullNameOffset = readUInt32BE(rec0, 84)
                val fullNameLength = readUInt32BE(rec0, 88)
                if (fullNameOffset in 0 until rec0.size &&
                    fullNameLength > 0 &&
                    fullNameOffset + fullNameLength <= rec0.size
                ) {
                    val extractedTitle =
                        String(rec0, fullNameOffset, fullNameLength, charset)
                            .trim { it <= ' ' || it == '\u0000' }
                    if (extractedTitle.isNotEmpty()) {
                        bookTitle = extractedTitle
                    }
                }
            }
            val exthFlags = if (rec0.size >= 132) readUInt32BE(rec0, 128) else 0
            val exthOffset = 16 + mobiHeaderLen
            if ((exthFlags and 0x40) != 0 &&
                exthOffset >= 16 &&
                exthOffset + 12 <= rec0.size &&
                String(rec0, exthOffset, 4, Charsets.US_ASCII) == "EXTH"
            ) {
                val exthRecordCount = readUInt32BE(rec0, exthOffset + 8)
                var cursor = exthOffset + 12
                for (r in 0 until exthRecordCount) {
                    if (cursor + 8 > rec0.size) break
                    val recType = readUInt32BE(rec0, cursor)
                    val recLen = readUInt32BE(rec0, cursor + 4)
                    if (recLen < 8 || cursor + recLen > rec0.size) break
                    val payloadLen = recLen - 8
                    val valueStr =
                        String(rec0, cursor + 8, payloadLen, charset)
                            .trim { it <= ' ' || it == '\u0000' }
                    if (valueStr.isNotEmpty()) {
                        when (recType) {
                            100 -> bookAuthor = valueStr
                            503 -> bookTitle = valueStr
                        }
                    }
                    cursor += recLen
                }
            }
        } else {
            val pdbTitle =
                String(bytes, 0, 32, Charsets.UTF_8)
                    .substringBefore('\u0000')
                    .trim()
            if (pdbTitle.isNotEmpty()) {
                bookTitle = pdbTitle
            }
        }

        val combinedDecompressed = ByteArrayOutputStream()
        for (recIdx in 1..textRecordCount) {
            val rawRecord = recordSlice(recIdx)
            val decompressed =
                if (compression == 2) {
                    decompressPalmDocLz77(rawRecord)
                } else {
                    rawRecord
                }
            combinedDecompressed.write(decompressed)
        }

        val rawHtmlOrText = String(combinedDecompressed.toByteArray(), charset)
        val plainWithMarkers = convertMobiHtmlToPlainText(rawHtmlOrText)
        if (plainWithMarkers.isBlank()) {
            return EbookNormalizeOutcome.Failed(
                BookImportError.EmptyOrUnreadableFile(
                    "No readable text records found in $formatCode",
                ),
            )
        }

        val chapters = splitPlainTextIntoChapters(plainWithMarkers, bookTitle)
        val language = inferLanguageCode(plainWithMarkers)
        val normalizedTemp = File(stagingFile.parentFile, "${stagingFile.name}.normalized.epub")
        val idHash = bookTitle.hashCode().toUInt().toString(16)
        val bookIdentifier = "urn:mixu:${formatCode.lowercase()}:$idHash"

        return try {
            writeStandardEpubArchive(
                targetFile = normalizedTemp,
                title = bookTitle,
                author = bookAuthor,
                language = language,
                identifier = bookIdentifier,
                chapters = chapters,
            )
            Files.move(
                normalizedTemp.toPath(),
                stagingFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
            EbookNormalizeOutcome.Normalized(stagingFile, formatCode)
        } catch (e: Exception) {
            normalizedTemp.delete()
            EbookNormalizeOutcome.Failed(
                BookImportError.CorruptArchive(
                    "Failed to normalize $formatCode to EPUB: ${e.message}",
                ),
            )
        }
    }

    /**
     * Decompresses a single PalmDOC LZ77-compressed text record (`compression == 2`).
     *
     * Time complexity: O(R) where R is the uncompressed record size (typically <= 4096 bytes).
     * Space complexity: O(R).
     */
    fun decompressPalmDocLz77(input: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(input.size * 2)
        var i = 0
        while (i < input.size) {
            val b = input[i].toInt() and 0xFF
            i++
            when {
                b == 0x00 -> out.write(0x00)
                b in 0x01..0x08 -> {
                    val count = minOf(b, input.size - i)
                    out.write(input, i, count)
                    i += count
                }
                b in 0x09..0x7F -> out.write(b)
                b in 0x80..0xBF -> {
                    if (i >= input.size) break
                    val next = input[i].toInt() and 0xFF
                    i++
                    val pair = ((b and 0x3F) shl 8) or next
                    val distance = (pair ushr 3) and 0x07FF
                    val length = (pair and 0x07) + 3
                    val currentBytes = out.toByteArray()
                    if (distance in 1..currentBytes.size) {
                        var copyPos = currentBytes.size - distance
                        val temp = ByteArray(currentBytes.size + length)
                        System.arraycopy(currentBytes, 0, temp, 0, currentBytes.size)
                        var writePos = currentBytes.size
                        repeat(length) {
                            val copiedByte = temp[copyPos]
                            temp[writePos] = copiedByte
                            out.write(copiedByte.toInt() and 0xFF)
                            copyPos++
                            writePos++
                        }
                    }
                }
                else -> {
                    // 0xC0..0xFF: space (0x20) followed by (b xor 0x80)
                    out.write(0x20)
                    out.write(b xor 0x80)
                }
            }
        }
        return out.toByteArray()
    }

    /**
     * Compresses a plain/HTML byte slice using PalmDOC LZ77 (`0xC0..0xFF` space+ASCII pair
     * encoding and literal runs) so unit tests can generate authentic compressed `.mobi`/`.azw3`
     * records.
     */
    fun compressPalmDocLz77(input: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(input.size)
        var i = 0
        while (i < input.size) {
            val b = input[i].toInt() and 0xFF
            if (b == 0x20 && i + 1 < input.size) {
                val next = input[i + 1].toInt() and 0xFF
                if (next in 0x40..0x7F) {
                    out.write(next or 0x80)
                    i += 2
                    continue
                }
            }
            if (b == 0x00 || b in 0x09..0x7F) {
                out.write(b)
                i++
            } else {
                // High byte (UTF-8 multibyte) or 0x01..0x08: emit literal run 0x01..0x08
                val runStart = i
                var runLen = 0
                while (i < input.size && runLen < 8) {
                    val cur = input[i].toInt() and 0xFF
                    if (cur in 0x09..0x7F) break
                    i++
                    runLen++
                }
                out.write(runLen)
                out.write(input, runStart, runLen)
            }
        }
        return out.toByteArray()
    }

    /**
     * Builds a valid unencrypted PalmDB `BOOKMOBI` binary image with MOBI + EXTH headers and
     * PalmDOC text records (either LZ77-compressed or uncompressed) for testing `.mobi` and `.azw3`
     * import.
     */
    fun createSyntheticMobiBytes(
        title: String,
        author: String,
        chapters: List<Pair<String, String>>,
        compressLz77: Boolean = true,
    ): ByteArray {
        val htmlText =
            chapters.joinToString("<mbp:pagebreak/>") { (heading, body) ->
                "<html><body><h1>$heading</h1><p>$body</p></body></html>"
            }
        val rawTextBytes = htmlText.toByteArray(Charsets.UTF_8)
        val textRecordPayload =
            if (compressLz77) {
                compressPalmDocLz77(rawTextBytes)
            } else {
                rawTextBytes
            }

        // Build Record 0: 16-byte PalmDOC header + 232-byte MOBI header + EXTH header + Full Name
        val titleBytes = title.toByteArray(Charsets.UTF_8)
        val authorBytes = author.toByteArray(Charsets.UTF_8)
        val mobiHeaderLen = 232
        val exthAuthorRecLen = 8 + authorBytes.size
        val exthTitleRecLen = 8 + titleBytes.size
        val exthRawLen = 12 + exthAuthorRecLen + exthTitleRecLen
        val exthPaddedLen = (exthRawLen + 3) and -4
        val fullNameOffset = 16 + mobiHeaderLen + exthPaddedLen
        val rec0Size = fullNameOffset + titleBytes.size + 4

        val rec0 = ByteBuffer.allocate(rec0Size)
        // PalmDOC 16-byte header
        rec0.putShort(0, (if (compressLz77) 2 else 1).toShort()) // compression
        rec0.putShort(2, 0)
        rec0.putInt(4, rawTextBytes.size) // uncompressed text length
        rec0.putShort(8, 1) // 1 text record
        rec0.putShort(10, 4096) // record size
        rec0.putShort(12, 0) // encryptionType = 0 (unencrypted)
        rec0.putShort(14, 0)

        // MOBI header at offset 16
        rec0.position(16)
        rec0.put("MOBI".toByteArray(Charsets.US_ASCII))
        rec0.putInt(20, mobiHeaderLen)
        rec0.putInt(24, 2) // MOBI type = book
        rec0.putInt(28, 65001) // UTF-8
        rec0.putInt(84, fullNameOffset)
        rec0.putInt(88, titleBytes.size)
        rec0.putInt(128, 0x40) // EXTH flag bit 6 set

        // EXTH header at offset 16 + mobiHeaderLen
        val exthStart = 16 + mobiHeaderLen
        rec0.position(exthStart)
        rec0.put("EXTH".toByteArray(Charsets.US_ASCII))
        rec0.putInt(exthStart + 4, exthRawLen)
        rec0.putInt(exthStart + 8, 2) // 2 EXTH records (100=author, 503=title)
        var cursor = exthStart + 12
        rec0.putInt(cursor, 100)
        rec0.putInt(cursor + 4, exthAuthorRecLen)
        rec0.position(cursor + 8)
        rec0.put(authorBytes)
        cursor += exthAuthorRecLen
        rec0.putInt(cursor, 503)
        rec0.putInt(cursor + 4, exthTitleRecLen)
        rec0.position(cursor + 8)
        rec0.put(titleBytes)

        // Full Name at fullNameOffset
        rec0.position(fullNameOffset)
        rec0.put(titleBytes)

        val rec0Bytes = rec0.array()
        val numRecords = 2
        val pdbHeaderSize = 78 + numRecords * 8 + 2
        val rec0Offset = pdbHeaderSize
        val rec1Offset = rec0Offset + rec0Bytes.size
        val totalSize = rec1Offset + textRecordPayload.size

        val pdb = ByteBuffer.allocate(totalSize)
        val asciiName = title.take(20).toByteArray(Charsets.UTF_8)
        pdb.position(0)
        pdb.put(asciiName.copyOf(minOf(asciiName.size, 31)))
        pdb.position(60)
        pdb.put("BOOKMOBI".toByteArray(Charsets.US_ASCII))
        pdb.putShort(76, numRecords.toShort())
        // Record 0 offset entry
        pdb.putInt(78, rec0Offset)
        pdb.putInt(82, 0)
        // Record 1 offset entry
        pdb.putInt(86, rec1Offset)
        pdb.putInt(90, 1)

        pdb.position(rec0Offset)
        pdb.put(rec0Bytes)
        pdb.position(rec1Offset)
        pdb.put(textRecordPayload)
        return pdb.array()
    }

    private fun decodePlainTextBytes(bytes: ByteArray): String {
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()
        ) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 &&
            bytes[0] == 0xFF.toByte() &&
            bytes[1] == 0xFE.toByte()
        ) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        if (bytes.size >= 2 &&
            bytes[0] == 0xFE.toByte() &&
            bytes[1] == 0xFF.toByte()
        ) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        val utf8Decoder =
            Charsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
        val strictUtf8 =
            runCatching {
                val charBuf: CharBuffer = utf8Decoder.decode(ByteBuffer.wrap(bytes))
                charBuf.toString()
            }.getOrNull()
        if (strictUtf8 != null) {
            return strictUtf8
        }
        val gbCharset =
            runCatching { Charset.forName("GB18030") }.getOrDefault(Charsets.UTF_8)
        return String(bytes, gbCharset)
    }

    private fun inferTitleAndAuthorFromTxt(
        fileName: String?,
        text: String,
    ): Pair<String, String> {
        val baseName =
            fileName
                ?.substringAfterLast('/')
                ?.substringBeforeLast('.')
                ?.trim()
                .orEmpty()
        var title = baseName.ifEmpty { "Imported TXT Novel" }
        var author = "Unknown Author"

        // Check if filename matches 《Title》-Author or Title - Author
        val bracketMatch = Regex("《([^》]+)》\\s*[-_—·]?\\s*(.*)").find(baseName)
        if (bracketMatch != null) {
            title = bracketMatch.groupValues[1].trim().ifEmpty { title }
            val tailAuthor =
                bracketMatch.groupValues[2]
                    .replace(Regex("^(作者[:：]?|著)"), "")
                    .trim()
            if (tailAuthor.isNotEmpty()) {
                author = tailAuthor
            }
        }

        // Check first 15 lines for explicit 书名/Title and 作者/Author metadata
        val headLines =
            text
                .lineSequence()
                .take(15)
                .map { it.trim() }
                .toList()
        for (line in headLines) {
            when {
                line.startsWith("书名：") || line.startsWith("书名:") -> {
                    val v = line.substringAfter('：', line.substringAfter(':')).trim().trim('《', '》')
                    if (v.isNotEmpty()) title = v
                }
                line.startsWith("作者：") || line.startsWith("作者:") -> {
                    val v = line.substringAfter('：', line.substringAfter(':')).trim()
                    if (v.isNotEmpty()) author = v
                }
                line.startsWith("Title:", ignoreCase = true) -> {
                    val v = line.substringAfter(':').trim()
                    if (v.isNotEmpty()) title = v
                }
                line.startsWith("Author:", ignoreCase = true) -> {
                    val v = line.substringAfter(':').trim()
                    if (v.isNotEmpty()) author = v
                }
            }
        }
        return title to author
    }

    private fun convertMobiHtmlToPlainText(rawHtml: String): String {
        val withoutStyles = HTML_STYLE_OR_SCRIPT_REGEX.replace(rawHtml, "")
        val withPageBreaks = HTML_PAGEBREAK_REGEX.replace(withoutStyles, "\n\n")
        val withHeadings =
            HTML_HEADING_TAG_REGEX.replace(withPageBreaks) { match ->
                val inner = HTML_ANY_TAG_REGEX.replace(match.groupValues[1], "").trim()
                "\n$inner\n"
            }
        val withLineBreaks = HTML_BLOCK_BREAK_REGEX.replace(withHeadings, "\n")
        val stripped = HTML_ANY_TAG_REGEX.replace(withLineBreaks, "")
        return stripped
            .replace("&nbsp;", " ")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
            .trim()
    }

    private fun splitPlainTextIntoChapters(
        fullText: String,
        fallbackBookTitle: String,
    ): List<Pair<String, List<String>>> {
        val lines = fullText.lines()
        val chapters = mutableListOf<Pair<String, MutableList<String>>>()
        var currentTitle: String? = null
        var currentParagraphs = mutableListOf<String>()

        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            if (chapters.isEmpty() && currentTitle == null && isMetadataHeaderLine(line)) {
                continue
            }
            if (CHAPTER_HEADING_REGEX.matches(line)) {
                if (currentParagraphs.isNotEmpty()) {
                    val heading = currentTitle ?: "第 ${chapters.size + 1} 节"
                    chapters.add(heading to currentParagraphs)
                    currentParagraphs = mutableListOf()
                }
                currentTitle = line
            } else {
                currentParagraphs.add(line)
            }
        }
        if (currentParagraphs.isNotEmpty()) {
            val heading =
                currentTitle
                    ?: if (chapters.isEmpty()) fallbackBookTitle else "第 ${chapters.size + 1} 节"
            chapters.add(heading to currentParagraphs)
        }

        if (chapters.size == 1 && chapters.first().second.sumOf { it.length } > 8_000) {
            // Chunk long unchaptered text into ~5,000-char readable spine chapters
            val allParas = chapters.first().second
            val chunked = mutableListOf<Pair<String, List<String>>>()
            var bucket = mutableListOf<String>()
            var bucketChars = 0
            for (para in allParas) {
                bucket.add(para)
                bucketChars += para.length
                if (bucketChars >= 5_000) {
                    chunked.add("第 ${chunked.size + 1} 节" to bucket)
                    bucket = mutableListOf()
                    bucketChars = 0
                }
            }
            if (bucket.isNotEmpty()) {
                chunked.add("第 ${chunked.size + 1} 节" to bucket)
            }
            return chunked
        }

        return chapters.map { it.first to it.second.toList() }
    }

    private fun isMetadataHeaderLine(line: String): Boolean =
        line.startsWith("书名：") ||
            line.startsWith("书名:") ||
            line.startsWith("作者：") ||
            line.startsWith("作者:") ||
            line.startsWith("Title:", ignoreCase = true) ||
            line.startsWith("Author:", ignoreCase = true)

    private fun inferLanguageCode(text: String): String {
        val sample = text.take(1_500)
        val hasKana = sample.any { it in '\u3040'..'\u30FF' }
        if (hasKana) return "ja"
        val hasCjk = sample.any { it in '\u4E00'..'\u9FFF' }
        return if (hasCjk) "zh-CN" else "en"
    }

    private fun writeStandardEpubArchive(
        targetFile: File,
        title: String,
        author: String,
        language: String,
        identifier: String,
        chapters: List<Pair<String, List<String>>>,
    ) {
        val safeChapters =
            chapters.ifEmpty {
                listOf(title to listOf(title))
            }
        ZipOutputStream(targetFile.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("mimetype"))
            zos.write(PublicationFormatValidator.MIME_EPUB.toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("META-INF/container.xml"))
            val containerXml =
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                    "<container version=\"1.0\" " +
                    "xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\">\n" +
                    "  <rootfiles>\n" +
                    "    <rootfile full-path=\"OEBPS/content.opf\" " +
                    "media-type=\"application/oebps-package+xml\"/>\n" +
                    "  </rootfiles>\n" +
                    "</container>"
            zos.write(containerXml.toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            val manifestItems =
                safeChapters.indices.joinToString("\n    ") { idx ->
                    "<item id=\"ch_${idx + 1}\" href=\"text/ch_${idx + 1}.xhtml\" " +
                        "media-type=\"application/xhtml+xml\"/>"
                }
            val spineRefs =
                safeChapters.indices.joinToString("\n    ") { idx ->
                    "<itemref idref=\"ch_${idx + 1}\"/>"
                }

            zos.putNextEntry(ZipEntry("OEBPS/content.opf"))
            val pkgAttrs =
                "xmlns=\"http://www.idpf.org/2007/opf\" " +
                    "version=\"3.0\" unique-identifier=\"bookid\""
            val opfXml =
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <package $pkgAttrs>
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:identifier id="bookid">${escapeXml(identifier)}</dc:identifier>
                    <dc:title>${escapeXml(title)}</dc:title>
                    <dc:creator>${escapeXml(author)}</dc:creator>
                    <dc:language>${escapeXml(language)}</dc:language>
                  </metadata>
                  <manifest>
                    $manifestItems
                  </manifest>
                  <spine>
                    $spineRefs
                  </spine>
                </package>
                """.trimIndent()
            zos.write(opfXml.toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            safeChapters.forEachIndexed { idx, (chapterHeading, paragraphs) ->
                zos.putNextEntry(ZipEntry("OEBPS/text/ch_${idx + 1}.xhtml"))
                val paragraphsXml =
                    paragraphs.joinToString("\n    ") { p ->
                        "<p>${escapeXml(p)}</p>"
                    }
                val xhtml =
                    """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <html xmlns="http://www.w3.org/1999/xhtml">
                      <head><title>${escapeXml(chapterHeading)}</title></head>
                      <body>
                        <h1>${escapeXml(chapterHeading)}</h1>
                        $paragraphsXml
                      </body>
                    </html>
                    """.trimIndent()
                zos.write(xhtml.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
        }
    }

    private fun escapeXml(input: String): String =
        buildString(input.length + 16) {
            for (ch in input) {
                when (ch) {
                    '&' -> append("&amp;")
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '"' -> append("&quot;")
                    '\'' -> append("&apos;")
                    else ->
                        if (ch == '\t' || ch == '\n' || ch == '\r' || ch.code >= 0x20) {
                            append(ch)
                        }
                }
            }
        }

    private fun readUInt16BE(
        bytes: ByteArray,
        offset: Int,
    ): Int = ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)

    private fun readUInt32BE(
        bytes: ByteArray,
        offset: Int,
    ): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
}
