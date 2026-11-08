// SPDX-License-Identifier: MPL-2.0
package app.mixu.client.importer

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns

/**
 * Android Storage Access Framework (SAF) document picker contract and URI import bridge.
 *
 * Operates strictly on local device content URIs without requiring `android.permission.INTERNET`.
 */
object SafBookPickerContract {
    /**
     * Creates an [Intent.ACTION_OPEN_DOCUMENT] configured for selecting openable EPUB publications.
     */
    fun createOpenDocumentIntent(
        allowedMimeTypes: Array<String> =
            arrayOf(
                PublicationFormatValidator.MIME_EPUB,
                PublicationFormatValidator.MIME_OCTET_STREAM,
            ),
    ): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = PublicationFormatValidator.MIME_EPUB
            putExtra(Intent.EXTRA_MIME_TYPES, allowedMimeTypes)
        }

    /**
     * Streams a selected SAF document [uri] through [importService] and returns the typed
     * [BookImportResult].
     */
    fun importFromUri(
        contentResolver: ContentResolver,
        uri: Uri,
        importService: BookImportService,
        onProgress: (BookImportProgress) -> Unit = {},
    ): BookImportResult {
        val declaredMime = runCatching { contentResolver.getType(uri) }.getOrNull()
        val (displayName, byteSize) = queryDocumentMetadata(contentResolver, uri)

        val stream =
            runCatching { contentResolver.openInputStream(uri) }.getOrNull()
                ?: return BookImportResult.Failed(
                    BookImportError.EmptyOrUnreadableFile("ContentResolver returned null stream"),
                )

        return stream.use { input ->
            importService.importStream(
                inputStream = input,
                fileName = displayName,
                declaredMimeType = declaredMime,
                totalBytes = byteSize,
                onProgress = onProgress,
            )
        }
    }

    private fun queryDocumentMetadata(
        contentResolver: ContentResolver,
        uri: Uri,
    ): Pair<String?, Long?> =
        runCatching {
            contentResolver
                .query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                        val name =
                            if (nameIdx >= 0 && !cursor.isNull(nameIdx)) {
                                cursor.getString(nameIdx)
                            } else {
                                null
                            }
                        val size =
                            if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) {
                                cursor.getLong(sizeIdx)
                            } else {
                                null
                            }
                        Pair(name, size)
                    } else {
                        Pair(null, null)
                    }
                } ?: Pair(null, null)
        }.getOrDefault(Pair(null, null))
}
