package dev.shehran.rawcull.device

import android.graphics.Bitmap
import android.graphics.Color
import java.io.File
import java.security.MessageDigest

/** Synthetic edge cases and file helpers for the import suites (R8.5). Samples come from [Samples]. */
object ImportFixtures {

    /** Every file under [dir], as paths relative to it with `/`. */
    fun relativeFiles(dir: File): List<String> =
        dir.walkTopDown().filter { it.isFile }.map { it.relativeTo(dir).invariantSeparatorsPath }.sorted().toList()

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** A small JPEG without EXIF, dated 1970 so the importer must file it under Undated/. */
    fun undatedJpeg(target: File, shade: Int = Color.DKGRAY) {
        target.parentFile?.mkdirs()
        val bitmap = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(shade) }
        target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it) }
        bitmap.recycle()
        target.setLastModified(0L)
    }

    /** An Adobe-style sidecar (`<stem>.xmp`) with a rating and a foreign namespace. */
    fun sidecar(target: File, rating: Int) {
        target.parentFile?.mkdirs()
        target.writeText(
            """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
<rdf:Description rdf:about="" xmlns:xmp="http://ns.adobe.com/xap/1.0/" xmlns:qa="http://example.com/qa/1.0/"
 xmp:Rating="$rating" qa:Keep="yes"/></rdf:RDF></x:xmpmeta>""",
        )
    }
}
