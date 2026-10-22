package com.example.collagestudio.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.collagestudio.R
import com.example.collagestudio.model.EditorDoc
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class Tool(val label: Int, val glyph: String, val photoMode: PhotoMode? = null) {
    Layouts(R.string.tool_layouts, "▦"),
    Crop(R.string.tool_crop, "⛶", PhotoMode.Crop),
    Adjust(R.string.tool_adjust, "☼"),
    Color(R.string.tool_color, "◑"),
    Filters(R.string.tool_filters, "◐"),
    Blur(R.string.tool_blur, "◎", PhotoMode.Focus),
    Brush(R.string.tool_brush, "✦", PhotoMode.Local),
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
        // Full-screen photo tools open straight away when it is clear which photo is meant.
        val mode = tool.photoMode
        val target = vm.targetPhoto
        if (mode != null && target != null) vm.openPhotoTool(mode, target)
    }
    LaunchedEffect(tool, vm.targetPhoto, doc.edits) {
        if (tool == Tool.Filters) vm.ensureFilterThumbs()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
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
                    CompareButton(vm)
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
                        Tool.Color -> ColorPanel(vm, doc)
                        Tool.Filters -> FiltersPanel(vm, doc)
                        Tool.Text -> TextPanel(vm)
                        Tool.Stickers -> StickersPanel(vm)
                        Tool.Draw -> DrawPanel(vm)
                        Tool.Style -> StylePanel(vm, doc)
                        Tool.Crop, Tool.Blur, Tool.Brush -> PhotoChooserPanel(vm, tool.photoMode!!)
                    }
                    HorizontalDivider()
                    ToolBar(tool) { picked ->
                        if (picked == tool) {
                            // Tapping the current photo tool again reopens it.
                            val mode = picked.photoMode
                            val target = vm.targetPhoto
                            if (mode != null && target != null) vm.openPhotoTool(mode, target)
                        }
                        tool = picked
                    }
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
            if (vm.compare) {
                Text(
                    stringResource(R.string.before),
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .clip(RoundedCornerShape(50))
                        .background(Color.Black.copy(alpha = 0.6f))
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }
            if (vm.busy) CircularProgressIndicator()
        }
    }
}

/** Shows the photos without colour edits while pressed. */
@Composable
private fun CompareButton(vm: EditorViewModel) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    vm.compare = true
                    tryAwaitRelease()
                    vm.compare = false
                })
            }
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text("◧ " + stringResource(R.string.before), style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun ToolBar(current: Tool, onSelect: (Tool) -> Unit) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 4.dp),
    ) {
        items(Tool.entries) { t ->
            Row(Modifier.width(72.dp)) {
                NavigationBarItem(
                    selected = t == current,
                    onClick = { onSelect(t) },
                    icon = { Text(t.glyph, style = MaterialTheme.typography.titleMedium) },
                    label = { Text(stringResource(t.label), maxLines = 1) },
                )
            }
        }
    }
}

/**
 * Live preview. Draws with the same CollageRenderer used for saving.
 * Gestures: tap selects; drag/pinch moves the selection, or zooms the view when nothing is
 * selected; double-tap resets the zoom; press and hold shows "before".
 */
@Composable
private fun CollageCanvas(vm: EditorViewModel, doc: EditorDoc, modifier: Modifier) {
    val photos = if (vm.compare) vm.beforePhotos() else vm.photos
    val selection = vm.selection
    val vignettes = remember(doc.edits, vm.compare) { if (vm.compare) FloatArray(doc.edits.size) else vm.vignettes(doc) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }

    val gestures = if (vm.drawMode) {
        Modifier.pointerInput("draw") {
            fun map(p: Offset): Offset {
                val c = Offset(size.width / 2f, size.height / 2f)
                return (p - c - pan) / zoom + c
            }
            detectDragGestures(
                onDragStart = { p -> map(p).let { vm.startStroke(it.x, it.y, size.width.toFloat(), size.height.toFloat()) } },
                onDragEnd = { vm.endStroke() },
                onDragCancel = { vm.endStroke() },
            ) { change, _ ->
                change.consume()
                map(change.position).let { vm.extendStroke(it.x, it.y, size.width.toFloat(), size.height.toFloat()) }
            }
        }
    } else {
        Modifier
            .pointerInput("tap") {
                fun map(p: Offset): Offset {
                    val c = Offset(size.width / 2f, size.height / 2f)
                    return (p - c - pan) / zoom + c
                }
                detectTapGestures(
                    onTap = { p -> map(p).let { vm.tap(it.x, it.y, size.width.toFloat(), size.height.toFloat()) } },
                    onDoubleTap = {
                        zoom = 1f
                        pan = Offset.Zero
                    },
                    onPress = {
                        coroutineScope {
                            val hold = launch {
                                delay(450)
                                vm.compare = true
                            }
                            tryAwaitRelease()
                            hold.cancel()
                            vm.compare = false
                        }
                    },
                )
            }
            .pointerInput("transform") {
                detectTransformGestures { _, panChange, zoomChange, rotation ->
                    vm.compare = false
                    val w = size.width.toFloat()
                    val h = size.height.toFloat()
                    val moved = vm.transform(panChange.x / zoom, panChange.y / zoom, zoomChange, rotation, w, h)
                    if (!moved) {
                        zoom = (zoom * zoomChange).coerceIn(1f, 5f)
                        val maxX = (zoom - 1f) * w / 2f
                        val maxY = (zoom - 1f) * h / 2f
                        pan = Offset((pan.x + panChange.x).coerceIn(-maxX, maxX), (pan.y + panChange.y).coerceIn(-maxY, maxY))
                    }
                }
            }
    }
    Box(modifier.clipToBounds().then(gestures)) {
        Canvas(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = zoom
                    scaleY = zoom
                    translationX = pan.x
                    translationY = pan.y
                },
        ) {
            val live = vm.liveStroke
            drawIntoCanvas { canvas ->
                vm.renderer.draw(canvas.nativeCanvas, size.width, size.height, doc, photos, vignettes, selection, live)
            }
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
