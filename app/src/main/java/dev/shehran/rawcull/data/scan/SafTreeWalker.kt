package dev.shehran.rawcull.data.scan

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.FileNotFoundException

data class WalkResult(
    val entries: List<ScannedEntry>,
    val dirsVisited: Int,
)

/**
 * Breadth-first walk of a picked tree, one cursor query per directory. DocumentFile.listFiles()
 * costs a query per file per property, which is far too slow on a real card.
 */
class SafTreeWalker(private val resolver: ContentResolver) {

    private data class Directory(
        val docId: String,
        val docUri: String,
        val relativeDir: String,
        val depth: Int,
    )

    /**
     * @param onProgress called after each directory with (dirsVisited, filesSeen).
     * @throws FileNotFoundException if a directory can't be listed, typically because the volume
     * went away. An incomplete walk must fail rather than look like a shorter listing, since the
     * merge step deletes whatever is missing from it.
     */
    suspend fun walk(treeUri: Uri, onProgress: (Int, Int) -> Unit = { _, _ -> }): WalkResult {
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val rootUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId).toString()
        val pending = ArrayDeque(listOf(Directory(rootId, rootUri, relativeDir = "", depth = 0)))
        val entries = ArrayList<ScannedEntry>()
        var dirsVisited = 0

        while (pending.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val directory = pending.removeFirst()
            listDirectory(treeUri, directory, entries, pending)
            dirsVisited++
            onProgress(dirsVisited, entries.size)
        }
        return WalkResult(entries, dirsVisited)
    }

    private fun listDirectory(
        treeUri: Uri,
        directory: Directory,
        entries: MutableList<ScannedEntry>,
        pending: ArrayDeque<Directory>,
    ) {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, directory.docId)
        val cursor = resolver.query(childrenUri, PROJECTION, null, null, null)
            ?: throw FileNotFoundException("Could not list ${directory.relativeDir.ifEmpty { "source root" }}")

        cursor.use {
            while (it.moveToNext()) {
                val docId = it.getString(COLUMN_ID) ?: continue
                val name = it.getString(COLUMN_NAME) ?: continue
                val mime = it.getString(COLUMN_MIME)
                val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId).toString()

                if (mime == Document.MIME_TYPE_DIR) {
                    val depth = directory.depth + 1
                    if (WalkPolicy.shouldDescend(name, depth)) {
                        val relativeDir = if (directory.relativeDir.isEmpty()) name else "${directory.relativeDir}/$name"
                        pending.addLast(Directory(docId, docUri, relativeDir, depth))
                    }
                } else {
                    entries += ScannedEntry(
                        docId = docId,
                        docUri = docUri,
                        parentDocId = directory.docId,
                        parentDocUri = directory.docUri,
                        relativeDir = directory.relativeDir,
                        name = name,
                        mime = mime,
                        size = if (it.isNull(COLUMN_SIZE)) 0L else it.getLong(COLUMN_SIZE),
                        lastModified = if (it.isNull(COLUMN_MODIFIED)) 0L else it.getLong(COLUMN_MODIFIED),
                    )
                }
            }
        }
    }

    private companion object {
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
    }
}
