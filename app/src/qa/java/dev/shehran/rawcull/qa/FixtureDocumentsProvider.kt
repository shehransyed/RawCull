package dev.shehran.rawcull.qa

import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException

/**
 * qa only (R8 D9 a): serves `filesDir/fixtures/<name>/` as SAF trees, so device tests run the app's
 * real DocumentsContract code without a picker. Document ids are paths under `filesDir`, starting
 * with `fixtures`. The app reaches it under its own authority, which needs no persistable grant.
 */
class FixtureDocumentsProvider : DocumentsProvider() {

    private val base: File get() = checkNotNull(context).filesDir

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: ROOT_PROJECTION).apply {
            newRow()
                .add(Root.COLUMN_ROOT_ID, ROOT_ID)
                .add(Root.COLUMN_DOCUMENT_ID, ROOT_ID)
                .add(Root.COLUMN_TITLE, "QA fixtures")
                .add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_IS_CHILD)
                .add(Root.COLUMN_ICON, android.R.drawable.ic_menu_gallery)
        }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: DOCUMENT_PROJECTION).apply { addRow(this, documentId, fileFor(documentId)) }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        val dir = fileFor(parentDocumentId)
        val cursor = MatrixCursor(projection ?: DOCUMENT_PROJECTION)
        dir.listFiles()?.sortedBy { it.name }?.forEach { addRow(cursor, "$parentDocumentId/${it.name}", it) }
        cursor.setNotificationUri(checkNotNull(context).contentResolver, childrenUri(parentDocumentId))
        return cursor
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        val file = fileFor(documentId)
        if (file.isDirectory) throw FileNotFoundException("Is a directory: $documentId")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
    }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val parent = fileFor(parentDocumentId)
        // Like real providers, a taken name gets " (n)"; the app has to notice and fix that itself.
        var name = displayName
        var n = 1
        while (File(parent, name).exists()) name = dedupe(displayName, n++)
        val file = File(parent, name)
        val made = if (mimeType == Document.MIME_TYPE_DIR) file.mkdir() else file.createNewFile()
        if (!made) throw FileNotFoundException("Could not create $displayName in $parentDocumentId")
        notifyChildren(parentDocumentId)
        return "$parentDocumentId/$name"
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        val file = fileFor(documentId)
        val target = File(file.parentFile, displayName)
        if (target.exists() || !file.renameTo(target)) throw FileNotFoundException("Could not rename $documentId to $displayName")
        val parentId = documentId.substringBeforeLast('/')
        notifyChildren(parentId)
        return "$parentId/$displayName"
    }

    override fun deleteDocument(documentId: String) {
        val file = fileFor(documentId)
        if (!file.deleteRecursively()) throw FileNotFoundException("Could not delete $documentId")
        notifyChildren(documentId.substringBeforeLast('/'))
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean =
        documentId.startsWith("$parentDocumentId/")

    override fun getDocumentType(documentId: String): String = mimeOf(fileFor(documentId))

    private fun fileFor(documentId: String): File {
        val segments = documentId.split('/')
        if (segments.first() != ROOT_ID || segments.any { it.isEmpty() || it == "." || it == ".." }) {
            throw FileNotFoundException("Not a fixture document: $documentId")
        }
        val file = File(base, documentId)
        if (!file.exists()) throw FileNotFoundException("Missing: $documentId")
        return file
    }

    private fun addRow(cursor: MatrixCursor, documentId: String, file: File) {
        val flags = if (file.isDirectory) {
            Document.FLAG_DIR_SUPPORTS_CREATE or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
        } else {
            Document.FLAG_SUPPORTS_WRITE or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
        }
        cursor.newRow()
            .add(Document.COLUMN_DOCUMENT_ID, documentId)
            .add(Document.COLUMN_DISPLAY_NAME, file.name)
            .add(Document.COLUMN_MIME_TYPE, mimeOf(file))
            .add(Document.COLUMN_SIZE, if (file.isDirectory) null else file.length())
            .add(Document.COLUMN_LAST_MODIFIED, file.lastModified())
            .add(Document.COLUMN_FLAGS, flags)
    }

    private fun childrenUri(parentId: String): Uri =
        DocumentsContract.buildChildDocumentsUri(authority(checkNotNull(context)), parentId)

    private fun notifyChildren(parentId: String) {
        checkNotNull(context).contentResolver.notifyChange(childrenUri(parentId), null)
    }

    companion object {
        const val ROOT_ID = "fixtures"

        private val ROOT_PROJECTION = arrayOf(
            Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_FLAGS, Root.COLUMN_ICON,
        )
        private val DOCUMENT_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS,
        )

        fun authority(context: Context): String = "${context.packageName}.fixtures"

        /** `filesDir/fixtures`, which holds one folder per fixture tree. */
        fun root(context: Context): File = File(context.filesDir, ROOT_ID)

        /** The folder of fixture tree [name]; creates it. */
        fun dir(context: Context, name: String): File = File(root(context), name).apply { mkdirs() }

        /** What OpenDocumentTree would return for the fixture tree [name]. */
        fun treeUri(context: Context, name: String): Uri =
            DocumentsContract.buildTreeDocumentUri(authority(context), "$ROOT_ID/$name")

        fun clear(context: Context) {
            root(context).deleteRecursively()
            root(context).mkdirs()
        }

        private fun dedupe(name: String, n: Int): String {
            val dot = name.lastIndexOf('.')
            return if (dot <= 0) "$name ($n)" else "${name.substring(0, dot)} ($n)${name.substring(dot)}"
        }

        private fun mimeOf(file: File): String = when {
            file.isDirectory -> Document.MIME_TYPE_DIR
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase())
                ?: "application/octet-stream"
        }
    }
}
