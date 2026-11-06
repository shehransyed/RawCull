package dev.shehran.rawcull.data.xmp

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.util.Log
import dev.shehran.rawcull.data.model.PhotoAsset
import java.io.FileNotFoundException
import java.io.IOException
import java.io.OutputStream

/** Sidecars on a Storage Access Framework source, addressed by stored document URIs (no findFile). */
class SafSidecarStore(private val resolver: ContentResolver) : SidecarStore {

    override fun create(asset: PhotoAsset): String = guarded {
        val parent = Uri.parse(asset.parentDocUri)
        val flags = queryInfo(parent)?.flags ?: throw FileNotFoundException("Folder not found: $parent")
        if (flags and Document.FLAG_DIR_SUPPORTS_CREATE == 0) {
            throw SidecarReadOnlyException("Folder is read-only: $parent")
        }

        val name = asset.sidecarFileName
        // octet-stream stops most providers appending an extension to the name.
        val created = DocumentsContract.createDocument(resolver, parent, MIME_TYPE, name)
            ?: throw IOException("Provider did not create $name")
        ensureNamed(created, name).toString()
    }

    /**
     * Providers may change the name: appending an extension, or de-duplicating to `X (1).xmp` when
     * a sidecar appeared since the last scan. No app would read such a file, so it can't stay.
     */
    private fun ensureNamed(created: Uri, name: String): Uri {
        if (queryInfo(created)?.displayName == name) return created
        val renamed = runCatching { DocumentsContract.renameDocument(resolver, created, name) ?: created }
            .onFailure { Log.e(TAG, "Could not rename new sidecar to $name", it) }
            .getOrDefault(created)
        if (queryInfo(renamed)?.displayName == name) return renamed

        runCatching { DocumentsContract.deleteDocument(resolver, renamed) }
        throw IOException("Provider would not name the sidecar $name")
    }

    override fun read(uri: String): SidecarContent? = guarded {
        val document = Uri.parse(uri)
        val info = try {
            queryInfo(document)
        } catch (e: IllegalArgumentException) {
            null // How providers report a document that no longer exists.
        } ?: return@guarded null
        val bytes = try {
            resolver.openInputStream(document)?.use { it.readBytes() }
        } catch (e: FileNotFoundException) {
            null
        } ?: return@guarded null
        SidecarContent(bytes, info.lastModified)
    }

    override fun write(uri: String, bytes: ByteArray): Long? = guarded {
        val document = Uri.parse(uri)
        val info = queryInfo(document) ?: throw FileNotFoundException("Sidecar not found: $uri")
        if (info.flags and Document.FLAG_SUPPORTS_WRITE == 0) {
            throw SidecarReadOnlyException("Sidecar is read-only: $uri")
        }

        val truncating = openTruncating(document)
        val stream = truncating ?: resolver.openOutputStream(document, "w")
        if (stream == null) throw IOException("No output stream for $uri")
        stream.use {
            it.write(bytes)
            // Without truncation the end of a longer old file would stay behind the new content.
            // Whitespace is legal after the root element, so padding keeps the XML well-formed.
            if (truncating == null) it.write(ByteArray((info.size - bytes.size).coerceAtLeast(0L).toInt()) { SPACE })
        }
        queryInfo(document)?.lastModified
    }

    /** Null if the provider doesn't support the "wt" mode. */
    private fun openTruncating(document: Uri): OutputStream? = try {
        resolver.openOutputStream(document, "wt")
    } catch (e: IllegalArgumentException) {
        null
    }

    override fun backup(uri: String): Unit = guarded {
        val document = Uri.parse(uri)
        val name = queryInfo(document)?.displayName ?: throw FileNotFoundException("Sidecar not found: $uri")
        DocumentsContract.renameDocument(resolver, document, "$name$BACKUP_SUFFIX")
    }

    private class DocumentInfo(val displayName: String?, val lastModified: Long?, val size: Long, val flags: Int)

    private fun queryInfo(uri: Uri): DocumentInfo? =
        resolver.query(uri, PROJECTION, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return null
            DocumentInfo(
                displayName = cursor.getString(0),
                lastModified = if (cursor.isNull(1)) null else cursor.getLong(1),
                size = if (cursor.isNull(2)) 0L else cursor.getLong(2),
                flags = cursor.getInt(3),
            )
        }

    /** A lost grant or a locked card shows up as one of these, wherever the operation failed. */
    private inline fun <T> guarded(block: () -> T): T = try {
        block()
    } catch (e: SecurityException) {
        throw SidecarReadOnlyException("No write access", e)
    } catch (e: IOException) {
        if (e !is SidecarReadOnlyException && e.message.orEmpty().contains(READ_ONLY_ERRNO)) {
            throw SidecarReadOnlyException("Read-only file system", e)
        }
        throw e
    }

    private companion object {
        const val TAG = "RawCull.Xmp"
        const val MIME_TYPE = "application/octet-stream"
        const val BACKUP_SUFFIX = ".bak"
        const val READ_ONLY_ERRNO = "EROFS"
        const val SPACE = ' '.code.toByte()
        val PROJECTION = arrayOf(
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_SIZE,
            Document.COLUMN_FLAGS,
        )
    }
}
