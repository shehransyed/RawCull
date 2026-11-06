package dev.shehran.rawcull.data.xmp

import dev.shehran.rawcull.data.model.PhotoAsset
import java.io.File
import java.io.IOException
import java.net.URI

/** Sidecars in a local folder, which is where the demo shoot lives. */
class LocalSidecarStore : SidecarStore {

    override fun create(asset: PhotoAsset): String {
        val file = File(fileOf(asset.parentDocUri), asset.sidecarFileName)
        if (!file.exists() && !file.createNewFile()) throw IOException("Could not create $file")
        return file.toURI().toString()
    }

    override fun read(uri: String): SidecarContent? {
        val file = fileOf(uri)
        return if (file.exists()) SidecarContent(file.readBytes(), file.lastModified()) else null
    }

    override fun write(uri: String, bytes: ByteArray): Long? {
        val file = fileOf(uri)
        file.writeBytes(bytes)
        return file.lastModified()
    }

    override fun backup(uri: String) {
        val file = fileOf(uri)
        val backup = generateSequence(0) { it + 1 }
            .map { File(file.parentFile, if (it == 0) "${file.name}.bak" else "${file.name}.$it.bak") }
            .first { !it.exists() }
        if (!file.renameTo(backup)) throw IOException("Could not back up $file")
    }

    private fun fileOf(uri: String) = File(URI(uri))
}
