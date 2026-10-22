package com.example.collagestudio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.collagestudio.ui.EditorScreen
import com.example.collagestudio.ui.EditorViewModel
import com.example.collagestudio.ui.HomeScreen
import com.example.collagestudio.ui.theme.AppTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppTheme {
                // The UI text is Arabic, so lay everything out right-to-left.
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    App()
                }
            }
        }
    }
}

@Composable
private fun App(vm: EditorViewModel = viewModel()) {
    val doc = vm.doc
    if (doc == null) {
        HomeScreen(vm)
    } else {
        BackHandler { vm.close() }
        EditorScreen(vm, doc)
    }
}
