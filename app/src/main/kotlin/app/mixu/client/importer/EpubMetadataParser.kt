// SPDX-License-Identifier: MPL-2.0
package app.mixu.client.importer

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.File
import java.io.StringReader
import java.util.zip.ZipException
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Typed error surface for publication import and metadata extraction failures.
 */
sealed interface BookImportError {
    val userReadableMessage: String

    /**
     * Format is explicitly excluded from Mixu product scope (e.g. PDF / OCR).
     */
    data class ExcludedFormat(
        val formatCode: String,
    ) : BookImportError {
        override val userReadableMessage: String =
            "$formatCode files are excluded from product scope. Please import an EPUB novel."
    }

    /**
     * Format is deferred or not enabled in the active adapter allowlist (e.g. TXT, AZW3, MOBI).
     */
    data class UnsupportedOrDeferredFormat(
        val formatCode: String,
    ) : BookImportError {
        override val userReadableMessage: String =
            "$formatCode format is not enabled in the current publication adapter allowlist."
    }

    /**
     * Input stream or file is empty or unreadable.
     */
    data class EmptyOrUnreadableFile(
        val reason: String,
    ) : BookImportError {
        override val userReadableMessage: String =
            "The selected file is empty or could not be read: $reason"
    }

    /**
     * File does not have a valid ZIP structure or is truncated/corrupted.
     */
    data class CorruptArchive(
        val reason: String,
    ) : BookImportError {
        override val userReadableMessage: String =
            "The EPUB archive is corrupted or truncated: $reason"
    }

    /**
     * Archive is missing `META-INF/container.xml` or a `rootfile` declaration.
     */
    data object MissingContainerXml : BookImportError {
        override val userReadableMessage: String =
            "Invalid EPUB archive: missing META-INF/container.xml."
    }

    /**
     * Archive is missing the OPF package document referenced by `container.xml`.
     */
    data class MissingPackageDocument(
        val opfPath: String,
    ) : BookImportError {
        override val userReadableMessage: String =
            "Invalid EPUB archive: missing package document ($opfPath)."
    }

    /**
     * Container or OPF XML is malformed or contains disallowed DTD/XXE declarations.
     */
    data class MalformedMetadataXml(
        val reason: String,
    ) : BookImportError {
        override val userReadableMessage: String =
            "Invalid EPUB metadata XML: $reason"
    }

    /**
     * OPF package document contains no readable spine items.
     */
    data object EmptySpine : BookImportError {
        override val userReadableMessage: String =
            "Invalid EPUB archive: publication spine contains no readable chapters."
    }

    /**
     * Storage I/O or database persistence failure.
     */
    data class StorageFailure(
        val reason: String,
    ) : BookImportError {
        override val userReadableMessage: String =
            "Failed to store imported book in local library: $reason"
    }
}

/**
 * Single spine reading-order entry extracted from an EPUB OPF package document.
 */
data class EpubSpineItem(
    val spineIndex: Int,
    val idref: String,
    val href: String,
    val resolvedZipPath: String,
    val mediaType: String,
    val title: String,
)

/**
 * Parsed bibliographic and structural metadata extracted from an EPUB archive.
 */
data class ParsedEpubMetadata(
    val title: String,
    val author: String,
    val language: String,
    val identifier: String?,
    val opfPath: String,
    val spineItems: List<EpubSpineItem>,
) {
    val totalChapters: Int
        get() = spineItems.size
}

/**
 * Result of parsing an EPUB archive.
 */
sealed interface EpubParseOutcome {
    data class Success(
        val metadata: ParsedEpubMetadata,
    ) : EpubParseOutcome

    data class Failure(
        val error: BookImportError,
    ) : EpubParseOutcome
}

/**
 * XXE-safe EPUB archive validator and OPF metadata parser.
 */
object EpubMetadataParser {
    private const val CONTAINER_XML_PATH = "META-INF/container.xml"
    private const val DEFAULT_TITLE = "Untitled Mystery"
    private const val DEFAULT_AUTHOR = "Unknown Author"
    private const val DEFAULT_LANGUAGE = "und"

    /**
     * Parses [epubFile] and returns either [EpubParseOutcome.Success] or a typed
     * [EpubParseOutcome.Failure] without throwing unhandled exceptions on corrupt input.
     */
    fun parse(epubFile: File): EpubParseOutcome {
        if (!epubFile.exists() || epubFile.length() == 0L) {
            return EpubParseOutcome.Failure(
                BookImportError.EmptyOrUnreadableFile("File is empty or does not exist"),
            )
        }
        if (!hasZipMagicBytes(epubFile)) {
            return EpubParseOutcome.Failure(
                BookImportError.CorruptArchive("Missing ZIP magic header"),
            )
        }

        return try {
            ZipFile(epubFile).use { zip ->
                val containerEntry =
                    zip.getEntry(CONTAINER_XML_PATH)
                        ?: return EpubParseOutcome.Failure(BookImportError.MissingContainerXml)

                val containerXml =
                    zip.getInputStream(containerEntry).bufferedReader(Charsets.UTF_8).use {
                        it.readText()
                    }
                val containerDoc =
                    parseXmlSafely(containerXml)
                        ?: return EpubParseOutcome.Failure(
                            BookImportError.MalformedMetadataXml(
                                "Malformed META-INF/container.xml",
                            ),
                        )

                val opfPath =
                    extractOpfRootfilePath(containerDoc)
                        ?: return EpubParseOutcome.Failure(BookImportError.MissingContainerXml)

                val normalizedOpfPath = normalizeZipPath(opfPath)
                val opfEntry =
                    zip.getEntry(normalizedOpfPath)
                        ?: return EpubParseOutcome.Failure(
                            BookImportError.MissingPackageDocument(normalizedOpfPath),
                        )

                val opfXml =
                    zip.getInputStream(opfEntry).bufferedReader(Charsets.UTF_8).use {
                        it.readText()
                    }
                val opfDoc =
                    parseXmlSafely(opfXml)
                        ?: return EpubParseOutcome.Failure(
                            BookImportError.MalformedMetadataXml("Malformed OPF package document"),
                        )

                val metadata = extractPackageMetadata(zip, normalizedOpfPath, opfDoc)
                if (metadata.spineItems.isEmpty()) {
                    return EpubParseOutcome.Failure(BookImportError.EmptySpine)
                }
                EpubParseOutcome.Success(metadata)
            }
        } catch (e: ZipException) {
            EpubParseOutcome.Failure(
                BookImportError.CorruptArchive(e.message ?: "Invalid ZIP archive structure"),
            )
        } catch (e: Exception) {
            EpubParseOutcome.Failure(
                BookImportError.CorruptArchive(e.message ?: "Unexpected archive read failure"),
            )
        }
    }

    internal fun hasZipMagicBytes(file: File): Boolean {
        if (file.length() < 4L) return false
        val header = ByteArray(4)
        val read = file.inputStream().use { it.read(header) }
        if (read < 4) return false
        return header[0] == 0x50.toByte() &&
            header[1] == 0x4B.toByte() &&
            header[2] == 0x03.toByte() &&
            header[3] == 0x04.toByte()
    }

    /**
     * Parses XML text using a hardened [DocumentBuilderFactory] with DTDs and external entities
     * disabled to prevent XXE attacks.
     */
    internal fun parseXmlSafely(xmlContent: String): Document? =
        runCatching {
            val normalizedXml = xmlContent.trimStart { it == '\uFEFF' || it.isWhitespace() }
            if (normalizedXml.contains("<!DOCTYPE", ignoreCase = true) ||
                normalizedXml.contains("<!ENTITY", ignoreCase = true)
            ) {
                return null
            }
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = true
            runCatching { factory.isXIncludeAware = false }
            runCatching { factory.isExpandEntityReferences = false }
            runCatching {
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            }
            runCatching {
                factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
            }
            runCatching {
                factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            }
            runCatching {
                factory.setFeature(
                    "http://apache.org/xml/features/nonvalidating/load-external-dtd",
                    false,
                )
            }
            val builder = factory.newDocumentBuilder()
            // Suppress default stderr printing from SAX parser on malformed XML
            builder.setErrorHandler(null)
            builder.setEntityResolver { _, _ -> InputSource(StringReader("")) }
            builder.parse(InputSource(StringReader(normalizedXml)))
        }.getOrNull()

    private fun extractOpfRootfilePath(containerDoc: Document): String? {
        val nodes = containerDoc.getElementsByTagName("*")
        for (i in 0 until nodes.length) {
            val node = nodes.item(i) as? Element ?: continue
            val localName =
                node.localName?.takeIf { it.isNotEmpty() }
                    ?: node.tagName.substringAfterLast(':')
            if (localName.equals("rootfile", ignoreCase = true)) {
                val fullPath = node.getAttribute("full-path").trim()
                if (fullPath.isNotEmpty()) {
                    return fullPath
                }
            }
        }
        return null
    }

    private fun extractPackageMetadata(
        zip: ZipFile,
        opfPath: String,
        opfDoc: Document,
    ): ParsedEpubMetadata {
        val opfDir = opfPath.substringBeforeLast('/', missingDelimiterValue = "")
        var title: String? = null
        var author: String? = null
        var language: String? = null
        var identifier: String? = null

        val manifestById = linkedMapOf<String, Pair<String, String>>()
        val spineIdrefs = mutableListOf<String>()

        val allElements = opfDoc.getElementsByTagName("*")
        for (i in 0 until allElements.length) {
            val el = allElements.item(i) as? Element ?: continue
            val local =
                (
                    el.localName?.takeIf { it.isNotEmpty() }
                        ?: el.tagName.substringAfterLast(':')
                ).lowercase()
            when (local) {
                "title" -> {
                    val text = el.textContent?.trim().orEmpty()
                    if (title.isNullOrEmpty() && text.isNotEmpty()) {
                        title = text
                    }
                }
                "creator" -> {
                    val text = el.textContent?.trim().orEmpty()
                    if (author.isNullOrEmpty() && text.isNotEmpty()) {
                        author = text
                    }
                }
                "language" -> {
                    val text = el.textContent?.trim().orEmpty()
                    if (language.isNullOrEmpty() && text.isNotEmpty()) {
                        language = text
                    }
                }
                "identifier" -> {
                    val text = el.textContent?.trim().orEmpty()
                    if (identifier.isNullOrEmpty() && text.isNotEmpty()) {
                        identifier = text
                    }
                }
                "item" -> {
                    val id = el.getAttribute("id").trim()
                    val href = el.getAttribute("href").trim()
                    val mediaType =
                        el.getAttribute("media-type").trim().ifEmpty {
                            "application/xhtml+xml"
                        }
                    if (id.isNotEmpty() && href.isNotEmpty()) {
                        manifestById[id] = Pair(href, mediaType)
                    }
                }
                "itemref" -> {
                    val linear = el.getAttribute("linear").trim()
                    val idref = el.getAttribute("idref").trim()
                    if (idref.isNotEmpty() && !linear.equals("no", ignoreCase = true)) {
                        spineIdrefs.add(idref)
                    }
                }
            }
        }

        val spineItems = mutableListOf<EpubSpineItem>()
        for (idref in spineIdrefs) {
            val manifestEntry = manifestById[idref] ?: continue
            val rawHref = manifestEntry.first.substringBefore('#')
            val mediaType = manifestEntry.second
            val resolvedPath = resolveRelativeZipPath(opfDir, rawHref)
            if (zip.getEntry(resolvedPath) == null) {
                continue
            }
            val nextIndex = spineItems.size
            val chapterTitle =
                extractChapterHeading(zip, resolvedPath)
                    ?: "Chapter ${nextIndex + 1}"
            spineItems.add(
                EpubSpineItem(
                    spineIndex = nextIndex,
                    idref = idref,
                    href = rawHref,
                    resolvedZipPath = resolvedPath,
                    mediaType = mediaType,
                    title = chapterTitle,
                ),
            )
        }

        return ParsedEpubMetadata(
            title = title ?: DEFAULT_TITLE,
            author = author ?: DEFAULT_AUTHOR,
            language = language ?: DEFAULT_LANGUAGE,
            identifier = identifier,
            opfPath = opfPath,
            spineItems = spineItems,
        )
    }

    internal fun extractChapterHeading(
        zip: ZipFile,
        entryPath: String,
    ): String? {
        val entry = zip.getEntry(entryPath) ?: return null
        val content =
            runCatching {
                zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
            }.getOrNull() ?: return null

        val headingRegex =
            Regex(
                "<(?:h1|h2|h3|title)[^>]*>(.*?)</(?:h1|h2|h3|title)>",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
            )
        for (match in headingRegex.findAll(content)) {
            val cleaned =
                match.groupValues[1]
                    .replace(Regex("<[^>]+>"), " ")
                    .replace(Regex("\\s+"), " ")
                    .trim()
            if (cleaned.isNotEmpty()) {
                return cleaned
            }
        }
        return null
    }

    internal fun normalizeZipPath(path: String): String = path.replace('\\', '/').trimStart('/')

    internal fun resolveRelativeZipPath(
        baseDir: String,
        relativeHref: String,
    ): String {
        val combined =
            if (baseDir.isEmpty()) {
                relativeHref
            } else {
                "$baseDir/$relativeHref"
            }
        val segments = mutableListOf<String>()
        for (part in combined.replace('\\', '/').split('/')) {
            when (part) {
                "", "." -> Unit
                ".." -> if (segments.isNotEmpty()) segments.removeAt(segments.lastIndex)
                else -> segments.add(part)
            }
        }
        return segments.joinToString("/")
    }
}
