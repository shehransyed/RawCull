package dev.shehran.rawcull.util

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import dev.shehran.rawcull.data.model.ColorLabel
import dev.shehran.rawcull.data.model.CullStatus
import dev.shehran.rawcull.data.model.RawPhotoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
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
     * Atomically writes or updates the .xmp sidecar directly alongside the photo file
     */
    suspend fun writeSidecar(
        context: Context,
        folderUriString: String,
        photo: RawPhotoItem
    ): String? = withContext(Dispatchers.IO) {
        val xmpName = "${photo.baseStem}.xmp"
        val xmpXml = XmpSidecarGenerator.generateXmp(photo)

        // Case 1: Local file scheme or sample folder
        if (folderUriString.startsWith("file://") || folderUriString.startsWith("/")) {
            val localPath = if (folderUriString.startsWith("file://")) Uri.parse(folderUriString).path else folderUriString
            if (localPath != null) {
                val dir = File(localPath)
                if (dir.exists()) {
                    val xmpFile = File(dir, xmpName)
                    runCatching {
                        FileOutputStream(xmpFile).use { out ->
                            out.write(xmpXml.toByteArray(Charsets.UTF_8))
                            out.flush()
                        }
                        return@withContext Uri.fromFile(xmpFile).toString()
                    }
                }
            }
        }

        // Case 2: Storage Access Framework (SAF) tree Uri
        runCatching {
            val treeUri = Uri.parse(folderUriString)
            val parentDoc = DocumentFile.fromTreeUri(context, treeUri) ?: return@runCatching null

            // Find existing .xmp or create a new one
            var xmpDoc = parentDoc.findFile(xmpName)
            if (xmpDoc == null) {
                xmpDoc = parentDoc.createFile("application/rdf+xml", xmpName)
            }

            if (xmpDoc != null) {
                context.contentResolver.openOutputStream(xmpDoc.uri, "wt")?.use { out ->
                    out.write(xmpXml.toByteArray(Charsets.UTF_8))
                    out.flush()
                }
                xmpDoc.uri.toString()
            } else null
        }.getOrNull()
    }
}
