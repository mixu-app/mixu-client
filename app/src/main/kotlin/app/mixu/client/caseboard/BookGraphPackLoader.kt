// SPDX-License-Identifier: MPL-2.0
package app.mixu.client.caseboard

import app.mixu.client.db.BookAssetRecord
import app.mixu.client.importer.BookImportResult
import app.mixu.client.importer.BookImportService
import app.mixu.client.logging.StructuredLogger
import app.mixu.client.reader.EpubBookPublication
import app.mixu.graph.AnchorId
import app.mixu.graph.EditionId
import app.mixu.graph.Entity
import app.mixu.graph.EntityId
import app.mixu.graph.EntityName
import app.mixu.graph.EntityType
import app.mixu.graph.EvidenceAnchorId
import app.mixu.graph.GraphJson
import app.mixu.graph.GraphPack
import app.mixu.graph.GraphPackManifest
import app.mixu.graph.Locator
import app.mixu.graph.ManifestCounts
import app.mixu.graph.NameId
import app.mixu.graph.NameKind
import app.mixu.graph.Provenance
import app.mixu.graph.RevealAnchor
import app.mixu.graph.RevealKind
import app.mixu.graph.WorkId
import kotlinx.serialization.decodeFromString
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Loads companion `.graph.json` narrative graph packs for imported books,
 * synchronizes staging directories (`import/`), and provides a spine-aligned
 * fallback [GraphPack] when a book does not yet have an extracted graph pack.
 */
class BookGraphPackLoader(
    private val packsDirectory: File,
    private val logger: StructuredLogger = StructuredLogger.default,
) {
    init {
        packsDirectory.mkdirs()
    }

    /**
     * Scans [importDirectories] for `.graph.json` and `.epub` files, copying graph packs
     * into [packsDirectory] and importing EPUBs via [importService].
     *
     * Returns the list of newly imported or matched book IDs.
     */
    fun syncImportDirectories(
        importDirectories: List<File>,
        importService: BookImportService,
    ): List<String> {
        packsDirectory.mkdirs()
        val discoveredBookIds = mutableListOf<String>()

        for (dir in importDirectories) {
            if (!dir.exists() || !dir.isDirectory) continue
            val files = dir.listFiles()?.sortedBy { it.name }.orEmpty()

            // First copy any companion .graph.json packs
            for (file in files) {
                if (file.isFile && file.name.endsWith(".graph.json", ignoreCase = true)) {
                    runCatching {
                        val dest = File(packsDirectory, file.name)
                        if (file.canonicalPath != dest.canonicalPath) {
                            Files.copy(
                                file.toPath(),
                                dest.toPath(),
                                StandardCopyOption.REPLACE_EXISTING,
                            )
                        }
                        // Also index by editionId if present inside manifest
                        val pack = GraphJson.decodeFromString<GraphPack>(dest.readText())
                        val edValue = pack.manifest.editionId.value
                        val shortHash = edValue.substringAfterLast(':')
                        if (shortHash.isNotBlank() && shortHash != edValue) {
                            val aliasDest = File(packsDirectory, "$shortHash.graph.json")
                            if (dest.canonicalPath != aliasDest.canonicalPath) {
                                Files.copy(
                                    dest.toPath(),
                                    aliasDest.toPath(),
                                    StandardCopyOption.REPLACE_EXISTING,
                                )
                            }
                        }
                    }.onFailure { err ->
                        logger.warn(
                            message = "Failed to stage companion graph pack",
                            attributes =
                                mapOf(
                                    "event" to "graph_pack_stage_failed",
                                    "error" to (err.message ?: "unknown"),
                                ),
                        )
                    }
                }
            }

            // Next import any .epub files
            for (file in files) {
                if (file.isFile && file.name.endsWith(".epub", ignoreCase = true)) {
                    when (val res = importService.importFile(file)) {
                        is BookImportResult.Imported -> {
                            discoveredBookIds.add(res.bookItem.id)
                            // Check if a matching <stem>.graph.json was alongside the EPUB
                            val stem = file.nameWithoutExtension
                            val companion = File(dir, "$stem.graph.json")
                            if (companion.isFile) {
                                copyCompanionForAsset(companion, res.asset)
                            }
                        }
                        is BookImportResult.Duplicate -> {
                            discoveredBookIds.add(res.existingBookItem.id)
                            val stem = file.nameWithoutExtension
                            val companion = File(dir, "$stem.graph.json")
                            if (companion.isFile) {
                                copyCompanionForAsset(companion, res.existingAsset)
                            }
                        }
                        is BookImportResult.Failed -> Unit
                    }
                }
            }
        }
        return discoveredBookIds
    }

    /**
     * Resolves the best available [GraphPack] for [asset] and optional [publication].
     *
     * Priority:
     * 1. Exact `<sha256>.graph.json` or `<shortHash>.graph.json` in [packsDirectory].
     * 2. Any `.graph.json` in [packsDirectory] whose `manifest.editionId` matches [asset].
     * 3. Spine-aligned fallback [GraphPack] constructed from [publication] chapters.
     * 4. [SyntheticMysteryData.createMysteryGraphPack] if no asset/publication is available.
     */
    fun loadForBook(
        asset: BookAssetRecord?,
        publication: EpubBookPublication? = null,
    ): GraphPack {
        if (asset != null) {
            val sha = asset.sha256Checksum.lowercase()
            val shortHash = sha.take(16)
            val candidates =
                listOf(
                    File(packsDirectory, "$sha.graph.json"),
                    File(packsDirectory, "$shortHash.graph.json"),
                    File(
                        File(asset.localStoragePath).parentFile ?: packsDirectory,
                        "$sha.graph.json",
                    ),
                    File(
                        File(asset.localStoragePath).parentFile ?: packsDirectory,
                        "$shortHash.graph.json",
                    ),
                )
            for (candidate in candidates) {
                if (candidate.isFile) {
                    val loaded = decodeGraphPackOrNull(candidate)
                    if (loaded != null) {
                        return loaded
                    }
                }
            }

            // Scan packsDirectory for a matching editionId
            val expectedEditionId = asset.editionId ?: "urn:mixu:edition:$shortHash"
            val packFiles =
                packsDirectory
                    .listFiles()
                    ?.filter { it.isFile && it.name.endsWith(".graph.json", ignoreCase = true) }
                    .orEmpty()
            for (packFile in packFiles) {
                val loaded = decodeGraphPackOrNull(packFile)
                if (loaded != null && loaded.manifest.editionId.value == expectedEditionId) {
                    return loaded
                }
            }

            if (publication != null) {
                return createSpineFallbackPack(asset, publication)
            }
        }
        return SyntheticMysteryData.createMysteryGraphPack()
    }

    private fun copyCompanionForAsset(
        companionFile: File,
        asset: BookAssetRecord,
    ) {
        runCatching {
            val sha = asset.sha256Checksum.lowercase()
            val shortHash = sha.take(16)
            Files.copy(
                companionFile.toPath(),
                File(packsDirectory, "$sha.graph.json").toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
            Files.copy(
                companionFile.toPath(),
                File(packsDirectory, "$shortHash.graph.json").toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    private fun decodeGraphPackOrNull(file: File): GraphPack? =
        runCatching {
            GraphJson.decodeFromString<GraphPack>(file.readText(Charsets.UTF_8))
        }.onFailure { err ->
            logger.warn(
                message = "Failed to decode GraphPack JSON",
                attributes =
                    mapOf(
                        "event" to "graph_pack_decode_failed",
                        "file" to file.name,
                        "error" to (err.message ?: "unknown"),
                    ),
            )
        }.getOrNull()

    companion object {
        /**
         * Creates a valid spine-aligned [GraphPack] for an imported EPUB that does not yet
         * have an AI-extracted `.graph.json` companion pack.
         */
        fun createSpineFallbackPack(
            asset: BookAssetRecord,
            publication: EpubBookPublication,
        ): GraphPack {
            val sha = asset.sha256Checksum.lowercase()
            val shortHash = sha.take(16)
            val workId = WorkId("urn:mixu:work:$shortHash")
            val editionId = EditionId(asset.editionId ?: "urn:mixu:edition:$shortHash")
            val spineItems = publication.metadata.spineItems
            val bookTitle = publication.metadata.title
            val spineCount = spineItems.size.coerceAtLeast(1)

            val anchors =
                if (spineItems.isNotEmpty()) {
                    spineItems.mapIndexed { idx, spine ->
                        RevealAnchor(
                            anchorId = AnchorId("anchor:ord_$idx"),
                            editionId = editionId,
                            ordinal = idx,
                            locator =
                                Locator(
                                    href = spine.href,
                                    type = spine.mediaType,
                                    title = spine.title,
                                    progression = 0.0,
                                    totalProgression =
                                        (idx.toDouble() / spineCount.toDouble())
                                            .coerceIn(0.0, 1.0),
                                ),
                            revealKind = RevealKind.CHAPTER_START,
                        )
                    }
                } else {
                    listOf(
                        RevealAnchor(
                            anchorId = AnchorId("anchor:ord_0"),
                            editionId = editionId,
                            ordinal = 0,
                            locator =
                                Locator(
                                    href = "text/ch1.xhtml",
                                    type = "application/xhtml+xml",
                                    title = bookTitle,
                                ),
                            revealKind = RevealKind.CHAPTER_START,
                        ),
                    )
                }

            val rootEntityId = EntityId("ent:book_$shortHash")
            val rootNameId = NameId("name:book_$shortHash:canonical")
            val entities =
                listOf(
                    Entity(
                        entityId = rootEntityId,
                        entityType = EntityType.OBJECT,
                        firstReveal = 0,
                        canonicalNameId = rootNameId,
                    ),
                )
            val names =
                listOf(
                    EntityName(
                        nameId = rootNameId,
                        entityId = rootEntityId,
                        displayText = bookTitle,
                        firstReveal = 0,
                        nameKind = NameKind.CANONICAL,
                        evidenceAnchorId = EvidenceAnchorId("anchor:ord_0"),
                    ),
                )

            val manifest =
                GraphPackManifest(
                    graphSchemaVersion = 1,
                    workId = workId,
                    editionId = editionId,
                    graphVersion = "1.0.0",
                    revealAnchorMapVersion = 1,
                    checksumSha256 = sha,
                    packageFormat = "json",
                    publicationPolicy = "PRIVATE_ONLY",
                    sourceExcerptPolicy = "LOCATOR_ONLY",
                    createdAt = "2026-11-08T00:00:00Z",
                    copyrightBasis = "PRIVATE_USER_IMPORT",
                    provenance = Provenance(validatorVersion = "1.0.0"),
                    counts =
                        ManifestCounts(
                            entities = entities.size,
                            assertions = 0,
                            events = 0,
                            evidenceAnchors = anchors.size,
                        ),
                )

            return GraphPack.canonical(
                manifest = manifest,
                entities = entities,
                names = names,
                assertions = emptyList(),
                transitions = emptyList(),
                anchors = anchors,
            )
        }
    }
}
