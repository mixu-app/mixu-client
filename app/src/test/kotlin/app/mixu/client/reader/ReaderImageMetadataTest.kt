// SPDX-License-Identifier: MPL-2.0
package app.mixu.client.reader

import app.mixu.client.importer.BookImportServiceTest
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ReaderImageMetadataTest {
    @Test
    fun `image labels are sanitized without changing normalized text or offsets`() {
        val directory = Files.createTempDirectory("mixu-image-metadata").toFile()
        try {
            val body =
                "Before.</p>" +
                    """<img src="plate.svg" alt="Publisher supplied geometric plate"/>""" +
                    """<img src="plate.svg"/><p>After."""
            val original =
                BookImportServiceTest.createSyntheticEpub(
                    targetFile = directory.resolve("source.epub"),
                    title = "Synthetic plates",
                    author = "Synthetic author",
                    chapters =
                        listOf(
                            "intro.xhtml" to ("Introduction" to "Original synthetic introduction."),
                            "chapter.xhtml" to (
                                "Plates" to
                                    body
                            ),
                        ),
                )
            val entries =
                ZipFile(original).use { zip ->
                    zip.entries().asSequence().associate {
                        it.name to
                            zip
                                .getInputStream(
                                    it,
                                ).readBytes()
                    }
                }
            val image =
                (
                    """<svg xmlns="http://www.w3.org/2000/svg" width="32" height="32">""" +
                        """<rect width="32" height="32"/><!--""" +
                        "Synthetic geometric metadata. ".repeat(100) + "--></svg>"
                ).toByteArray()
            val publicationFile = directory.resolve("plates.epub")
            ZipOutputStream(publicationFile.outputStream()).use { output ->
                (entries + ("OEBPS/text/plate.svg" to image)).forEach { (path, bytes) ->
                    output.putNextEntry(ZipEntry(path))
                    output.write(bytes)
                    output.closeEntry()
                }
            }
            val publication = EpubBookPublication.open(publicationFile)
            val chapter = publication.chapters[1]
            assertEquals(1, chapter.spineItem.spineIndex)
            val rawImageTokens =
                chapter.paragraphs.filter {
                    it.startsWith(EpubBookPublication.IMAGE_TOKEN_PREFIX)
                }
            val images = rawImageTokens.map { assertNotNull(parseReaderImageTokenOrNull(it)) }
            assertEquals(2, images.size)
            val originalDescriptions =
                listOf(
                    "Publisher supplied geometric plate",
                    "书中插图 / 现场平面图 #2",
                )
            val expectedTokens =
                images.zip(originalDescriptions).map { (imageToken, description) ->
                    "${EpubBookPublication.IMAGE_TOKEN_PREFIX}${imageToken.filePath}|" +
                        "$description${EpubBookPublication.IMAGE_TOKEN_SUFFIX}"
                }
            assertEquals(expectedTokens, rawImageTokens)
            val expectedParagraphs = listOf("Plates", "Before.") + expectedTokens + "After."
            assertEquals(expectedParagraphs, chapter.paragraphs)
            assertEquals(expectedParagraphs.joinToString("\n\n"), chapter.fullText)
            val originalLength = chapter.fullText.length
            assertEquals("Publisher supplied geometric plate", images[0].caption)
            assertEquals("图片", images[1].caption)
            assertEquals(originalLength, chapter.totalChars)
            images.forEach { token -> assertContentEquals(image, File(token.filePath).readBytes()) }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `image marker without metadata uses neutral accessibility description`() {
        val token =
            assertNotNull(
                parseReaderImageTokenOrNull(
                    "${EpubBookPublication.IMAGE_TOKEN_PREFIX}images/plate.svg${EpubBookPublication.IMAGE_TOKEN_SUFFIX}",
                ),
            )
        assertEquals("图片", token.caption)
    }
}
