package dev.shehran.rawcull.qa

import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import dev.shehran.rawcull.BuildConfig
import dev.shehran.rawcull.util.SafPermissions

/**
 * qa only (R8 D9 b): grants the shared-storage test folder `Download/RawCullQA` once. The runner
 * drives it with UiAutomator; if that fails, the tester opens "QA folder grant" and picks the folder.
 */
class QaGrantActivity : ComponentActivity() {

    private lateinit var status: TextView

    private val pick = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        status.text = when {
            uri == null -> "Cancelled. Open QA folder grant again and pick Download/RawCullQA."
            uri.toString() != BuildConfig.QA_KEPT_TREE -> "That's not Download/RawCullQA: $uri"
            SafPermissions.takePersistable(this, uri) -> "$GRANTED $uri"
            else -> "Android didn't keep the grant for $uri"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply {
            setPadding(PADDING, PADDING, PADDING, PADDING)
            contentDescription = "qa_grant_status"
        }
        setContentView(status)
        if (isGranted()) {
            status.text = "$GRANTED ${BuildConfig.QA_KEPT_TREE}"
        } else if (savedInstanceState == null) {
            status.text = "Pick Download/RawCullQA, then Use this folder and Allow."
            pick.launch(initialUri())
        }
    }

    private fun isGranted(): Boolean = contentResolver.persistedUriPermissions.any {
        it.uri.toString() == BuildConfig.QA_KEPT_TREE && it.isReadPermission && it.isWritePermission
    }

    private fun initialUri(): Uri {
        val tree = Uri.parse(BuildConfig.QA_KEPT_TREE)
        return DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
    }

    companion object {
        const val GRANTED = "Granted:"
        private const val PADDING = 48
    }
}
