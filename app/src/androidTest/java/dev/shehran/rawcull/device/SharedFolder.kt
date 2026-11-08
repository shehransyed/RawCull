package dev.shehran.rawcull.device

import android.content.Context
import android.net.Uri
import dev.shehran.rawcull.BuildConfig
import org.junit.Assume.assumeTrue

/**
 * R8 D9 b: `Download/RawCullQA` on shared storage, behind the real ExternalStorageProvider. Granted
 * once (`SharedFolderGrantTest`); the grant survives reinstalls and `QaReset`. Tests only ever
 * write inside [PATH].
 */
object SharedFolder {
    const val PATH = "/sdcard/Download/RawCullQA"
    const val FALLBACK =
        "Shared folder not granted: on the tablet open \"QA folder grant\" (RawCull QA) and pick Download/RawCullQA."

    val treeUri: Uri get() = Uri.parse(BuildConfig.QA_KEPT_TREE)

    fun isGranted(context: Context): Boolean = context.contentResolver.persistedUriPermissions.any {
        it.uri == treeUri && it.isReadPermission && it.isWritePermission
    }

    /** Skips the calling test when the grant is missing. */
    fun assumeGranted(context: Context) = assumeTrue(FALLBACK, isGranted(context))

    fun ensureExists() {
        Shell.text("mkdir -p ${Shell.quote(PATH)}")
    }

    /** Empties the folder (never the folder above it). */
    fun clear() {
        Shell.text("mkdir -p ${Shell.quote(PATH)} && cd ${Shell.quote(PATH)} && rm -rf -- * .[!.]*")
    }

    /** Copies samples in with the shell user's `cp`; MediaStore sees them on its next scan. */
    fun copy(samples: List<Samples.Sample>, subdir: String = "") {
        val target = if (subdir.isEmpty()) PATH else "$PATH/$subdir"
        val script = buildString {
            appendLine("mkdir -p ${Shell.quote(target)}")
            samples.forEach { appendLine("cp ${Shell.quote("${Samples.dir}/${it.name}")} ${Shell.quote(target)}/") }
        }
        Shell.text(script)
    }
}
