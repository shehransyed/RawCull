package dev.shehran.rawcull.data.importer

import android.content.Context
import android.net.Uri
import dev.shehran.rawcull.data.scan.AssetDraft
import dev.shehran.rawcull.data.scan.SafTreeWalker
import dev.shehran.rawcull.data.scan.WalkResult
import dev.shehran.rawcull.importer.ImportSourceReader
import dev.shehran.rawcull.importer.Sha256HeadHash
import dev.shehran.rawcull.importer.SourceMetadata
import dev.shehran.rawcull.util.RawMetadataParser
import dev.shehran.rawcull.util.SafPermissions
import java.io.IOException

/** Reads a card or folder through the Storage Access Framework. */
class SafImportSourceReader(context: Context) : ImportSourceReader {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val walker = SafTreeWalker(resolver)

    override suspend fun list(treeUri: String, onProgress: (Int, Int) -> Unit): WalkResult =
        walker.walk(Uri.parse(treeUri), onProgress)

    override fun metadata(draft: AssetDraft): SourceMetadata {
        val parsed = RawMetadataParser.parse(
            context = appContext,
            uri = Uri.parse(draft.primary.docUri),
            format = draft.format,
            companionUri = draft.companion?.docUri?.let(Uri::parse),
        )
        return SourceMetadata(parsed.dateTaken, ImportMetadataCodec.encode(parsed))
    }

    override fun headHash(uri: String): String? = try {
        resolver.openInputStream(Uri.parse(uri))?.use(Sha256HeadHash::of)
    } catch (e: IOException) {
        null
    } catch (e: SecurityException) {
        null
    }

    override fun isAvailable(treeUri: String): Boolean = SafPermissions.isAvailable(appContext, Uri.parse(treeUri))
}
