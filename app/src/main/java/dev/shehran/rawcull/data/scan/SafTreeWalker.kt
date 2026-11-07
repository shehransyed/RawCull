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
    suspend fun walk(treeUri: Uri, onProgress: (Int, Int) -> Unit = { _, _ -> }): WalkResult =
        walkFrom(treeUri, rootDirectory(treeUri), onProgress)

    /**
     * Like [walk], but only [relativeDir] and what's under it. Entries keep their full relative
     * dirs. A folder that no longer exists gives an empty result; a lost volume throws, as in [walk].
     */
    suspend fun walkSubtree(
        treeUri: Uri,
        relativeDir: String,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): WalkResult {
        var directory = rootDirectory(treeUri)
        if (relativeDir.isNotEmpty()) {
            for (segment in relativeDir.split('/')) {
                directory = findChildDirectory(treeUri, directory, segment) ?: return WalkResult(emptyList(), 0)
            }
        }
        return walkFrom(treeUri, directory, onProgress)
    }

    private fun rootDirectory(treeUri: Uri): Directory {
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val rootUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId).toString()
        return Directory(rootId, rootUri, relativeDir = "", depth = 0)
    }

    /** One children query per path segment; nothing here reads the files themselves. */
    private fun findChildDirectory(treeUri: Uri, parent: Directory, name: String): Directory? {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parent.docId)
        val cursor = resolver.query(childrenUri, PROJECTION, null, null, null)
            ?: throw FileNotFoundException("Could not list ${parent.relativeDir.ifEmpty { "source root" }}")
        cursor.use {
            while (it.moveToNext()) {
                if (it.getString(COLUMN_NAME) != name || it.getString(COLUMN_MIME) != Document.MIME_TYPE_DIR) continue
                val docId = it.getString(COLUMN_ID) ?: continue
                val relativeDir = if (parent.relativeDir.isEmpty()) name else "${parent.relativeDir}/$name"
                val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId).toString()
                return Directory(docId, docUri, relativeDir, parent.depth + 1)
            }
        }
        return null
    }

    private suspend fun walkFrom(treeUri: Uri, start: Directory, onProgress: (Int, Int) -> Unit): WalkResult {
        val pending = ArrayDeque(listOf(start))
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
