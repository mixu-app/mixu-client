// SPDX-License-Identifier: MPL-2.0
package app.mixu.client.db

import android.database.sqlite.SQLiteDatabase
import app.mixu.client.config.ReaderDisplayConfig
import app.mixu.client.library.BookItem
import app.mixu.client.logging.StructuredLogger
import app.mixu.graph.Locator
import app.mixu.graph.OverlayKind
import app.mixu.graph.OverlayObjectType
import app.mixu.graph.OverlayVisibility
import java.io.File
import java.time.Instant
import kotlin.math.roundToInt

/**
 * Typed migration failure hierarchy for local SQLite schema migrations.
 */
sealed class DatabaseMigrationException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {
    /**
     * Thrown when migrating to a target version requires a sequential step
     * [fromVersion] -> [toVersion] that is not present in the registered migration map.
     */
    data class MissingMigration(
        val fromVersion: Int,
        val toVersion: Int,
    ) : DatabaseMigrationException(
            "Missing required schema migration step from v$fromVersion to v$toVersion",
        )

    /**
     * Thrown when the requested target version is lower than the currently applied schema version.
     */
    data class InvalidMigrationTarget(
        val currentVersion: Int,
        val targetVersion: Int,
    ) : DatabaseMigrationException(
            "Cannot downgrade schema from v$currentVersion to v$targetVersion",
        )

    /**
     * Thrown when a migration step fails during SQLite execution.
     */
    class MigrationExecutionFailure(
        val fromVersion: Int,
        val toVersion: Int,
        cause: Throwable,
    ) : DatabaseMigrationException(
            "Failed executing schema migration from v$fromVersion to v$toVersion",
            cause,
        )
}

/**
 * Defines a single sequential migration step from [fromVersion] to [toVersion].
 */
data class SchemaMigration(
    val fromVersion: Int,
    val toVersion: Int,
    val description: String,
    val statements: List<String>,
) {
    init {
        require(fromVersion >= 0) { "fromVersion must be non-negative: $fromVersion" }
        require(toVersion == fromVersion + 1) {
            "SchemaMigration must advance by 1 version ($fromVersion -> ${fromVersion + 1})"
        }
        require(description.isNotBlank()) { "description cannot be blank" }
        require(statements.isNotEmpty()) { "statements cannot be empty" }
    }
}

/**
 * Audit record of an applied schema migration in `schema_migrations`.
 */
data class SchemaMigrationRecord(
    val version: Int,
    val appliedAt: String,
    val description: String,
)

/**
 * Abstract literary work record (`works` table).
 */
data class WorkRecord(
    val workId: String,
    val canonicalTitle: String,
    val primaryAuthor: String,
    val languageCode: String = "und",
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * Particular text arrangement or translation of a work (`editions` table).
 */
data class EditionRecord(
    val editionId: String,
    val workId: String? = null,
    val title: String,
    val author: String,
    val languageCode: String = "und",
    val identifier: String? = null,
    val format: String = "EPUB",
    val spineCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * Generic publication file asset record (`book_assets` table) per `publication-formats.md`.
 */
data class BookAssetRecord(
    val assetId: String,
    val editionId: String? = null,
    val formatCode: String = "EPUB",
    val mediaType: String = "application/epub+zip",
    val sha256Checksum: String,
    val byteSize: Long,
    val localStoragePath: String,
    val parserVersion: String = "1.0.0",
    val formatMetadataJson: String = "{}",
    val importedAt: Long = System.currentTimeMillis(),
) {
    init {
        require(assetId.isNotBlank()) { "assetId cannot be blank" }
        require(formatCode.isNotBlank()) { "formatCode cannot be blank" }
        require(mediaType.isNotBlank()) { "mediaType cannot be blank" }
        require(sha256Checksum.matches(Regex("^[0-9a-fA-F]{64}$"))) {
            "sha256Checksum must be a 64-character hex string"
        }
        require(byteSize >= 0L) { "byteSize must be non-negative" }
        require(localStoragePath.isNotBlank()) { "localStoragePath cannot be blank" }
    }
}

/**
 * Reader library membership record (`library_items` table).
 */
data class LibraryItemRecord(
    val bookId: String,
    val assetId: String,
    val editionId: String? = null,
    val displayTitle: String,
    val displayAuthor: String,
    val coverPath: String? = null,
    val coverPlaceholderColor: Long = 0xFF1E3A8AL,
    val totalChapters: Int = 0,
    val addedAt: Long = System.currentTimeMillis(),
    val lastOpenedAt: Long = 0L,
    val isArchived: Boolean = false,
) {
    init {
        require(bookId.isNotBlank()) { "bookId cannot be blank" }
        require(assetId.isNotBlank()) { "assetId cannot be blank" }
        require(displayTitle.isNotBlank()) { "displayTitle cannot be blank" }
        require(displayAuthor.isNotBlank()) { "displayAuthor cannot be blank" }
        require(totalChapters >= 0) { "totalChapters must be non-negative" }
    }
}

/**
 * Tripartite reading progress and locator record (`reading_progress` table).
 */
data class ReadingProgressRecord(
    val bookId: String,
    val readingLocator: Locator,
    val progression: Double =
        (readingLocator.totalProgression ?: readingLocator.progression ?: 0.0)
            .coerceIn(0.0, 1.0),
    val knowledgeHorizonOrdinal: Int = 0,
    val graphViewOrdinal: Int = 0,
    val activeGraphPackId: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
) {
    init {
        require(bookId.isNotBlank()) { "bookId cannot be blank" }
        require(progression in 0.0..1.0) { "progression must be in 0.0..1.0, got $progression" }
        require(knowledgeHorizonOrdinal >= 0) { "knowledgeHorizonOrdinal must be non-negative" }
        require(graphViewOrdinal >= 0) { "graphViewOrdinal must be non-negative" }
    }
}

/**
 * Reader bookmark record with versioned tombstone support (`bookmarks` table).
 */
data class BookmarkRecord(
    val bookmarkId: String,
    val bookId: String,
    val locator: Locator,
    val label: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
    val version: Int = 1,
    val isTombstone: Boolean = false,
) {
    init {
        require(bookmarkId.isNotBlank()) { "bookmarkId cannot be blank" }
        require(bookId.isNotBlank()) { "bookId cannot be blank" }
        require(version >= 1) { "version must be >= 1" }
    }
}

/**
 * Persistent user overlay annotation record (`user_overlays` table).
 */
data class UserOverlayRecord(
    val overlayId: String,
    val bookId: String,
    val objectType: OverlayObjectType,
    val objectId: String,
    val deviceId: String? = null,
    val createdAt: String = Instant.now().toString(),
    val createdAtReveal: Int = 0,
    val kind: OverlayKind,
    val payloadJson: String = "{}",
    val visibility: OverlayVisibility = OverlayVisibility.FROM_CREATION,
    val isTombstone: Boolean = false,
) {
    init {
        require(overlayId.isNotBlank()) { "overlayId cannot be blank" }
        require(bookId.isNotBlank()) { "bookId cannot be blank" }
        require(objectId.isNotBlank()) { "objectId cannot be blank" }
        require(createdAtReveal >= 0) { "createdAtReveal must be non-negative" }
    }
}

/**
 * Local mutation outbox entry for deferred cloud sync (`sync_outbox` table).
 */
data class SyncOutboxRecord(
    val mutationId: String,
    val entityTable: String,
    val entityId: String,
    val operation: String,
    val payloadJson: String,
    val createdAt: Long = System.currentTimeMillis(),
    val syncedAt: Long? = null,
)

/**
 * Low-level SQLite execution contract backed either by Android's `SQLiteDatabase`
 * on device or by the system `sqlite3` engine in host JVM tests.
 */
interface SqlEngine : AutoCloseable {
    fun executeScript(sqlScript: String)

    fun queryHexEncodedRows(
        selectExpressions: List<String>,
        fromAndWhereClause: String,
    ): List<List<String?>>
}

/**
 * SQLite engine invoking the standard `sqlite3` binary in batch mode against [dbFile].
 * Ensures deterministic on-disk SQLite 3 persistence in both JVM unit tests and Linux hosts.
 */
internal class CliSqlEngine(
    private val dbFile: File,
) : SqlEngine {
    private var closed = false

    init {
        dbFile.parentFile?.mkdirs()
    }

    override fun executeScript(sqlScript: String) {
        check(!closed) { "Database engine is closed" }
        val fullInput =
            buildString {
                appendLine(".bail on")
                appendLine("PRAGMA foreign_keys = ON;")
                appendLine(sqlScript)
            }
        runSqliteProcess(fullInput)
    }

    override fun queryHexEncodedRows(
        selectExpressions: List<String>,
        fromAndWhereClause: String,
    ): List<List<String?>> {
        check(!closed) { "Database engine is closed" }
        require(selectExpressions.isNotEmpty()) { "selectExpressions cannot be empty" }
        val projected =
            selectExpressions.joinToString(", ") { expr ->
                "CASE WHEN ($expr) IS NULL THEN '$NULL_SENTINEL' " +
                    "ELSE hex(CAST(($expr) AS BLOB)) END"
            }
        val querySql =
            buildString {
                appendLine(".bail on")
                appendLine(".mode list")
                appendLine(".headers off")
                appendLine(".separator |")
                appendLine("PRAGMA foreign_keys = ON;")
                append("SELECT ")
                append(projected)
                append(' ')
                append(fromAndWhereClause.trim())
                if (!fromAndWhereClause.trim().endsWith(";")) {
                    append(';')
                }
                appendLine()
            }
        val stdout = runSqliteProcess(querySql)
        if (stdout.isBlank()) {
            return emptyList()
        }
        return stdout
            .lineSequence()
            .map { it.trimEnd('\r') }
            .filter { it.isNotEmpty() }
            .map { line ->
                val parts = line.split('|')
                parts.map { token -> decodeHexOrNull(token) }
            }.toList()
    }

    override fun close() {
        closed = true
    }

    private fun runSqliteProcess(stdinSql: String): String {
        val process =
            ProcessBuilder("sqlite3", "-batch", dbFile.absolutePath)
                .redirectErrorStream(false)
                .start()
        process.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.write(stdinSql)
            writer.flush()
        }
        val stdout = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val stderr = process.errorStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            throw IllegalStateException(
                "SQLite execution failed (exit=$exitCode): ${stderr.trim()}",
            )
        }
        return stdout
    }

    companion object {
        internal const val NULL_SENTINEL = "~NULL~"

        internal fun decodeHexOrNull(token: String): String? {
            if (token == NULL_SENTINEL) {
                return null
            }
            if (token.isEmpty()) {
                return ""
            }
            val bytes = ByteArray(token.length / 2)
            var i = 0
            while (i < token.length) {
                val hi = Character.digit(token[i], 16)
                val lo = Character.digit(token[i + 1], 16)
                require(hi >= 0 && lo >= 0) { "Invalid hex token from SQLite: $token" }
                bytes[i / 2] = ((hi shl 4) or lo).toByte()
                i += 2
            }
            return String(bytes, Charsets.UTF_8)
        }
    }
}

/**
 * Android runtime SQLite engine backed by [SQLiteDatabase].
 */
internal class AndroidSqlEngine(
    private val sqliteDb: SQLiteDatabase,
) : SqlEngine {
    private var closed = false

    init {
        sqliteDb.execSQL("PRAGMA foreign_keys = ON;")
    }

    override fun executeScript(sqlScript: String) {
        check(!closed) { "Database engine is closed" }
        val statements = splitSqlStatements(sqlScript)
        sqliteDb.beginTransaction()
        try {
            for (stmt in statements) {
                val trimmed = stmt.trim()
                if (trimmed.isNotEmpty() &&
                    !trimmed.equals("BEGIN IMMEDIATE", ignoreCase = true) &&
                    !trimmed.equals("BEGIN", ignoreCase = true) &&
                    !trimmed.equals("COMMIT", ignoreCase = true)
                ) {
                    sqliteDb.execSQL(trimmed)
                }
            }
            sqliteDb.setTransactionSuccessful()
        } finally {
            sqliteDb.endTransaction()
        }
    }

    override fun queryHexEncodedRows(
        selectExpressions: List<String>,
        fromAndWhereClause: String,
    ): List<List<String?>> {
        check(!closed) { "Database engine is closed" }
        val sentinel = CliSqlEngine.NULL_SENTINEL
        val projected =
            selectExpressions.joinToString(", ") { expr ->
                "CASE WHEN ($expr) IS NULL THEN '$sentinel' ELSE hex(CAST(($expr) AS BLOB)) END"
            }
        val sql = "SELECT $projected ${fromAndWhereClause.trim().removeSuffix(";")}"
        val rows = mutableListOf<List<String?>>()
        sqliteDb.rawQuery(sql, null).use { cursor ->
            val colCount = selectExpressions.size
            while (cursor.moveToNext()) {
                val row = ArrayList<String?>(colCount)
                for (i in 0 until colCount) {
                    val token =
                        if (cursor.isNull(i)) {
                            CliSqlEngine.NULL_SENTINEL
                        } else {
                            cursor.getString(i)
                        }
                    row.add(CliSqlEngine.decodeHexOrNull(token))
                }
                rows.add(row)
            }
        }
        return rows
    }

    override fun close() {
        if (!closed) {
            closed = true
            sqliteDb.close()
        }
    }

    companion object {
        internal fun splitSqlStatements(script: String): List<String> {
            val result = mutableListOf<String>()
            val current = StringBuilder()
            var inSingleQuote = false
            var i = 0
            while (i < script.length) {
                val ch = script[i]
                if (ch == '\'' && !inSingleQuote) {
                    inSingleQuote = true
                    current.append(ch)
                } else if (ch == '\'' && inSingleQuote) {
                    if (i + 1 < script.length && script[i + 1] == '\'') {
                        current.append("''")
                        i++
                    } else {
                        inSingleQuote = false
                        current.append(ch)
                    }
                } else if (
                    !inSingleQuote &&
                    ch == '-' &&
                    i + 1 < script.length &&
                    script[i + 1] == '-'
                ) {
                    while (i < script.length && script[i] != '\n') {
                        i++
                    }
                } else if (!inSingleQuote && ch == ';') {
                    val stmt = current.toString().trim()
                    if (stmt.isNotEmpty()) {
                        result.add(stmt)
                    }
                    current.setLength(0)
                } else {
                    current.append(ch)
                }
                i++
            }
            val trailing = current.toString().trim()
            if (trailing.isNotEmpty()) {
                result.add(trailing)
            }
            return result
        }
    }
}

/**
 * Local SQLite database manager for `library.db` with schema v1 initialization,
 * sequential migration verification, and privacy-preserving structured logging.
 */
class LocalLibraryDatabase private constructor(
    val dbFile: File,
    private val engine: SqlEngine,
    private val logger: StructuredLogger,
) : AutoCloseable {
    private var isClosed: Boolean = false

    /**
     * Returns the highest schema version recorded in `schema_migrations`, or 0 if uninitialized.
     */
    fun getCurrentSchemaVersion(): Int {
        ensureOpen()
        val tableExistsRows =
            engine.queryHexEncodedRows(
                selectExpressions = listOf("COUNT(*)"),
                fromAndWhereClause =
                    "FROM sqlite_master WHERE type = 'table' AND name = 'schema_migrations'",
            )
        val exists = (tableExistsRows.firstOrNull()?.firstOrNull()?.toIntOrNull() ?: 0) > 0
        if (!exists) {
            return 0
        }
        val rows =
            engine.queryHexEncodedRows(
                selectExpressions = listOf("COALESCE(MAX(version), 0)"),
                fromAndWhereClause = "FROM schema_migrations",
            )
        return rows.firstOrNull()?.firstOrNull()?.toIntOrNull() ?: 0
    }

    /**
     * Lists all applied schema migrations ordered by version ascending.
     */
    fun listAppliedMigrations(): List<SchemaMigrationRecord> {
        ensureOpen()
        if (getCurrentSchemaVersion() == 0) {
            return emptyList()
        }
        val rows =
            engine.queryHexEncodedRows(
                selectExpressions = listOf("version", "applied_at", "description"),
                fromAndWhereClause = "FROM schema_migrations ORDER BY version ASC",
            )
        return rows.map { row ->
            SchemaMigrationRecord(
                version = row[0]!!.toInt(),
                appliedAt = row[1]!!,
                description = row[2]!!,
            )
        }
    }

    /**
     * Migrates the database from its current schema version to [targetVersion] using the
     * sequential steps provided in [migrations] (keyed by `fromVersion`).
     *
     * Throws [DatabaseMigrationException.MissingMigration] if any step `v -> v + 1` is missing.
     */
    fun migrateTo(
        targetVersion: Int,
        migrations: Map<Int, SchemaMigration> = emptyMap(),
    ) {
        ensureOpen()
        val allMigrations = LinkedHashMap<Int, SchemaMigration>()
        allMigrations[0] = V1_MIGRATION
        for ((k, v) in migrations) {
            allMigrations[k] = v
        }

        val currentVersion = getCurrentSchemaVersion()
        if (targetVersion < currentVersion) {
            throw DatabaseMigrationException.InvalidMigrationTarget(
                currentVersion = currentVersion,
                targetVersion = targetVersion,
            )
        }
        if (targetVersion == currentVersion) {
            return
        }

        // First validate that every required sequential step exists before mutating anything.
        for (stepFrom in currentVersion until targetVersion) {
            val stepTo = stepFrom + 1
            val migration = allMigrations[stepFrom]
            if (
                migration == null ||
                migration.fromVersion != stepFrom ||
                migration.toVersion != stepTo
            ) {
                logger.error(
                    message = "Database schema migration step missing",
                    attributes =
                        mapOf(
                            "event" to "db_migration_missing",
                            "from_version" to stepFrom,
                            "to_version" to stepTo,
                            "target_version" to targetVersion,
                        ),
                )
                throw DatabaseMigrationException.MissingMigration(
                    fromVersion = stepFrom,
                    toVersion = stepTo,
                )
            }
        }

        // Apply each sequential step transactionally.
        for (stepFrom in currentVersion until targetVersion) {
            val stepTo = stepFrom + 1
            val migration = allMigrations.getValue(stepFrom)
            val nowIso = Instant.now().toString()
            val txScript =
                buildString {
                    appendLine("BEGIN IMMEDIATE;")
                    for (stmt in migration.statements) {
                        val trimmed = stmt.trim()
                        if (trimmed.isNotEmpty()) {
                            append(trimmed)
                            if (!trimmed.endsWith(";")) {
                                append(';')
                            }
                            appendLine()
                        }
                    }
                    append("INSERT OR REPLACE INTO schema_migrations")
                    append("(version, applied_at, description) VALUES (")
                    append(stepTo).append(", ")
                    append(sqlText(nowIso)).append(", ")
                    append(sqlText(migration.description))
                    appendLine(");")
                    appendLine("COMMIT;")
                }
            try {
                engine.executeScript(txScript)
                logger.info(
                    message = "Database schema migration applied",
                    attributes =
                        mapOf(
                            "event" to "db_migrated",
                            "from_version" to stepFrom,
                            "to_version" to stepTo,
                        ),
                )
            } catch (t: Throwable) {
                logger.error(
                    message = "Database schema migration execution failed",
                    attributes =
                        mapOf(
                            "event" to "db_migration_failed",
                            "from_version" to stepFrom,
                            "to_version" to stepTo,
                        ),
                    throwable = t,
                )
                throw DatabaseMigrationException.MigrationExecutionFailure(
                    fromVersion = stepFrom,
                    toVersion = stepTo,
                    cause = t,
                )
            }
        }
    }

    /**
     * Overload accepting a list of [SchemaMigration] steps.
     */
    fun migrateTo(
        targetVersion: Int,
        migrations: List<SchemaMigration>,
    ) {
        migrateTo(targetVersion, migrations.associateBy { it.fromVersion })
    }

    /**
     * Inserts or updates a [WorkRecord], [EditionRecord], [BookAssetRecord],
     * and [LibraryItemRecord] in a single atomic transaction.
     */
    fun upsertBookAssetAndLibraryItem(
        asset: BookAssetRecord,
        item: LibraryItemRecord,
        edition: EditionRecord? = null,
        work: WorkRecord? = null,
    ) {
        ensureOpen()
        val script =
            buildString {
                appendLine("BEGIN IMMEDIATE;")
                if (work != null) {
                    append("INSERT OR REPLACE INTO works(")
                    append("work_id, canonical_title, primary_author, language_code, created_at")
                    append(") VALUES (")
                    append(sqlText(work.workId)).append(", ")
                    append(sqlText(work.canonicalTitle)).append(", ")
                    append(sqlText(work.primaryAuthor)).append(", ")
                    append(sqlText(work.languageCode)).append(", ")
                    append(work.createdAt).appendLine(");")
                }
                if (edition != null) {
                    append("INSERT OR REPLACE INTO editions(")
                    append("edition_id, work_id, title, author, language_code, ")
                    append("identifier, format, spine_count, created_at")
                    append(") VALUES (")
                    append(sqlText(edition.editionId)).append(", ")
                    append(sqlNullableText(edition.workId)).append(", ")
                    append(sqlText(edition.title)).append(", ")
                    append(sqlText(edition.author)).append(", ")
                    append(sqlText(edition.languageCode)).append(", ")
                    append(sqlNullableText(edition.identifier)).append(", ")
                    append(sqlText(edition.format)).append(", ")
                    append(edition.spineCount).append(", ")
                    append(edition.createdAt).appendLine(");")
                }
                append("INSERT OR REPLACE INTO book_assets(")
                append("asset_id, edition_id, format_code, media_type, sha256_checksum, ")
                append("byte_size, local_storage_path, parser_version, format_metadata_json, ")
                append("imported_at) VALUES (")
                append(sqlText(asset.assetId)).append(", ")
                append(sqlNullableText(asset.editionId)).append(", ")
                append(sqlText(asset.formatCode)).append(", ")
                append(sqlText(asset.mediaType)).append(", ")
                append(sqlText(asset.sha256Checksum.lowercase())).append(", ")
                append(asset.byteSize).append(", ")
                append(sqlText(asset.localStoragePath)).append(", ")
                append(sqlText(asset.parserVersion)).append(", ")
                append(sqlText(asset.formatMetadataJson)).append(", ")
                append(asset.importedAt).appendLine(");")

                append("INSERT OR REPLACE INTO library_items(")
                append("book_id, asset_id, edition_id, display_title, display_author, ")
                append("cover_path, cover_placeholder_color, total_chapters, ")
                append("added_at, last_opened_at, is_archived) VALUES (")
                append(sqlText(item.bookId)).append(", ")
                append(sqlText(item.assetId)).append(", ")
                append(sqlNullableText(item.editionId)).append(", ")
                append(sqlText(item.displayTitle)).append(", ")
                append(sqlText(item.displayAuthor)).append(", ")
                append(sqlNullableText(item.coverPath)).append(", ")
                append(item.coverPlaceholderColor).append(", ")
                append(item.totalChapters).append(", ")
                append(item.addedAt).append(", ")
                append(item.lastOpenedAt).append(", ")
                append(if (item.isArchived) 1 else 0).appendLine(");")
                appendLine("COMMIT;")
            }
        engine.executeScript(script)
        logger.info(
            message = "Book asset and library item stored",
            attributes =
                mapOf(
                    "event" to "db_asset_upserted",
                    "book_id" to item.bookId,
                    "format_code" to asset.formatCode,
                    "byte_size" to asset.byteSize,
                    "total_chapters" to item.totalChapters,
                ),
        )
    }

    /**
     * Looks up an imported [BookAssetRecord] by its lowercase SHA-256 hex digest.
     */
    fun findAssetBySha256(sha256Checksum: String): BookAssetRecord? {
        ensureOpen()
        val normalized = sha256Checksum.trim().lowercase()
        val rows =
            engine.queryHexEncodedRows(
                selectExpressions = BOOK_ASSET_COLUMNS,
                fromAndWhereClause =
                    "FROM book_assets WHERE sha256_checksum = ${sqlText(normalized)} LIMIT 1",
            )
        return rows.firstOrNull()?.let { parseBookAssetRow(it) }
    }

    /**
     * Looks up a [BookAssetRecord] by `asset_id`.
     */
    fun getBookAsset(assetId: String): BookAssetRecord? {
        ensureOpen()
        val rows =
            engine.queryHexEncodedRows(
                selectExpressions = BOOK_ASSET_COLUMNS,
                fromAndWhereClause = "FROM book_assets WHERE asset_id = ${sqlText(
                    assetId,
                )} LIMIT 1",
            )
        return rows.firstOrNull()?.let { parseBookAssetRow(it) }
    }

    /**
     * Looks up the [BookAssetRecord] associated with a library `book_id`.
     */
    fun getBookAssetForBook(bookId: String): BookAssetRecord? {
        ensureOpen()
        val clause =
            "FROM book_assets ba " +
                "INNER JOIN library_items li ON li.asset_id = ba.asset_id " +
                "WHERE li.book_id = ${sqlText(bookId)} LIMIT 1"
        val rows =
            engine.queryHexEncodedRows(
                selectExpressions = BOOK_ASSET_COLUMNS.map { "ba.$it" },
                fromAndWhereClause = clause,
            )
        return rows.firstOrNull()?.let { parseBookAssetRow(it) }
    }

    /**
     * Looks up a [LibraryItemRecord] by `book_id`.
     */
    fun getLibraryItem(bookId: String): LibraryItemRecord? {
        ensureOpen()
        val rows =
            engine.queryHexEncodedRows(
                selectExpressions = LIBRARY_ITEM_COLUMNS,
                fromAndWhereClause =
                    "FROM library_items WHERE book_id = ${sqlText(bookId)} LIMIT 1",
            )
        return rows.firstOrNull()?.let { parseLibraryItemRow(it) }
    }

    /**
     * Looks up a [LibraryItemRecord] by `asset_id`.
     */
    fun getLibraryItemByAssetId(assetId: String): LibraryItemRecord? {
        ensureOpen()
        val rows =
            engine.queryHexEncodedRows(
                selectExpressions = LIBRARY_ITEM_COLUMNS,
                fromAndWhereClause =
                    "FROM library_items WHERE asset_id = ${sqlText(assetId)} LIMIT 1",
            )
        return rows.firstOrNull()?.let { parseLibraryItemRow(it) }
    }

    /**
     * Lists raw [LibraryItemRecord] entries in the library.
     */
    fun listLibraryItemRecords(includeArchived: Boolean = false): List<LibraryItemRecord> {
        ensureOpen()
        val filter = if (includeArchived) "" else "WHERE is_archived = 0"
        val clause =
            "FROM library_items $filter " +
                "ORDER BY last_opened_at DESC, added_at DESC, display_title ASC"
        val rows =
            engine.queryHexEncodedRows(
                selectExpressions = LIBRARY_ITEM_COLUMNS,
                fromAndWhereClause = clause,
            )
        return rows.map { parseLibraryItemRow(it) }
    }

    /**
     * Lists library books joined with their latest reading progress as domain [BookItem] objects.
     */
    fun listLibraryItems(includeArchived: Boolean = false): List<BookItem> {
        ensureOpen()
        val filter = if (includeArchived) "" else "WHERE li.is_archived = 0"
        val rows =
            engine.queryHexEncodedRows(
                selectExpressions = JOINED_BOOK_ITEM_COLUMNS,
                fromAndWhereClause =
                    """
                    FROM library_items li
                    LEFT JOIN reading_progress rp ON rp.book_id = li.book_id
                    $filter
                    ORDER BY li.last_opened_at DESC, li.added_at DESC, li.display_title ASC
                    """.trimIndent(),
            )
        return enrichBookItemsWithCollections(rows.map { parseJoinedBookItemRow(it) })
    }

    /**
     * Queries the most recently opened/read books in descending order of last activity.
     */
    fun getLastReadBooks(limit: Int = 10): List<BookItem> {
        ensureOpen()
        require(limit > 0) { "limit must be positive: $limit" }
        val rows =
            engine.queryHexEncodedRows(
                selectExpressions = JOINED_BOOK_ITEM_COLUMNS,
                fromAndWhereClause =
                    """
                    FROM library_items li
                    LEFT JOIN reading_progress rp ON rp.book_id = li.book_id
                    WHERE li.is_archived = 0
                      AND (li.last_opened_at > 0 OR rp.updated_at IS NOT NULL)
                    ORDER BY COALESCE(rp.updated_at, li.last_opened_at) DESC,
                      li.last_opened_at DESC,
                      li.display_title ASC
                    LIMIT $limit
                    """.trimIndent(),
            )
        return enrichBookItemsWithCollections(rows.map { parseJoinedBookItemRow(it) })
    }

    /**
     * Persists a book's favorite status and enqueues a cloud/self-hosted sync mutation.
     */
    fun setBookFavorite(
        bookId: String,
        isFavorite: Boolean,
        updatedAt: Long = System.currentTimeMillis(),
    ) {
        ensureOpen()
        val scopeKey = "book:$bookId"
        val script =
            buildString {
                appendLine("BEGIN IMMEDIATE;")
                append("INSERT OR REPLACE INTO reader_settings(")
                append("scope_key, setting_key, setting_value, updated_at) VALUES (")
                append(sqlText(scopeKey)).append(", ")
                append(sqlText("is_favorite")).append(", ")
                append(sqlText(isFavorite.toString())).append(", ")
                append(updatedAt).appendLine(");")
                appendLine("COMMIT;")
            }
        engine.executeScript(script)
        enqueueSyncMutation(
            SyncOutboxRecord(
                mutationId = "fav-$bookId-$updatedAt",
                entityTable = "book_collections",
                entityId = bookId,
                operation = "SET_FAVORITE",
                payloadJson = """{"bookId":"$bookId","isFavorite":$isFavorite}""",
                createdAt = updatedAt,
            ),
        )
    }

    /**
     * Persists a book's Kindle-style collection memberships and enqueues a cloud/self-hosted sync mutation.
     */
    fun setBookCollections(
        bookId: String,
        collections: Set<String>,
        updatedAt: Long = System.currentTimeMillis(),
    ) {
        ensureOpen()
        val scopeKey = "book:$bookId"
        val serialized =
            collections
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .joinToString("|")
        val script =
            buildString {
                appendLine("BEGIN IMMEDIATE;")
                append("INSERT OR REPLACE INTO reader_settings(")
                append("scope_key, setting_key, setting_value, updated_at) VALUES (")
                append(sqlText(scopeKey)).append(", ")
                append(sqlText("collections")).append(", ")
                append(sqlText(serialized)).append(", ")
                append(updatedAt).appendLine(");")
                appendLine("COMMIT;")
            }
        engine.executeScript(script)
        val jsonArray =
            collections.joinToString(prefix = "[", postfix = "]") {
                "\"${it.replace("\"", "\\\"")}\""
            }
        enqueueSyncMutation(
            SyncOutboxRecord(
                mutationId = "col-$bookId-$updatedAt",
                entityTable = "book_collections",
                entityId = bookId,
                operation = "SET_COLLECTIONS",
                payloadJson = """{"bookId":"$bookId","collections":$jsonArray}""",
                createdAt = updatedAt,
            ),
        )
    }

    /**
     * Persists a book's mystery tags array (e.g. author, locked-room, blizzard-villa)
     * and enqueues a cloud/self-hosted sync mutation into `sync_outbox`.
     */
    fun setBookTags(
        bookId: String,
        tags: List<String>,
        updatedAt: Long = System.currentTimeMillis(),
    ) {
        ensureOpen()
        val scopeKey = "book:$bookId"
        val cleanTags =
            tags
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
        val serialized = cleanTags.joinToString("|")
        val script =
            buildString {
                appendLine("BEGIN IMMEDIATE;")
                append("INSERT OR REPLACE INTO reader_settings(")
                append("scope_key, setting_key, setting_value, updated_at) VALUES (")
                append(sqlText(scopeKey)).append(", ")
                append(sqlText("tags")).append(", ")
                append(sqlText(serialized)).append(", ")
                append(updatedAt).appendLine(");")
                appendLine("COMMIT;")
            }
        engine.executeScript(script)
        val jsonArray =
            cleanTags.joinToString(prefix = "[", postfix = "]") {
                "\"${it.replace("\"", "\\\"")}\""
            }
        enqueueSyncMutation(
            SyncOutboxRecord(
                mutationId = "tag-$bookId-$updatedAt",
                entityTable = "book_tags",
                entityId = bookId,
                operation = "SET_TAGS",
                payloadJson = """{"bookId":"$bookId","tags":$jsonArray}""",
                createdAt = updatedAt,
            ),
        )
    }

    private fun enrichBookItemsWithCollections(items: List<BookItem>): List<BookItem> {
        if (items.isEmpty()) return items
        val rows =
            engine.queryHexEncodedRows(
                selectExpressions = listOf("scope_key", "setting_key", "setting_value"),
                fromAndWhereClause = "FROM reader_settings WHERE scope_key LIKE 'book:%'",
            )
        val favByBook = mutableMapOf<String, Boolean>()
        val colsByBook = mutableMapOf<String, Set<String>>()
        val tagsByBook = mutableMapOf<String, List<String>>()
        for (row in rows) {
            val bookId = row[0]!!.removePrefix("book:")
            val key = row[1]!!
            val value = row[2]!!
            when (key) {
                "is_favorite" -> favByBook[bookId] = value.toBooleanStrictOrNull() ?: false
                "collections" ->
                    colsByBook[bookId] =
                        if (value.isBlank()) {
                            emptySet()
                        } else {
                            value
                                .split("|")
                                .map { it.trim() }
                                .filter { it.isNotEmpty() }
                                .toSet()
                        }
                "tags" ->
                    tagsByBook[bookId] =
                        if (value.isBlank()) {
                            emptyList()
                        } else {
                            value
                                .split("|")
                                .map { it.trim() }
                                .filter { it.isNotEmpty() }
                                .distinct()
                        }
            }
        }
        return items.map { item ->
            val isFav = favByBook[item.id] ?: item.isFavorite
            val cols =
                colsByBook[item.id]
                    ?: BookItem.defaultCollectionsFor(item.id, item.title, item.author)
            val tags =
                tagsByBook[item.id]
                    ?: BookItem.defaultTagsFor(item.id, item.title, item.author)
            item.copy(
                isFavorite = isFav,
                collections = cols,
                tags = tags,
            )
        }
    }

    /**
     * Persists reading progress and updates `last_opened_at` for [progress].
     * Enforces P-SPOILER-05: `knowledge_horizon_ordinal` is monotonic and never regresses,
     * and `graph_view_ordinal` stays bounded within `0..knowledge_horizon_ordinal`.
     */
    fun updateReadingProgress(progress: ReadingProgressRecord): ReadingProgressRecord {
        ensureOpen()
        val existing = getReadingProgress(progress.bookId)
        val monotonicHorizon =
            maxOf(
                existing?.knowledgeHorizonOrdinal ?: 0,
                progress.knowledgeHorizonOrdinal,
            )
        val clampedViewOrdinal = progress.graphViewOrdinal.coerceIn(0, monotonicHorizon)
        val normalized =
            progress.copy(
                knowledgeHorizonOrdinal = monotonicHorizon,
                graphViewOrdinal = clampedViewOrdinal,
            )
        val locatorJson = LocatorJsonCodec.encode(normalized.readingLocator)

        val script =
            buildString {
                appendLine("BEGIN IMMEDIATE;")
                append("INSERT OR REPLACE INTO reading_progress(")
                append("book_id, reading_locator_json, progression, ")
                append("knowledge_horizon_ordinal, graph_view_ordinal, ")
                append("active_graph_pack_id, updated_at) VALUES (")
                append(sqlText(normalized.bookId)).append(", ")
                append(sqlText(locatorJson)).append(", ")
                append(normalized.progression).append(", ")
                append(normalized.knowledgeHorizonOrdinal).append(", ")
                append(normalized.graphViewOrdinal).append(", ")
                append(sqlNullableText(normalized.activeGraphPackId)).append(", ")
                append(normalized.updatedAt).appendLine(");")

                append("UPDATE library_items SET last_opened_at = MAX(last_opened_at, ")
                append(normalized.updatedAt)
                append(") WHERE book_id = ")
                append(sqlText(normalized.bookId))
                appendLine(";")
                appendLine("COMMIT;")
            }
        engine.executeScript(script)

        val progressPercent = (normalized.progression * 100.0).roundToInt().coerceIn(0, 100)
        logger.logReadingEvent(
            bookReference = normalized.bookId,
            chapterIndex = normalized.knowledgeHorizonOrdinal,
            progressPercent = progressPercent,
            extraAttributes =
                mapOf(
                    "event" to "db_progress_updated",
                    "horizon_ordinal" to normalized.knowledgeHorizonOrdinal,
                    "view_ordinal" to normalized.graphViewOrdinal,
                ),
        )
        return normalized
    }

    /**
     * Retrieves the persisted [ReadingProgressRecord] for [bookId], or `null` if not started.
     */
    fun getReadingProgress(bookId: String): ReadingProgressRecord? {
        ensureOpen()
        val rows =
            engine.queryHexEncodedRows(
                selectExpressions =
                    listOf(
                        "book_id",
                        "reading_locator_json",
                        "progression",
                        "knowledge_horizon_ordinal",
                        "graph_view_ordinal",
                        "active_graph_pack_id",
                        "updated_at",
                    ),
                fromAndWhereClause =
                    "FROM reading_progress WHERE book_id = ${sqlText(bookId)} LIMIT 1",
            )
        val row = rows.firstOrNull() ?: return null
        return ReadingProgressRecord(
            bookId = row[0]!!,
            readingLocator = LocatorJsonCodec.decode(row[1]!!),
            progression = row[2]!!.toDouble(),
            knowledgeHorizonOrdinal = row[3]!!.toInt(),
            graphViewOrdinal = row[4]!!.toInt(),
            activeGraphPackId = row[5],
            updatedAt = row[6]!!.toLong(),
        )
    }

    /**
     * Adds or updates a [BookmarkRecord].
     */
    fun addBookmark(bookmark: BookmarkRecord) {
        ensureOpen()
        val locatorJson = LocatorJsonCodec.encode(bookmark.locator)
        val script =
            buildString {
                appendLine("BEGIN IMMEDIATE;")
                append("INSERT OR REPLACE INTO bookmarks(")
                append("bookmark_id, book_id, locator_json, label, ")
                append("created_at, updated_at, version, is_tombstone) VALUES (")
                append(sqlText(bookmark.bookmarkId)).append(", ")
                append(sqlText(bookmark.bookId)).append(", ")
                append(sqlText(locatorJson)).append(", ")
                append(sqlNullableText(bookmark.label)).append(", ")
                append(bookmark.createdAt).append(", ")
                append(bookmark.updatedAt).append(", ")
                append(bookmark.version).append(", ")
                append(if (bookmark.isTombstone) 1 else 0).appendLine(");")
                appendLine("COMMIT;")
            }
        engine.executeScript(script)
        logger.info(
            message = "Bookmark saved",
            attributes =
                mapOf(
                    "event" to "db_bookmark_added",
                    "book_id" to bookmark.bookId,
                    "version" to bookmark.version,
                ),
        )
    }

    /**
     * Removes a bookmark by marking it as a tombstone and incrementing its version.
     * Returns `true` if an active bookmark was tombstoned.
     */
    fun removeBookmark(
        bookmarkId: String,
        updatedAt: Long = System.currentTimeMillis(),
    ): Boolean {
        ensureOpen()
        val existingRows =
            engine.queryHexEncodedRows(
                selectExpressions = listOf("book_id", "is_tombstone"),
                fromAndWhereClause =
                    "FROM bookmarks WHERE bookmark_id = ${sqlText(bookmarkId)} LIMIT 1",
            )
        val existing = existingRows.firstOrNull() ?: return false
        val bookId = existing[0]!!
        val alreadyTombstone = (existing[1]?.toIntOrNull() ?: 0) != 0
        if (alreadyTombstone) {
            return false
        }
        val script =
            buildString {
                appendLine("BEGIN IMMEDIATE;")
                append("UPDATE bookmarks SET is_tombstone = 1, updated_at = ")
                append(updatedAt)
                append(", version = version + 1 WHERE bookmark_id = ")
                append(sqlText(bookmarkId))
                appendLine(";")
                appendLine("COMMIT;")
            }
        engine.executeScript(script)
        logger.info(
            message = "Bookmark removed",
            attributes =
                mapOf(
                    "event" to "db_bookmark_removed",
                    "book_id" to bookId,
                ),
        )
        return true
    }

    /**
     * Lists bookmarks for [bookId], excluding tombstoned records unless
     * [includeTombstones] is true.
     */
    fun listBookmarks(
        bookId: String,
        includeTombstones: Boolean = false,
    ): List<BookmarkRecord> {
        ensureOpen()
        val tombstoneClause = if (includeTombstones) "" else "AND is_tombstone = 0"
        val rows =
            engine.queryHexEncodedRows(
                selectExpressions =
                    listOf(
                        "bookmark_id",
                        "book_id",
                        "locator_json",
                        "label",
                        "created_at",
                        "updated_at",
                        "version",
                        "is_tombstone",
                    ),
                fromAndWhereClause =
                    """
                    FROM bookmarks
                    WHERE book_id = ${sqlText(bookId)} $tombstoneClause
                    ORDER BY created_at DESC, bookmark_id ASC
                    """.trimIndent(),
            )
        return rows.map { row ->
            BookmarkRecord(
                bookmarkId = row[0]!!,
                bookId = row[1]!!,
                locator = LocatorJsonCodec.decode(row[2]!!),
                label = row[3],
                createdAt = row[4]!!.toLong(),
                updatedAt = row[5]!!.toLong(),
                version = row[6]!!.toInt(),
                isTombstone = row[7]!!.toInt() != 0,
            )
        }
    }

    /**
     * Saves a [ReaderDisplayConfig] for [scopeKey] (defaulting to [GLOBAL_SETTINGS_SCOPE]).
     */
    fun saveReaderSettings(
        config: ReaderDisplayConfig,
        scopeKey: String = GLOBAL_SETTINGS_SCOPE,
        updatedAt: Long = System.currentTimeMillis(),
    ) {
        ensureOpen()
        val entries = config.toMap()
        val script =
            buildString {
                appendLine("BEGIN IMMEDIATE;")
                for ((key, value) in entries) {
                    append("INSERT OR REPLACE INTO reader_settings(")
                    append("scope_key, setting_key, setting_value, updated_at) VALUES (")
                    append(sqlText(scopeKey)).append(", ")
                    append(sqlText(key)).append(", ")
                    append(sqlText(value)).append(", ")
                    append(updatedAt).appendLine(");")
                }
                appendLine("COMMIT;")
            }
        engine.executeScript(script)
        logger.info(
            message = "Reader display settings persisted",
            attributes =
                mapOf(
                    "event" to "db_settings_saved",
                    "setting_count" to entries.size,
                ),
        )
    }

    /**
     * Loads the persisted [ReaderDisplayConfig] for [scopeKey], falling back to defaults for any
     * unset keys.
     */
    fun loadReaderSettings(scopeKey: String = GLOBAL_SETTINGS_SCOPE): ReaderDisplayConfig {
        ensureOpen()
        val rows =
            engine.queryHexEncodedRows(
                selectExpressions = listOf("setting_key", "setting_value"),
                fromAndWhereClause =
                    "FROM reader_settings WHERE scope_key = ${sqlText(scopeKey)}",
            )
        if (rows.isEmpty()) {
            return ReaderDisplayConfig.DEFAULT
        }
        val map = rows.associate { row -> row[0]!! to row[1]!! }
        return ReaderDisplayConfig.fromMap(map)
    }

    /**
     * Inserts or updates a [UserOverlayRecord] in `user_overlays`.
     */
    fun upsertUserOverlay(overlay: UserOverlayRecord) {
        ensureOpen()
        val script =
            buildString {
                appendLine("BEGIN IMMEDIATE;")
                append("INSERT OR REPLACE INTO user_overlays(")
                append("overlay_id, book_id, object_type, object_id, device_id, ")
                append("created_at, created_at_reveal, kind, payload_json, ")
                append("visibility, is_tombstone) VALUES (")
                append(sqlText(overlay.overlayId)).append(", ")
                append(sqlText(overlay.bookId)).append(", ")
                append(sqlText(overlay.objectType.name)).append(", ")
                append(sqlText(overlay.objectId)).append(", ")
                append(sqlNullableText(overlay.deviceId)).append(", ")
                append(sqlText(overlay.createdAt)).append(", ")
                append(overlay.createdAtReveal).append(", ")
                append(sqlText(overlay.kind.name)).append(", ")
                append(sqlText(overlay.payloadJson)).append(", ")
                append(sqlText(overlay.visibility.name)).append(", ")
                append(if (overlay.isTombstone) 1 else 0).appendLine(");")
                appendLine("COMMIT;")
            }
        engine.executeScript(script)
        logger.info(
            message = "User overlay persisted",
            attributes =
                mapOf(
                    "event" to "db_overlay_upserted",
                    "book_id" to overlay.bookId,
                    "overlay_kind" to overlay.kind.name,
                    "created_at_reveal" to overlay.createdAtReveal,
                ),
        )
    }

    /**
     * Lists user overlays for [bookId] visible at [maxRevealOrdinal].
     * Overlays with [OverlayVisibility.ALWAYS] are always returned; overlays with
     * [OverlayVisibility.FROM_CREATION] are returned when `created_at_reveal <= maxRevealOrdinal`.
     */
    fun listUserOverlays(
        bookId: String,
        maxRevealOrdinal: Int = Int.MAX_VALUE,
        includeTombstones: Boolean = false,
    ): List<UserOverlayRecord> {
        ensureOpen()
        val tombstoneClause = if (includeTombstones) "" else "AND is_tombstone = 0"
        val rows =
            engine.queryHexEncodedRows(
                selectExpressions =
                    listOf(
                        "overlay_id",
                        "book_id",
                        "object_type",
                        "object_id",
                        "device_id",
                        "created_at",
                        "created_at_reveal",
                        "kind",
                        "payload_json",
                        "visibility",
                        "is_tombstone",
                    ),
                fromAndWhereClause =
                    """
                    FROM user_overlays
                    WHERE book_id = ${sqlText(bookId)}
                      AND (visibility = 'ALWAYS' OR created_at_reveal <= $maxRevealOrdinal)
                      $tombstoneClause
                    ORDER BY created_at_reveal ASC, overlay_id ASC
                    """.trimIndent(),
            )
        return rows.map { row ->
            UserOverlayRecord(
                overlayId = row[0]!!,
                bookId = row[1]!!,
                objectType = OverlayObjectType.valueOf(row[2]!!),
                objectId = row[3]!!,
                deviceId = row[4],
                createdAt = row[5]!!,
                createdAtReveal = row[6]!!.toInt(),
                kind = OverlayKind.valueOf(row[7]!!),
                payloadJson = row[8]!!,
                visibility = OverlayVisibility.valueOf(row[9]!!),
                isTombstone = row[10]!!.toInt() != 0,
            )
        }
    }

    /**
     * Enqueues a mutation record in `sync_outbox`.
     */
    fun enqueueSyncMutation(mutation: SyncOutboxRecord) {
        ensureOpen()
        val script =
            buildString {
                appendLine("BEGIN IMMEDIATE;")
                append("INSERT OR REPLACE INTO sync_outbox(")
                append("mutation_id, entity_table, entity_id, operation, ")
                append("payload_json, created_at, synced_at) VALUES (")
                append(sqlText(mutation.mutationId)).append(", ")
                append(sqlText(mutation.entityTable)).append(", ")
                append(sqlText(mutation.entityId)).append(", ")
                append(sqlText(mutation.operation)).append(", ")
                append(sqlText(mutation.payloadJson)).append(", ")
                append(mutation.createdAt).append(", ")
                append(mutation.syncedAt?.toString() ?: "NULL").appendLine(");")
                appendLine("COMMIT;")
            }
        engine.executeScript(script)
    }

    /**
     * Lists unsynced mutations from `sync_outbox` in chronological order.
     */
    fun listPendingSyncMutations(): List<SyncOutboxRecord> {
        ensureOpen()
        val clause =
            "FROM sync_outbox WHERE synced_at IS NULL " +
                "ORDER BY created_at ASC, mutation_id ASC"
        val rows =
            engine.queryHexEncodedRows(
                selectExpressions =
                    listOf(
                        "mutation_id",
                        "entity_table",
                        "entity_id",
                        "operation",
                        "payload_json",
                        "created_at",
                        "synced_at",
                    ),
                fromAndWhereClause = clause,
            )
        return rows.map { row ->
            SyncOutboxRecord(
                mutationId = row[0]!!,
                entityTable = row[1]!!,
                entityId = row[2]!!,
                operation = row[3]!!,
                payloadJson = row[4]!!,
                createdAt = row[5]!!.toLong(),
                syncedAt = row[6]?.toLongOrNull(),
            )
        }
    }

    /**
     * Executes a read-only projection query for migration verification and diagnostics.
     */
    fun queryColumns(
        selectExpressions: List<String>,
        fromAndWhereClause: String,
    ): List<List<String?>> {
        ensureOpen()
        return engine.queryHexEncodedRows(selectExpressions, fromAndWhereClause)
    }

    override fun close() {
        if (!isClosed) {
            isClosed = true
            engine.close()
        }
    }

    private fun ensureOpen() {
        check(!isClosed) { "LocalLibraryDatabase is already closed" }
    }

    companion object {
        const val DATABASE_FILE_NAME = "library.db"
        const val SCHEMA_VERSION_V1 = 1
        const val GLOBAL_SETTINGS_SCOPE = "global"

        private val BOOK_ASSET_COLUMNS =
            listOf(
                "asset_id",
                "edition_id",
                "format_code",
                "media_type",
                "sha256_checksum",
                "byte_size",
                "local_storage_path",
                "parser_version",
                "format_metadata_json",
                "imported_at",
            )

        private val LIBRARY_ITEM_COLUMNS =
            listOf(
                "book_id",
                "asset_id",
                "edition_id",
                "display_title",
                "display_author",
                "cover_path",
                "cover_placeholder_color",
                "total_chapters",
                "added_at",
                "last_opened_at",
                "is_archived",
            )

        private val JOINED_BOOK_ITEM_COLUMNS =
            listOf(
                "li.book_id",
                "li.display_title",
                "li.display_author",
                "li.cover_placeholder_color",
                "li.total_chapters",
                "li.last_opened_at",
                "rp.reading_locator_json",
                "rp.progression",
                "rp.knowledge_horizon_ordinal",
                "rp.updated_at",
                "li.cover_path",
            )

        val V1_SCHEMA_STATEMENTS: List<String> =
            listOf(
                """
                CREATE TABLE IF NOT EXISTS schema_migrations (
                    version INTEGER NOT NULL PRIMARY KEY,
                    applied_at TEXT NOT NULL,
                    description TEXT NOT NULL
                )
                """.trimIndent(),
                """
                CREATE TABLE IF NOT EXISTS works (
                    work_id TEXT NOT NULL PRIMARY KEY,
                    canonical_title TEXT NOT NULL,
                    primary_author TEXT NOT NULL,
                    language_code TEXT NOT NULL DEFAULT 'und',
                    created_at INTEGER NOT NULL
                )
                """.trimIndent(),
                """
                CREATE TABLE IF NOT EXISTS editions (
                    edition_id TEXT NOT NULL PRIMARY KEY,
                    work_id TEXT,
                    title TEXT NOT NULL,
                    author TEXT NOT NULL,
                    language_code TEXT NOT NULL DEFAULT 'und',
                    identifier TEXT,
                    format TEXT NOT NULL DEFAULT 'EPUB',
                    spine_count INTEGER NOT NULL DEFAULT 0,
                    created_at INTEGER NOT NULL,
                    FOREIGN KEY (work_id) REFERENCES works(work_id) ON DELETE SET NULL
                )
                """.trimIndent(),
                """
                CREATE TABLE IF NOT EXISTS book_assets (
                    asset_id TEXT NOT NULL PRIMARY KEY,
                    edition_id TEXT,
                    format_code TEXT NOT NULL,
                    media_type TEXT NOT NULL,
                    sha256_checksum TEXT NOT NULL UNIQUE,
                    byte_size INTEGER NOT NULL,
                    local_storage_path TEXT NOT NULL,
                    parser_version TEXT NOT NULL,
                    format_metadata_json TEXT NOT NULL DEFAULT '{}',
                    imported_at INTEGER NOT NULL,
                    FOREIGN KEY (edition_id) REFERENCES editions(edition_id) ON DELETE SET NULL
                )
                """.trimIndent(),
                "CREATE INDEX IF NOT EXISTS idx_book_assets_sha256 " +
                    "ON book_assets(sha256_checksum)",
                """
                CREATE TABLE IF NOT EXISTS library_items (
                    book_id TEXT NOT NULL PRIMARY KEY,
                    asset_id TEXT NOT NULL,
                    edition_id TEXT,
                    display_title TEXT NOT NULL,
                    display_author TEXT NOT NULL,
                    cover_path TEXT,
                    cover_placeholder_color INTEGER NOT NULL DEFAULT -14796150,
                    total_chapters INTEGER NOT NULL DEFAULT 0,
                    added_at INTEGER NOT NULL,
                    last_opened_at INTEGER NOT NULL DEFAULT 0,
                    is_archived INTEGER NOT NULL DEFAULT 0,
                    FOREIGN KEY (asset_id) REFERENCES book_assets(asset_id) ON DELETE CASCADE,
                    FOREIGN KEY (edition_id) REFERENCES editions(edition_id) ON DELETE SET NULL
                )
                """.trimIndent(),
                "CREATE INDEX IF NOT EXISTS idx_library_items_last_opened " +
                    "ON library_items(last_opened_at DESC)",
                """
                CREATE TABLE IF NOT EXISTS reading_progress (
                    book_id TEXT NOT NULL PRIMARY KEY,
                    reading_locator_json TEXT NOT NULL,
                    progression REAL NOT NULL DEFAULT 0.0,
                    knowledge_horizon_ordinal INTEGER NOT NULL DEFAULT 0,
                    graph_view_ordinal INTEGER NOT NULL DEFAULT 0,
                    active_graph_pack_id TEXT,
                    updated_at INTEGER NOT NULL,
                    FOREIGN KEY (book_id) REFERENCES library_items(book_id) ON DELETE CASCADE
                )
                """.trimIndent(),
                """
                CREATE TABLE IF NOT EXISTS bookmarks (
                    bookmark_id TEXT NOT NULL PRIMARY KEY,
                    book_id TEXT NOT NULL,
                    locator_json TEXT NOT NULL,
                    label TEXT,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    version INTEGER NOT NULL DEFAULT 1,
                    is_tombstone INTEGER NOT NULL DEFAULT 0,
                    FOREIGN KEY (book_id) REFERENCES library_items(book_id) ON DELETE CASCADE
                )
                """.trimIndent(),
                "CREATE INDEX IF NOT EXISTS idx_bookmarks_book_id " +
                    "ON bookmarks(book_id, is_tombstone, created_at DESC)",
                """
                CREATE TABLE IF NOT EXISTS user_overlays (
                    overlay_id TEXT NOT NULL PRIMARY KEY,
                    book_id TEXT NOT NULL,
                    object_type TEXT NOT NULL,
                    object_id TEXT NOT NULL,
                    device_id TEXT,
                    created_at TEXT NOT NULL,
                    created_at_reveal INTEGER NOT NULL DEFAULT 0,
                    kind TEXT NOT NULL,
                    payload_json TEXT NOT NULL,
                    visibility TEXT NOT NULL DEFAULT 'FROM_CREATION',
                    is_tombstone INTEGER NOT NULL DEFAULT 0,
                    FOREIGN KEY (book_id) REFERENCES library_items(book_id) ON DELETE CASCADE
                )
                """.trimIndent(),
                "CREATE INDEX IF NOT EXISTS idx_user_overlays_book_reveal " +
                    "ON user_overlays(book_id, created_at_reveal)",
                """
                CREATE TABLE IF NOT EXISTS reader_settings (
                    scope_key TEXT NOT NULL,
                    setting_key TEXT NOT NULL,
                    setting_value TEXT NOT NULL,
                    updated_at INTEGER NOT NULL,
                    PRIMARY KEY (scope_key, setting_key)
                )
                """.trimIndent(),
                """
                CREATE TABLE IF NOT EXISTS sync_outbox (
                    mutation_id TEXT NOT NULL PRIMARY KEY,
                    entity_table TEXT NOT NULL,
                    entity_id TEXT NOT NULL,
                    operation TEXT NOT NULL,
                    payload_json TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    synced_at INTEGER
                )
                """.trimIndent(),
            )

        val V1_MIGRATION =
            SchemaMigration(
                fromVersion = 0,
                toVersion = SCHEMA_VERSION_V1,
                description =
                    "Initial v1 local library, progress, bookmarks, overlays and settings schema",
                statements = V1_SCHEMA_STATEMENTS,
            )

        /**
         * Opens or initializes `library.db` at [dbFile], automatically applying schema v1 if new.
         */
        fun open(
            dbFile: File,
            logger: StructuredLogger = StructuredLogger.default,
        ): LocalLibraryDatabase {
            val engine = createEngine(dbFile)
            val db = LocalLibraryDatabase(dbFile = dbFile, engine = engine, logger = logger)
            if (db.getCurrentSchemaVersion() == 0) {
                db.migrateTo(SCHEMA_VERSION_V1)
            }
            logger.info(
                message = "Local library database ready",
                attributes =
                    mapOf(
                        "event" to "db_initialized",
                        "schema_version" to db.getCurrentSchemaVersion(),
                    ),
            )
            return db
        }

        private fun createEngine(dbFile: File): SqlEngine {
            dbFile.parentFile?.mkdirs()
            // On Android device runtime, try Android SQLiteDatabase first; in host JVM unit tests,
            // Android stubs throw RuntimeException("not mocked"), falling back to CliSqlEngine.
            val androidDb =
                runCatching {
                    SQLiteDatabase.openOrCreateDatabase(dbFile, null)
                }.getOrNull()
            return if (androidDb != null) {
                AndroidSqlEngine(androidDb)
            } else {
                CliSqlEngine(dbFile)
            }
        }

        internal fun sqlText(value: String): String = "'${value.replace("'", "''")}'"

        internal fun sqlNullableText(value: String?): String =
            if (value == null) "NULL" else sqlText(value)

        private fun parseBookAssetRow(row: List<String?>): BookAssetRecord =
            BookAssetRecord(
                assetId = row[0]!!,
                editionId = row[1],
                formatCode = row[2]!!,
                mediaType = row[3]!!,
                sha256Checksum = row[4]!!,
                byteSize = row[5]!!.toLong(),
                localStoragePath = row[6]!!,
                parserVersion = row[7]!!,
                formatMetadataJson = row[8]!!,
                importedAt = row[9]!!.toLong(),
            )

        private fun parseLibraryItemRow(row: List<String?>): LibraryItemRecord =
            LibraryItemRecord(
                bookId = row[0]!!,
                assetId = row[1]!!,
                editionId = row[2],
                displayTitle = row[3]!!,
                displayAuthor = row[4]!!,
                coverPath = row[5],
                coverPlaceholderColor = row[6]!!.toLong(),
                totalChapters = row[7]!!.toInt(),
                addedAt = row[8]!!.toLong(),
                lastOpenedAt = row[9]!!.toLong(),
                isArchived = row[10]!!.toInt() != 0,
            )

        private fun parseJoinedBookItemRow(row: List<String?>): BookItem {
            val bookId = row[0]!!
            val title = row[1]!!
            val author = row[2]!!
            val color = row[3]!!.toLong()
            val totalChapters = row[4]!!.toInt()
            val lastOpenedAt = row[5]!!.toLong()
            val locatorJson = row[6]
            val progression = row[7]?.toDoubleOrNull() ?: 0.0
            val horizonOrdinal = row[8]?.toIntOrNull() ?: 0
            val progressUpdatedAt = row[9]?.toLongOrNull() ?: 0L

            val locator =
                locatorJson?.let { runCatching { LocatorJsonCodec.decode(it) }.getOrNull() }
            val progressPercent = (progression * 100.0).roundToInt().coerceIn(0, 100)

            return BookItem(
                id = bookId,
                title = title,
                author = author,
                coverPlaceholderColor = color,
                totalChapters = totalChapters,
                lastReadChapterTitle = locator?.title,
                lastReadOrdinal = horizonOrdinal,
                progressPercent = progressPercent,
                lastReadEpochMs = maxOf(lastOpenedAt, progressUpdatedAt),
                coverPath = row.getOrNull(10),
            )
        }
    }
}
