package dev.shehran.rawcull.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile

/** Persistable Storage Access Framework grants for picked trees. */
object SafPermissions {
    private const val TAG = "RawCull.Saf"
    private const val READ_WRITE =
        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

    /** Call on every OpenDocumentTree result; without this the grant dies with the process. */
    fun takePersistable(context: Context, uri: Uri): Boolean = try {
        context.contentResolver.takePersistableUriPermission(uri, READ_WRITE)
        true
    } catch (e: SecurityException) {
        Log.w(TAG, "Could not persist permission for $uri", e)
        false
    }

    fun release(context: Context, uri: Uri) {
        try {
            context.contentResolver.releasePersistableUriPermission(uri, READ_WRITE)
        } catch (e: SecurityException) {
            Log.w(TAG, "Could not release permission for $uri", e)
        }
    }

    fun persistedUris(context: Context): Set<String> =
        context.contentResolver.persistedUriPermissions.map { it.uri.toString() }.toSet()

    /** False when the volume is gone (card unplugged). Does I/O: keep it off the main thread. */
    fun isAvailable(context: Context, uri: Uri): Boolean = try {
        DocumentFile.fromTreeUri(context, uri)?.exists() == true
    } catch (e: RuntimeException) {
        Log.w(TAG, "Availability check failed for $uri", e)
        false
    }

    /** Does I/O: keep it off the main thread. */
    fun displayName(context: Context, uri: Uri): String {
        val name = try {
            DocumentFile.fromTreeUri(context, uri)?.name
        } catch (e: RuntimeException) {
            Log.w(TAG, "Display name lookup failed for $uri", e)
            null
        }
        return name?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast(':')?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringBefore(':')?.takeIf { it.isNotBlank() }
            ?: "Selected folder"
    }
}
