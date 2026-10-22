package com.example.collagestudio.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
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
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import com.example.collagestudio.R
import com.example.collagestudio.model.EditorDoc
import kotlinx.coroutines.launch

enum class Tool(val label: Int, val glyph: String) {
    Layouts(R.string.tool_layouts, "▦"),
    Adjust(R.string.tool_adjust, "☼"),
    Filters(R.string.tool_filters, "◐"),
    Text(R.string.tool_text, "T"),
    Stickers(R.string.tool_stickers, "☺"),
    Draw(R.string.tool_draw, "✎"),
    Style(R.string.tool_style, "◼"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(vm: EditorViewModel, doc: EditorDoc) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var tool by rememberSaveable { mutableStateOf(Tool.Layouts) }

    val savedText = stringResource(R.string.saved)
    val shareText = stringResource(R.string.share)

    LaunchedEffect(vm.message) {
        vm.message?.let {
            snackbar.showSnackbar(it)
            vm.clearMessage()
        }
    }
    LaunchedEffect(tool) {
        vm.drawMode = tool == Tool.Draw
        if (tool == Tool.Draw) vm.clearSelection()
    }
    LaunchedEffect(tool, vm.targetPhoto, doc.edits) {
        if (tool == Tool.Filters) vm.ensureFilterThumbs()
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
                    IconButton(onClick = vm::undo, enabled = vm.canUndo) {
                        Text("↶", style = MaterialTheme.typography.titleLarge)
                    }
                    IconButton(onClick = vm::redo, enabled = vm.canRedo) {
                        Text("↷", style = MaterialTheme.typography.titleLarge)
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
                    ) { Text(stringResource(R.string.save), fontWeight = FontWeight.Bold) }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(Modifier.navigationBarsPadding()) {
                    val overlay = vm.selectedOverlay
                    if (overlay != null && !vm.drawMode) {
                        OverlayBar(vm, overlay, onEditText = { tool = Tool.Text })
                        HorizontalDivider()
                    }
                    when (tool) {
                        Tool.Layouts -> TemplatesPanel(vm, doc)
                        Tool.Adjust -> AdjustPanel(vm, doc)
                        Tool.Filters -> FiltersPanel(vm, doc)
                        Tool.Text -> TextPanel(vm)
                        Tool.Stickers -> StickersPanel(vm)
                        Tool.Draw -> DrawPanel(vm)
                        Tool.Style -> StylePanel(vm, doc)
                    }
                    HorizontalDivider()
                    ToolBar(tool) { tool = it }
                }
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(12.dp),
            contentAlignment = Alignment.Center,
        ) {
            CollageCanvas(vm, doc, Modifier.aspectRatio(vm.canvasAspect(doc)))
            if (vm.busy) CircularProgressIndicator()
        }
    }
}

@Composable
private fun ToolBar(current: Tool, onSelect: (Tool) -> Unit) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 4.dp),
    ) {
        itemsIndexed(Tool.entries) { _, t ->
            Row(Modifier.width(76.dp)) {
                NavigationBarItem(
                    selected = t == current,
                    onClick = { onSelect(t) },
                    icon = { Text(t.glyph, style = MaterialTheme.typography.titleMedium) },
                    label = { Text(stringResource(t.label), maxLines = 1) },
                    colors = NavigationBarItemDefaults.colors(),
                )
            }
        }
    }
}

/** Live preview. Draws with the same CollageRenderer used for saving. */
@Composable
private fun CollageCanvas(vm: EditorViewModel, doc: EditorDoc, modifier: Modifier) {
    val photos = vm.photos
    val selection = vm.selection
    val vignettes = remember(doc.edits) { vm.vignettes(doc) }
    val gestures = if (vm.drawMode) {
        Modifier.pointerInput("draw") {
            detectDragGestures(
                onDragStart = { p -> vm.startStroke(p.x, p.y, size.width.toFloat(), size.height.toFloat()) },
                onDragEnd = { vm.endStroke() },
                onDragCancel = { vm.endStroke() },
            ) { change, _ ->
                change.consume()
                vm.extendStroke(change.position.x, change.position.y, size.width.toFloat(), size.height.toFloat())
            }
        }
    } else {
        Modifier
            .pointerInput("tap") {
                detectTapGestures { p -> vm.tap(p.x, p.y, size.width.toFloat(), size.height.toFloat()) }
            }
            .pointerInput("transform") {
                detectTransformGestures { centroid, pan, zoom, rotation ->
                    vm.transform(centroid.x, centroid.y, pan.x, pan.y, zoom, rotation, size.width.toFloat(), size.height.toFloat())
                }
            }
    }
    Canvas(modifier.then(gestures)) {
        val live = vm.liveStroke
        drawIntoCanvas { canvas ->
            vm.renderer.draw(canvas.nativeCanvas, size.width, size.height, doc, photos, vignettes, selection, live)
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
