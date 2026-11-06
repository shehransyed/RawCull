package com.example

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.example.data.model.ColorLabel
import com.example.data.model.CullStatus
import com.example.ui.MainDamScreen
import com.example.ui.RawCullViewModel
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
    private val viewModel: RawCullViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .navigationBarsPadding(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainDamScreen(viewModel = viewModel)
                }
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_P -> {
                viewModel.togglePick()
                return true
            }
            KeyEvent.KEYCODE_X -> {
                viewModel.toggleReject()
                return true
            }
            KeyEvent.KEYCODE_U -> {
                viewModel.setCullStatus(CullStatus.UNFLAGGED)
                return true
            }
            KeyEvent.KEYCODE_1 -> {
                viewModel.setRating(1)
                return true
            }
            KeyEvent.KEYCODE_2 -> {
                viewModel.setRating(2)
                return true
            }
            KeyEvent.KEYCODE_3 -> {
                viewModel.setRating(3)
                return true
            }
            KeyEvent.KEYCODE_4 -> {
                viewModel.setRating(4)
                return true
            }
            KeyEvent.KEYCODE_5 -> {
                viewModel.setRating(5)
                return true
            }
            KeyEvent.KEYCODE_0 -> {
                viewModel.setRating(0)
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_NAVIGATE_PREVIOUS -> {
                viewModel.previousPhoto()
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_NAVIGATE_NEXT -> {
                viewModel.nextPhoto()
                return true
            }
            KeyEvent.KEYCODE_C -> {
                viewModel.toggleCompareMode()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }
}
