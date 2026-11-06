package dev.shehran.rawcull.data.xmp

import dev.shehran.rawcull.data.model.PhotoAsset
import java.io.IOException

/** A sidecar's bytes, with the modification time they were read at (null if the store has none). */
class SidecarContent(val bytes: ByteArray, val lastModified: Long?)

/** The source can't be written to: a locked card, or a tree without write access. */
class SidecarReadOnlyException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Reads and writes sidecars. Blocking, so call it on an I/O dispatcher. */
interface SidecarStore {
    /** Creates the sidecar for [asset], which has none yet, and returns its URI. */
    @Throws(IOException::class)
    fun create(asset: PhotoAsset): String

    /** The sidecar's content, or null if the document no longer exists. */
    @Throws(IOException::class)
    fun read(uri: String): SidecarContent?

    /** Replaces the sidecar's content and returns its new modification time. */
    @Throws(IOException::class)
    fun write(uri: String, bytes: ByteArray): Long?

    /** Moves the sidecar aside as `<name>.bak`, without overwriting an earlier backup. */
    @Throws(IOException::class)
    fun backup(uri: String)
}

/** The store for a source, chosen by its root's scheme. */
class SidecarStores(
    private val saf: SidecarStore,
    private val local: SidecarStore,
) {
    fun forRoot(rootUri: String): SidecarStore = if (rootUri.startsWith(DEMO_SCHEME)) local else saf

    private companion object {
        const val DEMO_SCHEME = "rawcull-demo:"
    }
}
