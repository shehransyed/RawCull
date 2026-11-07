package dev.shehran.rawcull.importer

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** A document as a provider reports it. */
data class DocumentInfo(
    val uri: String,
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val lastModified: Long?,
)

/** A new document. Providers may change the name, so [displayName] must be checked. */
data class CreatedDocument(val uri: String, val displayName: String)

/** The storage calls an import makes. SAF lives behind this so the worker logic can be tested. */
interface DocumentIo {
    /** The document URI of a tree's top folder. */
    fun rootUri(treeUri: String): String

    @Throws(IOException::class)
    fun listChildren(dirUri: String): List<DocumentInfo>

    @Throws(IOException::class)
    fun createDirectory(parentUri: String, name: String): CreatedDocument

    @Throws(IOException::class)
    fun createFile(parentUri: String, mimeType: String, name: String): CreatedDocument

    @Throws(IOException::class)
    fun openInput(uri: String): InputStream

    /** The stream reaches the disk when closed. */
    @Throws(IOException::class)
    fun openOutput(uri: String): OutputStream

    /** Null if the document is gone. */
    fun info(uri: String): DocumentInfo?

    /** Returns the new URI, or null if the provider wouldn't rename it (a taken name, say). */
    fun rename(uri: String, name: String): String?

    /** True if the document is gone afterwards. */
    fun delete(uri: String): Boolean

    /** False when the volume is gone. */
    fun isAvailable(treeUri: String): Boolean

    /** Free space where the tree lives, or null if the provider doesn't say. */
    fun availableBytes(treeUri: String): Long?
}
