package dev.shehran.rawcull

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import dev.shehran.rawcull.ui.library.LibraryViewModel
import dev.shehran.rawcull.ui.shell.RawCullApp
import dev.shehran.rawcull.ui.theme.RawCullTheme

class MainActivity : ComponentActivity() {
    private val viewModel: LibraryViewModel by viewModels { LibraryViewModel.Factory }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RawCullTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    RawCullApp(libraryViewModel = viewModel)
                }
            }
        }
    }
}
