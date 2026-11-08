package dev.shehran.rawcull

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import dev.shehran.rawcull.data.importer.ImportNotifications
import dev.shehran.rawcull.ui.components.LocalPreviewRepository
import dev.shehran.rawcull.ui.library.LocalCameraLocations
import dev.shehran.rawcull.ui.library.LibraryViewModel
import dev.shehran.rawcull.ui.shell.RawCullApp as RawCullScreen
import dev.shehran.rawcull.ui.shell.ShellViewModel
import dev.shehran.rawcull.ui.theme.RawCullTheme
import dev.shehran.rawcull.ui.components.LocalPreviewWarmup
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.shehran.rawcull.ui.components.LocalShowPreviewTiming

class MainActivity : ComponentActivity() {
    private val viewModel: LibraryViewModel by viewModels { LibraryViewModel.Factory }
    private val shellViewModel: ShellViewModel by viewModels { ShellViewModel.Factory }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        openRequestedJob(intent)
        setContent {
            RawCullTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val container = (application as RawCullApp).container
                    val showTiming by container.settingsRepository.showPreviewTiming.collectAsStateWithLifecycle(initialValue = false)
                    CompositionLocalProvider(
                        LocalPreviewRepository provides container.previewRepository,
                        LocalPreviewWarmup provides container.previewWarmup,
                        LocalCameraLocations provides container.cameraLocations,
                        LocalShowPreviewTiming provides (BuildConfig.DEBUG && showTiming),
                    ) {
                        RawCullScreen(libraryViewModel = viewModel, shellViewModel = shellViewModel)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openRequestedJob(intent)
    }

    /** From a notification. The extra goes once read, so rotating doesn't reopen the screen. */
    private fun openRequestedJob(intent: Intent?) {
        val jobId = intent?.getLongExtra(ImportNotifications.EXTRA_JOB_ID, ImportNotifications.NO_JOB)
            ?: ImportNotifications.NO_JOB
        if (jobId == ImportNotifications.NO_JOB) return
        intent?.removeExtra(ImportNotifications.EXTRA_JOB_ID)
        shellViewModel.pendingJobId.value = jobId
    }
}
