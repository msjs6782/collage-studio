package com.example.collagestudio.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.collagestudio.R
import com.example.collagestudio.model.EditorDoc
import com.example.collagestudio.model.Selection
import kotlinx.coroutines.launch

private data class Tab(val label: Int, val glyph: String)

private val tabs = listOf(
    Tab(R.string.tab_layouts, "▦"),
    Tab(R.string.tab_filters, "◐"),
    Tab(R.string.tab_stickers, "☺"),
    Tab(R.string.tab_text, "T"),
    Tab(R.string.tab_style, "◼"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(vm: EditorViewModel, doc: EditorDoc) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var tab by rememberSaveable { mutableIntStateOf(0) }

    val savedText = stringResource(R.string.saved)
    val shareText = stringResource(R.string.share)

    LaunchedEffect(vm.message) {
        vm.message?.let {
            snackbar.showSnackbar(it)
            vm.clearMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.editor)) },
                navigationIcon = {
                    IconButton(onClick = vm::close) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    if (vm.selection is Selection.Item) {
                        IconButton(onClick = vm::deleteSelected) {
                            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.delete))
                        }
                    }
                    TextButton(
                        enabled = !vm.busy,
                        onClick = {
                            vm.export { uri ->
                                scope.launch {
                                    val result = snackbar.showSnackbar(savedText, actionLabel = shareText, duration = SnackbarDuration.Long)
                                    if (result == SnackbarResult.ActionPerformed) share(context, uri)
                                }
                            }
                        },
                    ) { Text(stringResource(R.string.save)) }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Column {
                Surface(tonalElevation = 2.dp) {
                    when (tab) {
                        0 -> TemplatesPanel(vm, doc)
                        1 -> FiltersPanel(vm)
                        2 -> StickersPanel(vm)
                        3 -> TextPanel(vm)
                        else -> StylePanel(vm, doc)
                    }
                }
                NavigationBar {
                    tabs.forEachIndexed { i, t ->
                        NavigationBarItem(
                            selected = tab == i,
                            onClick = { tab = i },
                            icon = { Text(t.glyph, style = MaterialTheme.typography.titleMedium) },
                            label = { Text(stringResource(t.label)) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            CollageCanvas(vm, doc, Modifier.aspectRatio(doc.template.aspect))
            if (vm.busy) CircularProgressIndicator()
        }
    }
}

/** Live preview. Draws with the same CollageRenderer used for saving. */
@Composable
private fun CollageCanvas(vm: EditorViewModel, doc: EditorDoc, modifier: Modifier) {
    val photos = vm.photos
    val selection = vm.selection
    Canvas(
        modifier
            .pointerInput(Unit) {
                detectTapGestures { p -> vm.tap(p.x, p.y, size.width.toFloat(), size.height.toFloat()) }
            }
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, zoom, rotation ->
                    vm.transform(centroid.x, centroid.y, pan.x, pan.y, zoom, rotation, size.width.toFloat(), size.height.toFloat())
                }
            },
    ) {
        drawIntoCanvas { canvas ->
            vm.renderer.draw(canvas.nativeCanvas, size.width, size.height, doc, photos, selection)
        }
    }
}

private fun share(context: Context, uri: Uri) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "image/jpeg"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, null))
}
