package dev.shehran.rawcull.util

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import dev.shehran.rawcull.data.model.ColorLabel
import dev.shehran.rawcull.data.model.CullStatus
import dev.shehran.rawcull.data.model.PhotoAsset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.InputStreamReader

data class ParsedXmpData(
    val rating: Int? = null,
    val cullStatus: CullStatus? = null,
    val colorLabel: ColorLabel? = null,
    val tags: List<String> = emptyList()
)

object XmpSidecarManager {

    /**
     * Reads and parses an existing .xmp sidecar XML from a Uri
     */
    suspend fun readSidecar(context: Context, xmpUri: Uri): ParsedXmpData = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openInputStream(xmpUri)?.use { stream ->
                val reader = BufferedReader(InputStreamReader(stream))
                val content = reader.readText()
                parseXmpString(content)
            }
        }.getOrNull() ?: ParsedXmpData()
    }

    fun parseXmpString(content: String): ParsedXmpData {
        var rating: Int? = null
        var cullStatus: CullStatus? = null
        var colorLabel: ColorLabel? = null
        val tags = mutableListOf<String>()

        // Rating: xmp:Rating="3" or <xmp:Rating>3</xmp:Rating>
        val ratingRegex = "xmp:Rating=[\"'](\\d+)[\"']|<xmp:Rating>(\\d+)</xmp:Rating>".toRegex()
        ratingRegex.find(content)?.let { match ->
            val v = (match.groups[1] ?: match.groups[2])?.value?.toIntOrNull()
            if (v != null) rating = v.coerceIn(0, 5)
        }

        // Pick: crs:Pick="1" / "-1" / "0" or xmpDM:pick="1"
        val pickRegex = "(?:crs:Pick|xmpDM:pick)=[\"'](-?\\d+)[\"']|<(?:crs:Pick|xmpDM:pick)>(-?\\d+)</(?:crs:Pick|xmpDM:pick)>".toRegex()
        pickRegex.find(content)?.let { match ->
            val v = (match.groups[1] ?: match.groups[2])?.value?.toIntOrNull()
            cullStatus = when (v) {
                1 -> CullStatus.PICK
                -1 -> CullStatus.REJECT
                0 -> CullStatus.UNFLAGGED
                else -> null
            }
        }

        // Color label: xmp:Label="Red"
        val labelRegex = "xmp:Label=[\"']([^\"']+)[\"']|<xmp:Label>([^<]+)</xmp:Label>".toRegex()
        labelRegex.find(content)?.let { match ->
            val l = (match.groups[1] ?: match.groups[2])?.value?.trim()?.lowercase()
            colorLabel = when (l) {
                "red" -> ColorLabel.RED
                "yellow" -> ColorLabel.YELLOW
                "green" -> ColorLabel.GREEN
                "blue" -> ColorLabel.BLUE
                "purple" -> ColorLabel.PURPLE
                else -> ColorLabel.NONE
            }
        }

        // Tags: <dc:subject><rdf:Bag><rdf:li>Tag</rdf:li>
        val tagRegex = "<rdf:li>([^<]+)</rdf:li>".toRegex()
        tagRegex.findAll(content).forEach { match ->
            val tag = match.groups[1]?.value?.trim()
            if (!tag.isNullOrBlank() && !tags.contains(tag)) {
                tags.add(tag)
            }
        }

        return ParsedXmpData(
            rating = rating,
            cullStatus = cullStatus,
            colorLabel = colorLabel,
            tags = tags
        )
    }

    /**
     * Writes the .xmp sidecar next to the photo and returns its URI, or null if that failed.
     * Demo assets live in a local folder; everything else goes through SAF using the stored
     * [PhotoAsset.xmpFileUri] / [PhotoAsset.parentDocUri], never findFile.
     */
    suspend fun writeSidecar(context: Context, photo: PhotoAsset): String? = withContext(Dispatchers.IO) {
        val bytes = XmpSidecarGenerator.generateXmp(photo).toByteArray(Charsets.UTF_8)
        if (photo.parentDocUri.startsWith("file://")) {
            writeLocal(photo, bytes)
        } else {
            writeExisting(context, photo.xmpFileUri, bytes) ?: createAndWrite(context, photo, bytes)
        }
    }

    private fun writeLocal(photo: PhotoAsset, bytes: ByteArray): String? = runCatching {
        val dir = File(requireNotNull(Uri.parse(photo.parentDocUri).path))
        val xmpFile = File(dir, photo.sidecarFileName)
        FileOutputStream(xmpFile).use { it.write(bytes) }
        Uri.fromFile(xmpFile).toString()
    }.getOrNull()

    private fun writeExisting(context: Context, xmpUri: String?, bytes: ByteArray): String? {
        if (xmpUri == null) return null
        return runCatching {
            writeBytes(context, Uri.parse(xmpUri), bytes)
            xmpUri
        }.getOrNull()
    }

    private fun createAndWrite(context: Context, photo: PhotoAsset, bytes: ByteArray): String? = runCatching {
        val resolver = context.contentResolver
        val name = photo.sidecarFileName
        // octet-stream stops providers appending an extension to the name.
        val created = DocumentsContract.createDocument(
            resolver, Uri.parse(photo.parentDocUri), "application/octet-stream", name
        ) ?: return null
        // A provider that de-duplicates would hand back "DSC001 (1).xmp", which no app would read.
        if (displayName(context, created) != name) {
            DocumentsContract.deleteDocument(resolver, created)
            return null
        }
        writeBytes(context, created, bytes)
        created.toString()
    }.getOrNull()

    private fun writeBytes(context: Context, uri: Uri, bytes: ByteArray) {
        val stream = context.contentResolver.openOutputStream(uri, "wt")
            ?: throw FileNotFoundException("No output stream for $uri")
        stream.use { it.write(bytes) }
    }

    private fun displayName(context: Context, uri: Uri): String? =
        context.contentResolver.query(
            uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
}
