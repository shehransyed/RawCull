package dev.shehran.rawcull.data.importer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.provider.DocumentsContract
import androidx.annotation.RequiresApi
import java.util.Locale

/** A removable volume that's mounted right now. */
data class CardVolume(
    val uuid: String?,
    val description: String,
    val isUsb: Boolean,
    val freeBytes: Long?,
    val totalBytes: Long?,
)

/** Removable storage, as far as the platform tells apps about it. */
class CardVolumes(context: Context) {
    private val appContext = context.applicationContext
    private val storage: StorageManager? = appContext.getSystemService(StorageManager::class.java)

    /** Does I/O for the space figures: keep it off the main thread. */
    fun list(): List<CardVolume> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return emptyList()
        return removable().map { volume ->
            val description = volume.getDescription(appContext)
            val space = spaceOf(volume)
            CardVolume(
                uuid = volume.uuid,
                description = description,
                isUsb = description.lowercase(Locale.ROOT).contains("usb"),
                freeBytes = space?.first,
                totalBytes = space?.second,
            )
        }
    }

    /** The system picker, opened on the card's DCIM folder. Null below API 29 or if it's gone. */
    fun pickerIntent(uuid: String?): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val volume = removable().firstOrNull { it.uuid == uuid } ?: return null
        return volume.createOpenDocumentTreeIntent().apply {
            if (uuid != null) putExtra(DocumentsContract.EXTRA_INITIAL_URI, dcimUri(uuid))
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun removable(): List<StorageVolume> =
        storage?.storageVolumes.orEmpty().filter { it.isRemovable && it.state == Environment.MEDIA_MOUNTED }

    private fun spaceOf(volume: StorageVolume): Pair<Long, Long>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val directory = volume.directory ?: return null
        val total = directory.totalSpace
        return if (total > 0) directory.usableSpace to total else null
    }

    private fun dcimUri(uuid: String): Uri =
        DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_AUTHORITY, "$uuid:DCIM")

    private companion object {
        const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
    }
}
