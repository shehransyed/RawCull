package dev.shehran.rawcull.data.importer

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.util.Log
import dev.shehran.rawcull.importer.CreatedDocument
import dev.shehran.rawcull.importer.DocumentInfo
import dev.shehran.rawcull.importer.DocumentIo
import dev.shehran.rawcull.util.SafPermissions
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** [DocumentIo] on the Storage Access Framework, by document URI and cursor queries only. */
class SafDocumentIo(context: Context) : DocumentIo {
    private val appContext = context.applicationContext
    private val resolver: ContentResolver = appContext.contentResolver

    override fun rootUri(treeUri: String): String {
        val tree = Uri.parse(treeUri)
        return DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)).toString()
    }

    override fun listChildren(dirUri: String): List<DocumentInfo> {
        val dir = Uri.parse(dirUri)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(dir, DocumentsContract.getDocumentId(dir))
        val cursor = resolver.query(children, PROJECTION, null, null, null)
            ?: throw FileNotFoundException("Could not list $dirUri")
        return cursor.use {
            buildList {
                while (it.moveToNext()) {
                    val id = it.getString(COLUMN_ID) ?: continue
                    add(it.toInfo(DocumentsContract.buildDocumentUriUsingTree(dir, id).toString()))
                }
            }
        }
    }

    override fun createDirectory(parentUri: String, name: String): CreatedDocument =
        create(parentUri, Document.MIME_TYPE_DIR, name)

    override fun createFile(parentUri: String, mimeType: String, name: String): CreatedDocument =
        create(parentUri, mimeType, name)

    private fun create(parentUri: String, mimeType: String, name: String): CreatedDocument {
        val created = guarded { DocumentsContract.createDocument(resolver, Uri.parse(parentUri), mimeType, name) }
            ?: throw IOException("The provider did not create $name")
        val info = info(created.toString()) ?: throw IOException("The provider lost $name")
        return CreatedDocument(created.toString(), info.name)
    }

    override fun openInput(uri: String): InputStream = guarded {
        resolver.openInputStream(Uri.parse(uri)) ?: throw FileNotFoundException("No input stream for $uri")
    }

    override fun openOutput(uri: String): OutputStream {
        val stream = guarded {
            resolver.openOutputStream(Uri.parse(uri), "w") ?: throw FileNotFoundException("No output stream for $uri")
        }
        return SyncingOutputStream(stream)
    }

    /** Pushes the bytes to the disk on close, so a verified file survives pulling the drive. */
    private class SyncingOutputStream(stream: OutputStream) : FilterOutputStream(stream) {
        override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)

        override fun close() {
            try {
                out.flush()
                (out as? FileOutputStream)?.fd?.sync()
            } finally {
                out.close()
            }
        }
    }

    override fun info(uri: String): DocumentInfo? = try {
        resolver.query(Uri.parse(uri), PROJECTION, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.toInfo(uri) else null
        }
    } catch (e: IllegalArgumentException) {
        null // How providers report a document that no longer exists.
    } catch (e: SecurityException) {
        null
    }

    override fun rename(uri: String, name: String): String? = try {
        DocumentsContract.renameDocument(resolver, Uri.parse(uri), name)?.toString() ?: uri
    } catch (e: IOException) {
        Log.w(TAG, "Could not rename to $name", e)
        null
    } catch (e: RuntimeException) {
        Log.w(TAG, "Could not rename to $name", e)
        null
    }

    override fun delete(uri: String): Boolean = try {
        DocumentsContract.deleteDocument(resolver, Uri.parse(uri))
    } catch (e: IOException) {
        Log.w(TAG, "Could not delete $uri", e)
        false
    } catch (e: RuntimeException) {
        Log.w(TAG, "Could not delete $uri", e)
        info(uri) == null
    }

    override fun isAvailable(treeUri: String): Boolean = SafPermissions.isAvailable(appContext, Uri.parse(treeUri))

    override fun availableBytes(treeUri: String): Long? = try {
        val tree = Uri.parse(treeUri)
        val treeDocId = DocumentsContract.getTreeDocumentId(tree)
        val roots = DocumentsContract.buildRootsUri(tree.authority)
        resolver.query(roots, ROOT_PROJECTION, null, null, null)?.use { cursor ->
            var best: Pair<Int, Long?>? = null
            while (cursor.moveToNext()) {
                val rootDocId = cursor.getString(0) ?: continue
                val matches = treeDocId == rootDocId || treeDocId.startsWith(rootDocId)
                if (matches && rootDocId.length > (best?.first ?: -1)) {
                    best = rootDocId.length to if (cursor.isNull(1)) null else cursor.getLong(1)
                }
            }
            best?.second
        }
    } catch (e: RuntimeException) {
        Log.w(TAG, "Could not read free space for $treeUri", e)
        null
    }

    private fun Cursor.toInfo(uri: String) = DocumentInfo(
        uri = uri,
        name = getString(COLUMN_NAME).orEmpty(),
        isDirectory = getString(COLUMN_MIME) == Document.MIME_TYPE_DIR,
        sizeBytes = if (isNull(COLUMN_SIZE)) 0L else getLong(COLUMN_SIZE),
        lastModified = if (isNull(COLUMN_MODIFIED)) null else getLong(COLUMN_MODIFIED),
    )

    /** A lost volume reaches apps as several kinds of runtime exception; callers expect I/O ones. */
    private inline fun <T> guarded(block: () -> T): T = try {
        block()
    } catch (e: IllegalArgumentException) {
        throw IOException(e.message, e)
    } catch (e: IllegalStateException) {
        throw IOException(e.message, e)
    } catch (e: SecurityException) {
        throw IOException(e.message, e)
    }

    private companion object {
        const val TAG = "RawCull.Import"
        val PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
        )
        const val COLUMN_ID = 0
        const val COLUMN_NAME = 1
        const val COLUMN_MIME = 2
        const val COLUMN_SIZE = 3
        const val COLUMN_MODIFIED = 4
        val ROOT_PROJECTION = arrayOf(Root.COLUMN_DOCUMENT_ID, Root.COLUMN_AVAILABLE_BYTES)
    }
}
