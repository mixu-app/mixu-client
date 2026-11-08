// SPDX-License-Identifier: MPL-2.0
package app.mixu.client.caseboard

import app.mixu.client.db.LocalLibraryDatabase
import app.mixu.client.importer.BookImportService
import app.mixu.client.reader.EpubBookPublication
import app.mixu.graph.EditionId
import app.mixu.graph.GraphJson
import kotlinx.serialization.encodeToString
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BookGraphPackLoaderTest {
    @Test
    fun `syncImportDirectories imports EPUB and loads companion graph pack`() {
        val tempDir = Files.createTempDirectory("mixu-pack-loader-test").toFile()
        try {
            val dbFile = tempDir.resolve(LocalLibraryDatabase.DATABASE_FILE_NAME)
            val booksDir = tempDir.resolve("books")
            val packsDir = tempDir.resolve("packs")
            val importDir = tempDir.resolve("import").apply { mkdirs() }

            val epubFile = createMinimalEpub(importDir.resolve("test-case.epub"))
            LocalLibraryDatabase.open(dbFile).use { db ->
                val importService = BookImportService(database = db, booksDirectory = booksDir)
                val loader = BookGraphPackLoader(packsDirectory = packsDir)

                // Sync EPUB first to get its asset & editionId
                val bookIds = loader.syncImportDirectories(listOf(importDir), importService)
                assertEquals(1, bookIds.size)
                val bookId = bookIds.first()
                val asset = db.getBookAssetForBook(bookId)!!
                val pub = EpubBookPublication.open(File(asset.localStoragePath))

                // Without a companion .graph.json, loadForBook returns a spine-aligned fallback pack
                val fallbackPack = loader.loadForBook(asset = asset, publication = pub)
                assertEquals(2, fallbackPack.anchors.size)
                assertEquals(
                    asset.editionId,
                    fallbackPack.manifest.editionId.value,
                )

                // Now stage a companion .graph.json for that editionId in importDir
                val basePack = SyntheticMysteryData.createMysteryGraphPack()
                val targetEdition = EditionId(asset.editionId!!)
                val customPack =
                    basePack.copy(
                        manifest = basePack.manifest.copy(editionId = targetEdition),
                        anchors = basePack.anchors.map { it.copy(editionId = targetEdition) },
                    )
                importDir
                    .resolve("test-case.graph.json")
                    .writeText(GraphJson.encodeToString(customPack))

                val secondSyncIds = loader.syncImportDirectories(listOf(importDir), importService)
                assertTrue(secondSyncIds.isEmpty())
                val loadedPack = loader.loadForBook(asset = asset, publication = pub)
                assertEquals(9, loadedPack.entities.size)
                assertEquals(8, loadedPack.assertions.size)
                assertTrue(
                    loadedPack.entities.any {
                        it.entityId == SyntheticMysteryData.ENTITY_ARTHUR
                    },
                )
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private fun createMinimalEpub(targetFile: File): File {
        ZipOutputStream(targetFile.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("mimetype"))
            zos.write("application/epub+zip".toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("META-INF/container.xml"))
            zos.write(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles>
                    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                  </rootfiles>
                </container>
                """.trimIndent().toByteArray(Charsets.UTF_8),
            )
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("OEBPS/content.opf"))
            zos.write(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:identifier id="uid">urn:uuid:loader-test-001</dc:identifier>
                    <dc:title>Clocktower Test</dc:title>
                    <dc:creator>Inspector Lin</dc:creator>
                    <dc:language>en</dc:language>
                  </metadata>
                  <manifest>
                    <item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
                    <item id="ch2" href="ch2.xhtml" media-type="application/xhtml+xml"/>
                  </manifest>
                  <spine>
                    <itemref idref="ch1"/>
                    <itemref idref="ch2"/>
                  </spine>
                </package>
                """.trimIndent().toByteArray(Charsets.UTF_8),
            )
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("OEBPS/ch1.xhtml"))
            zos.write(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <html xmlns="http://www.w3.org/1999/xhtml">
                <head><title>Chapter 1</title></head>
                <body><p>First chapter text.</p></body>
                </html>
                """.trimIndent().toByteArray(Charsets.UTF_8),
            )
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("OEBPS/ch2.xhtml"))
            zos.write(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <html xmlns="http://www.w3.org/1999/xhtml">
                <head><title>Chapter 2</title></head>
                <body><p>Second chapter text.</p></body>
                </html>
                """.trimIndent().toByteArray(Charsets.UTF_8),
            )
            zos.closeEntry()
        }
        return targetFile
    }
}
