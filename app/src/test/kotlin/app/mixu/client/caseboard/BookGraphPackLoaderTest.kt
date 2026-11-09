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

    @Test
    fun `epub cover image extraction and straight-line single edge layout work`() {
        val tempDir = Files.createTempDirectory("mixu-image-graph-test").toFile()
        try {
            val epubFile = tempDir.resolve("illustrated-mystery.epub")
            val dummyJpgBytes = ByteArray(4096) { (it % 251).toByte() }
            ZipOutputStream(epubFile.outputStream()).use { zos ->
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
                        <dc:identifier id="uid">urn:uuid:illustrated-001</dc:identifier>
                        <dc:title>三幕的杀意</dc:title>
                        <dc:creator>中町信</dc:creator>
                        <dc:language>zh</dc:language>
                      </metadata>
                      <manifest>
                        <item id="cover" href="Text/cover.xhtml" media-type="application/xhtml+xml"/>
                        <item id="cast" href="Text/cast.xhtml" media-type="application/xhtml+xml"/>
                        <item id="ch1" href="Text/ch1.xhtml" media-type="application/xhtml+xml"/>
                        <item id="img_cover" href="Images/cover.jpg" media-type="image/jpeg"/>
                        <item id="img_map" href="Images/map01.jpg" media-type="image/jpeg"/>
                      </manifest>
                      <spine>
                        <itemref idref="cover"/>
                        <itemref idref="cast"/>
                        <itemref idref="ch1"/>
                      </spine>
                    </package>
                    """.trimIndent().toByteArray(Charsets.UTF_8),
                )
                zos.closeEntry()

                zos.putNextEntry(ZipEntry("OEBPS/Images/cover.jpg"))
                zos.write(dummyJpgBytes)
                zos.closeEntry()

                zos.putNextEntry(ZipEntry("OEBPS/Images/map01.jpg"))
                zos.write(dummyJpgBytes)
                zos.closeEntry()

                zos.putNextEntry(ZipEntry("OEBPS/Text/cover.xhtml"))
                zos.write(
                    """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <html xmlns="http://www.w3.org/1999/xhtml" xmlns:xlink="http://www.w3.org/1999/xlink">
                    <head><title>Cover</title></head>
                    <body><svg><image width="800" height="1125" xlink:href="../Images/cover.jpg"/></svg></body>
                    </html>
                    """.trimIndent().toByteArray(Charsets.UTF_8),
                )
                zos.closeEntry()

                zos.putNextEntry(ZipEntry("OEBPS/Text/cast.xhtml"))
                zos.write(
                    """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <html xmlns="http://www.w3.org/1999/xhtml">
                    <head><title>登场人物</title></head>
                    <body>
                      <p>小野寺照夫：沼田中央高中职员</p>
                      <p>牧村良介：出版社编辑</p>
                      <div class="duokan-image-single"><img alt="案发现场平面图" src="../Images/map01.jpg"/></div>
                    </body>
                    </html>
                    """.trimIndent().toByteArray(Charsets.UTF_8),
                )
                zos.closeEntry()

                zos.putNextEntry(ZipEntry("OEBPS/Text/ch1.xhtml"))
                zos.write(
                    """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <html xmlns="http://www.w3.org/1999/xhtml">
                    <head><title>第一章</title></head>
                    <body><p>在山庄的密室里发现了怀表与钥匙。</p></body>
                    </html>
                    """.trimIndent().toByteArray(Charsets.UTF_8),
                )
                zos.closeEntry()
            }

            val pub = EpubBookPublication.open(epubFile)
            assertTrue(
                pub.chapters[0].paragraphs.any {
                    it.startsWith(EpubBookPublication.IMAGE_TOKEN_PREFIX)
                },
            )
            assertTrue(
                pub.chapters[1].paragraphs.any {
                    it.startsWith(EpubBookPublication.IMAGE_TOKEN_PREFIX) &&
                        it.contains("案发现场平面图")
                },
            )

            val basePack = SyntheticMysteryData.createMysteryGraphPack()
            val controller =
                CaseBoardController(
                    graphPack = basePack,
                    initialReadingPosition = 2,
                    initialHorizon = 2,
                    initialViewPosition = 2,
                )
            val view = controller.state.revealedGraphView!!
            val layout =
                ForceDirectedGraphEngine.computeLayout(
                    editionId = basePack.manifest.editionId.value,
                    entities = view.entities,
                    assertions = view.assertions,
                )
            assertTrue(layout.edges.isNotEmpty())
            assertTrue(layout.edges.any { kotlin.math.abs(it.curvature) < 0.001f })
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
