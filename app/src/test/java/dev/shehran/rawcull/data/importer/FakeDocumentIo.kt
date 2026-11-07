package dev.shehran.rawcull.data.importer

import dev.shehran.rawcull.importer.CreatedDocument
import dev.shehran.rawcull.importer.DocumentInfo
import dev.shehran.rawcull.importer.DocumentIo
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale

/** An in-memory document tree with the failures a real provider can produce. */
class FakeDocumentIo : DocumentIo {
    private class Node(var name: String, val isDirectory: Boolean, val parent: String?) {
        var bytes = ByteArray(0)
        var lastModified = 0L
    }

    private val nodes = LinkedHashMap<String, Node>()
    private var nextId = 0
    var clock = 1_000L

    /** Sources that stop being readable, and whether the whole volume goes with them. */
    val unreadable = HashSet<String>()
    var sourceAvailable = true
    var destinationAvailable = true

    /** Corrupts what's written for a file name this many times, so verification has to catch it. */
    val corruptWrites = HashMap<String, Int>()

    /** Writes fail with this message once this many bytes have gone to the target. */
    var writeFailure: String? = null

    /** Names the provider de-duplicates, as `X (1).ext`, whatever the directory really holds. */
    val forceRename = HashSet<String>()

    /** Called with each chunk read, so a test can act mid-copy. */
    var onSourceRead: ((String) -> Unit)? = null

    val deleted = ArrayList<String>()
    val undeletable = HashSet<String>()
    var bytesFree: Long? = null

    fun root(tree: String): String {
        val uri = "$tree/root"
        nodes.getOrPut(uri) { Node("root", true, null) }
        return uri
    }

    fun addFile(parent: String, name: String, bytes: ByteArray): String {
        val uri = "doc://${nextId++}/$name"
        nodes[uri] = Node(name, false, parent).also {
            it.bytes = bytes
            it.lastModified = clock
        }
        return uri
    }

    fun addDir(parent: String, name: String): String {
        val uri = "doc://${nextId++}/$name"
        nodes[uri] = Node(name, true, parent)
        return uri
    }

    /** Every file under [dirUri], by path relative to it. */
    fun files(dirUri: String, prefix: String = ""): Map<String, ByteArray> =
        nodes.filterValues { it.parent == dirUri }.entries.flatMap { (uri, node) ->
            val path = prefix + node.name
            if (node.isDirectory) files(uri, "$path/").entries.map { it.toPair() } else listOf(path to node.bytes)
        }.toMap()

    fun exists(uri: String) = uri in nodes

    override fun rootUri(treeUri: String) = root(treeUri)

    override fun listChildren(dirUri: String): List<DocumentInfo> {
        if (dirUri !in nodes) throw FileNotFoundException(dirUri)
        return nodes.filterValues { it.parent == dirUri }.map { (uri, node) -> node.info(uri) }
    }

    private fun Node.info(uri: String) = DocumentInfo(uri, name, isDirectory, bytes.size.toLong(), lastModified)

    override fun createDirectory(parentUri: String, name: String): CreatedDocument {
        if (!destinationAvailable) throw IOException("Volume gone")
        return CreatedDocument(addDir(parentUri, name), name)
    }

    override fun createFile(parentUri: String, mimeType: String, name: String): CreatedDocument {
        if (!destinationAvailable) throw IOException("Volume gone")
        writeFailure?.takeIf { it.contains("create") }?.let { throw IOException(it) }
        val taken = name in forceRename || nodes.values.any {
            it.parent == parentUri && it.name.lowercase(Locale.ROOT) == name.lowercase(Locale.ROOT)
        }
        val actual = if (taken) "${name.substringBeforeLast('.')} (1).${name.substringAfterLast('.')}" else name
        return CreatedDocument(addFile(parentUri, actual, ByteArray(0)), actual)
    }

    override fun openInput(uri: String): InputStream {
        if (uri in unreadable) throw IOException("Bad read")
        val node = nodes[uri] ?: throw FileNotFoundException(uri)
        val source = ByteArrayInputStream(node.bytes)
        return object : InputStream() {
            override fun read(): Int = source.read()

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (uri in unreadable) throw IOException("Bad read")
                onSourceRead?.invoke(node.name)
                return source.read(b, off, minOf(len, READ_CHUNK))
            }
        }
    }

    override fun openOutput(uri: String): OutputStream {
        val node = nodes[uri] ?: throw FileNotFoundException(uri)
        val sink = ByteArrayOutputStream()
        return object : OutputStream() {
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

            override fun write(b: ByteArray, off: Int, len: Int) {
                writeFailure?.takeUnless { it.contains("create") }?.let { throw IOException(it) }
                sink.write(b, off, len)
                node.bytes = sink.toByteArray()
            }

            override fun close() {
                val remaining = corruptWrites[node.name] ?: 0
                if (remaining > 0 && node.bytes.isNotEmpty()) {
                    corruptWrites[node.name] = remaining - 1
                    node.bytes = node.bytes.copyOf().also { it[it.lastIndex] = (it.last() + 1).toByte() }
                }
                node.lastModified = ++clock
            }
        }
    }

    override fun info(uri: String): DocumentInfo? = nodes[uri]?.info(uri)

    override fun rename(uri: String, name: String): String? {
        val node = nodes[uri] ?: return null
        val taken = nodes.values.any { it !== node && it.parent == node.parent && it.name.lowercase(Locale.ROOT) == name.lowercase(Locale.ROOT) }
        if (taken || name in forceRename) return null
        node.name = name
        return uri
    }

    override fun delete(uri: String): Boolean {
        if (uri in undeletable) return false
        deleted += uri
        nodes.remove(uri)
        return true
    }

    override fun isAvailable(treeUri: String) = if (treeUri.contains("card")) sourceAvailable else destinationAvailable

    override fun availableBytes(treeUri: String) = bytesFree

    private companion object {
        const val READ_CHUNK = 4096
    }
}
