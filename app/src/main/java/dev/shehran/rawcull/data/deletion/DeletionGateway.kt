package dev.shehran.rawcull.data.deletion

import android.content.ContentUris
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import java.io.FileNotFoundException

/** What deleting photos needs from Android. */
interface DeletionGateway {
    val apiLevel: Int

    /** The item MediaStore's trash can take for this document, or null if there isn't one. */
    fun mediaUri(documentUri: String): String?

    /** A system dialog that moves [mediaUris] into or out of the trash; one confirmation for all. */
    fun trashRequest(mediaUris: List<String>, trashed: Boolean): IntentSender

    /** False once the file has moved to the trash or gone. */
    fun documentExists(documentUri: String): Boolean

    /** True if the document is gone afterwards, whoever removed it. */
    fun deleteDocument(documentUri: String): Boolean
}

class SafDeletionGateway(context: Context) : DeletionGateway {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver

    override val apiLevel: Int get() = Build.VERSION.SDK_INT

    /**
     * getMediaUri answers with a generic Files URI, which createTrashRequest refuses as "not a media
     * item", so the same id is looked up in the Images collection instead. That's only right for
     * files MediaStore treats as images, which goes by MIME type; an .xmp has no such item.
     */
    override fun mediaUri(documentUri: String): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        // Providers report "not indexed" in several ways, and all of them mean the same here.
        return try {
            val document = Uri.parse(documentUri)
            val isImage = resolver.getType(document)?.startsWith("image/") == true
            val file = MediaStore.getMediaUri(appContext, document)
            if (isImage && file != null) {
                val images = MediaStore.Images.Media.getContentUri(MediaStore.getVolumeName(file))
                ContentUris.withAppendedId(images, ContentUris.parseId(file)).toString()
            } else {
                null
            }
        } catch (e: RuntimeException) {
            Log.i(TAG, "No media item for $documentUri: ${e.message}")
            null
        }
    }

    override fun trashRequest(mediaUris: List<String>, trashed: Boolean): IntentSender {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) { "The trash arrived in Android 11" }
        return MediaStore.createTrashRequest(resolver, mediaUris.map(Uri::parse), trashed).intentSender
    }

    override fun documentExists(documentUri: String): Boolean = try {
        resolver.query(Uri.parse(documentUri), arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)
            ?.use { it.moveToFirst() } == true
    } catch (e: FileNotFoundException) {
        false
    } catch (e: RuntimeException) {
        false
    }

    override fun deleteDocument(documentUri: String): Boolean = try {
        DocumentsContract.deleteDocument(resolver, Uri.parse(documentUri))
    } catch (e: FileNotFoundException) {
        true
    } catch (e: RuntimeException) {
        Log.w(TAG, "Could not delete $documentUri", e)
        false
    }

    private companion object {
        const val TAG = "RawCull.Deletion"
    }
}
