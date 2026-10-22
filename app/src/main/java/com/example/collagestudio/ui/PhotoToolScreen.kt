package com.example.collagestudio.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.collagestudio.R
import com.example.collagestudio.filter.PhotoProcessor
import com.example.collagestudio.model.CropRect
import com.example.collagestudio.model.EditorDoc
import com.example.collagestudio.model.FocusBlur
import com.example.collagestudio.model.LocalStroke
import com.example.collagestudio.model.PhotoEdit
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

private val StageBackground = Color(0xFF121212)

/** Where an image of [iw]×[ih] sits when fitted inside [cw]×[ch]. */
private fun fitRect(cw: Float, ch: Float, iw: Float, ih: Float): Rect {
    val s = min(cw / iw, ch / ih)
    val w = iw * s
    val h = ih * s
    return Rect(Offset((cw - w) / 2f, (ch - h) / 2f), Size(w, h))
}

private fun DrawScope.drawBitmapIn(img: androidx.compose.ui.graphics.ImageBitmap, frame: Rect) {
    drawImage(
        img,
        dstOffset = IntOffset(frame.left.roundToInt(), frame.top.roundToInt()),
        dstSize = IntSize(frame.width.roundToInt(), frame.height.roundToInt()),
        filterQuality = FilterQuality.Medium,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoToolScreen(vm: EditorViewModel, state: PhotoToolState, doc: EditorDoc) {
    val index = state.index
    val edit = doc.edits.getOrNull(index) ?: return
    BackHandler { vm.cancelPhotoTool() }

    // Shared between the stage and its controls.
    var ratio by rememberSaveable { mutableStateOf<Float?>(null) }
    var brushMode by rememberSaveable { mutableIntStateOf(LocalStroke.DODGE) }
    var brushSize by rememberSaveable { mutableFloatStateOf(0.07f) }
    var brushStrength by rememberSaveable { mutableFloatStateOf(0.6f) }

    val title = when (state.mode) {
        PhotoMode.Crop -> R.string.tool_crop
        PhotoMode.Focus -> R.string.tool_blur
        PhotoMode.Local -> R.string.tool_brush
    }

    Scaffold(
        containerColor = StageBackground,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(title)) },
                navigationIcon = { TextButton(onClick = vm::cancelPhotoTool) { Text(stringResource(R.string.cancel)) } },
                actions = {
                    IconButton(onClick = vm::undo, enabled = vm.canUndo) { Text("↶", style = MaterialTheme.typography.titleLarge) }
                    IconButton(onClick = vm::redo, enabled = vm.canRedo) { Text("↷", style = MaterialTheme.typography.titleLarge) }
                    TextButton(onClick = vm::donePhotoTool) { Text(stringResource(R.string.done), fontWeight = FontWeight.Bold) }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(Modifier.navigationBarsPadding().padding(12.dp)) {
                    when (state.mode) {
                        PhotoMode.Crop -> CropControls(vm, index, edit, ratio) { ratio = it }
                        PhotoMode.Focus -> FocusControls(vm, index, edit)
                        PhotoMode.Local -> LocalControls(
                            vm, edit, brushMode, { brushMode = it },
                            brushSize, { brushSize = it }, brushStrength, { brushStrength = it },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(20.dp),
        ) {
            when (state.mode) {
                PhotoMode.Crop -> CropStage(vm, index, edit, ratio)
                PhotoMode.Focus -> FocusStage(vm, index, edit)
                PhotoMode.Local -> LocalStage(vm, index, brushMode, brushSize, brushStrength)
            }
        }
    }
}

// ================================================================ crop

private enum class Handle { None, Move, L, T, R, B, TL, TR, BL, BR }

private const val MIN_CROP = 0.08f

/** Moves one handle by (du, dv) photo fractions. [rn] = locked width/height in fractions, or null. */
private fun dragCrop(c: CropRect, h: Handle, du: Float, dv: Float, rn: Float?): CropRect {
    var l = c.l
    var t = c.t
    var r = c.r
    var b = c.b
    if (h == Handle.Move) {
        val dx = du.coerceIn(-l, 1f - r)
        val dy = dv.coerceIn(-t, 1f - b)
        return CropRect(l + dx, t + dy, r + dx, b + dy)
    }
    val left = h == Handle.L || h == Handle.TL || h == Handle.BL
    val right = h == Handle.R || h == Handle.TR || h == Handle.BR
    val top = h == Handle.T || h == Handle.TL || h == Handle.TR
    val bottom = h == Handle.B || h == Handle.BL || h == Handle.BR
    if (left) l = (l + du).coerceIn(0f, r - MIN_CROP)
    if (right) r = (r + du).coerceIn(l + MIN_CROP, 1f)
    if (top) t = (t + dv).coerceIn(0f, b - MIN_CROP)
    if (bottom) b = (b + dv).coerceIn(t + MIN_CROP, 1f)
    if (rn != null) {
        // Height follows width; if that runs off the photo, shrink both.
        var wn = r - l
        var hn = wn / rn
        val maxH = if (top) b else 1f - t
        if (hn > maxH) {
            hn = maxH
            wn = hn * rn
            if (left) l = r - wn else r = l + wn
        }
        if (top) t = b - hn else b = t + hn
    }
    return CropRect(l, t, r, b)
}

/** The largest centred crop with width/height = [rn] (in fractions). */
private fun ratioRect(rn: Float): CropRect {
    var wn = 1f
    var hn = 1f / rn
    if (hn > 1f) {
        hn = 1f
        wn = rn
    }
    return CropRect(0.5f - wn / 2f, 0.5f - hn / 2f, 0.5f + wn / 2f, 0.5f + hn / 2f)
}

private fun pickHandle(p: Offset, cr: Rect, touch: Float, locked: Boolean): Handle {
    fun near(a: Offset) = (p - a).getDistance() < touch
    when {
        near(cr.topLeft) -> return Handle.TL
        near(cr.topRight) -> return Handle.TR
        near(cr.bottomLeft) -> return Handle.BL
        near(cr.bottomRight) -> return Handle.BR
    }
    if (!locked) {
        val inY = p.y in cr.top..cr.bottom
        val inX = p.x in cr.left..cr.right
        when {
            inY && abs(p.x - cr.left) < touch -> return Handle.L
            inY && abs(p.x - cr.right) < touch -> return Handle.R
            inX && abs(p.y - cr.top) < touch -> return Handle.T
            inX && abs(p.y - cr.bottom) < touch -> return Handle.B
        }
    }
    return if (cr.contains(p)) Handle.Move else Handle.None
}

@Composable
private fun CropStage(vm: EditorViewModel, index: Int, edit: PhotoEdit, ratio: Float?) {
    val key = edit.copy(straighten = 0f, crop = CropRect())
    val bitmap by produceState<Bitmap?>(null, key) { value = vm.uncroppedPreview(index, edit) }
    val bmp = bitmap
    if (bmp == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    val img = remember(bmp) { bmp.asImageBitmap() }
    val crop by rememberUpdatedState(edit.crop)
    val currentRatio by rememberUpdatedState(ratio)
    val touch = with(LocalDensity.current) { 28.dp.toPx() }
    val accent = MaterialTheme.colorScheme.primary

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val frame = fitRect(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat(), bmp.width.toFloat(), bmp.height.toFloat())
        var handle by remember { mutableStateOf(Handle.None) }
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(frame) {
                    detectDragGestures(
                        onDragStart = { p ->
                            val c = crop
                            val cr = Rect(frame.left + c.l * frame.width, frame.top + c.t * frame.height, frame.left + c.r * frame.width, frame.top + c.b * frame.height)
                            handle = pickHandle(p, cr, touch, currentRatio != null)
                        },
                        onDragEnd = { handle = Handle.None },
                        onDragCancel = { handle = Handle.None },
                    ) { change, drag ->
                        if (handle == Handle.None) return@detectDragGestures
                        change.consume()
                        val rn = currentRatio?.let { it * frame.height / frame.width }
                        vm.setCrop(dragCrop(crop, handle, drag.x / frame.width, drag.y / frame.height, rn))
                    }
                },
        ) {
            clipRect(frame.left, frame.top, frame.right, frame.bottom) {
                val s = PhotoProcessor.coverScale(frame.width, frame.height, edit.straighten)
                withTransform({
                    rotate(edit.straighten, frame.center)
                    scale(s, s, frame.center)
                }) { drawBitmapIn(img, frame) }
            }
            val c = edit.crop
            val cr = Rect(frame.left + c.l * frame.width, frame.top + c.t * frame.height, frame.left + c.r * frame.width, frame.top + c.b * frame.height)
            val dim = Color.Black.copy(alpha = 0.6f)
            drawRect(dim, frame.topLeft, Size(frame.width, cr.top - frame.top))
            drawRect(dim, Offset(frame.left, cr.bottom), Size(frame.width, frame.bottom - cr.bottom))
            drawRect(dim, Offset(frame.left, cr.top), Size(cr.left - frame.left, cr.height))
            drawRect(dim, Offset(cr.right, cr.top), Size(frame.right - cr.right, cr.height))
            // Rule-of-thirds grid.
            val grid = Color.White.copy(alpha = 0.45f)
            for (k in 1..2) {
                val x = cr.left + cr.width * k / 3f
                val y = cr.top + cr.height * k / 3f
                drawLine(grid, Offset(x, cr.top), Offset(x, cr.bottom), 1.dp.toPx())
                drawLine(grid, Offset(cr.left, y), Offset(cr.right, y), 1.dp.toPx())
            }
            drawRect(Color.White, cr.topLeft, cr.size, style = Stroke(1.5.dp.toPx()))
            // Corner handles.
            val len = 18.dp.toPx()
            val w = 4.dp.toPx()
            listOf(cr.topLeft to Offset(1f, 1f), cr.topRight to Offset(-1f, 1f), cr.bottomLeft to Offset(1f, -1f), cr.bottomRight to Offset(-1f, -1f)).forEach { (p, d) ->
                drawLine(accent, p, Offset(p.x + d.x * len, p.y), w, StrokeCap.Round)
                drawLine(accent, p, Offset(p.x, p.y + d.y * len), w, StrokeCap.Round)
            }
        }
    }
}

@Composable
private fun CropControls(vm: EditorViewModel, index: Int, edit: PhotoEdit, ratio: Float?, onRatio: (Float?) -> Unit) {
    val src = vm.previews.getOrNull(index)
    val turned = edit.rotation % 180 != 0
    val orientedAspect = if (src == null) 1f else if (turned) src.height.toFloat() / src.width else src.width.toFloat() / src.height
    val options = listOf(
        stringResource(R.string.ratio_free) to null,
        stringResource(R.string.ratio_original) to orientedAspect,
        "1:1" to 1f,
        "4:5" to 0.8f,
        "3:4" to 0.75f,
        "9:16" to 9f / 16f,
        "16:9" to 16f / 9f,
        "3:2" to 1.5f,
    )
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(options) { (label, r) ->
            FilterChip(
                selected = r == ratio,
                onClick = {
                    onRatio(r)
                    if (r != null) vm.setCrop(ratioRect(r / orientedAspect))
                },
                label = { Text(label) },
            )
        }
    }
    LabeledSlider(
        label = stringResource(R.string.straighten),
        value = edit.straighten,
        range = -45f..45f,
        onChange = vm::setStraighten,
        default = 0f,
        format = { "%.1f°".format(it) },
    )
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        item { AssistChip(onClick = vm::rotate, label = { Text("↻ " + stringResource(R.string.rotate)) }) }
        item { AssistChip(onClick = vm::flipH, label = { Text("⇋ " + stringResource(R.string.flip_h)) }) }
        item { AssistChip(onClick = vm::flipV, label = { Text("⇵ " + stringResource(R.string.flip_v)) }) }
        item {
            AssistChip(
                onClick = {
                    onRatio(null)
                    vm.setCrop(CropRect())
                    vm.setStraighten(0f)
                },
                label = { Text(stringResource(R.string.reset)) },
            )
        }
    }
}

// ================================================================ focus blur

/** The edited photo fitted in the stage, with [content] drawn on top in stage coordinates. */
@Composable
private fun PhotoStage(
    vm: EditorViewModel,
    index: Int,
    gestures: Modifier.(Rect) -> Modifier,
    overlay: DrawScope.(Rect) -> Unit,
) {
    val bmp = vm.photos.getOrNull(index) ?: return
    val img = remember(bmp) { bmp.asImageBitmap() }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val frame = fitRect(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat(), bmp.width.toFloat(), bmp.height.toFloat())
        Canvas(Modifier.fillMaxSize().gestures(frame)) {
            drawBitmapIn(img, frame)
            clipRect(frame.left, frame.top, frame.right, frame.bottom) { overlay(frame) }
        }
    }
}

@Composable
private fun FocusStage(vm: EditorViewModel, index: Int, edit: PhotoEdit) {
    val focus by rememberUpdatedState(edit.focus)
    PhotoStage(
        vm, index,
        gestures = { frame ->
            pointerInput(frame) {
                detectTapGestures { p ->
                    if (focus.mode == FocusBlur.OFF) return@detectTapGestures
                    val (sx, sy) = vm.finalToSource(index, ((p.x - frame.left) / frame.width).coerceIn(0f, 1f), ((p.y - frame.top) / frame.height).coerceIn(0f, 1f))
                    vm.setFocus("move") { it.copy(cx = sx, cy = sy) }
                }
            }.pointerInput(frame) {
                detectTransformGestures { _, pan, zoom, rotation ->
                    val f = focus
                    if (f.mode == FocusBlur.OFF) return@detectTransformGestures
                    val (u, v) = vm.sourceToFinal(index, f.cx, f.cy)
                    val (sx, sy) = vm.finalToSource(index, (u + pan.x / frame.width).coerceIn(0f, 1f), (v + pan.y / frame.height).coerceIn(0f, 1f))
                    var dx = f.dirX
                    var dy = f.dirY
                    if (rotation != 0f && f.mode == FocusBlur.LINEAR) {
                        val (fx, fy) = vm.sourceDirToFinal(index, f.dirX, f.dirY)
                        val a = Math.toRadians(rotation.toDouble())
                        val rx = (fx * cos(a) - fy * sin(a)).toFloat()
                        val ry = (fx * sin(a) + fy * cos(a)).toFloat()
                        val back = vm.finalDirToSource(index, rx, ry)
                        dx = back.first
                        dy = back.second
                    }
                    vm.setFocus("move") { it.copy(cx = sx, cy = sy, size = (it.size * zoom).coerceIn(0.03f, 1.2f), dirX = dx, dirY = dy) }
                }
            }
        },
        overlay = { frame ->
            val f = edit.focus
            if (f.mode == FocusBlur.OFF) return@PhotoStage
            val (u, v) = vm.sourceToFinal(index, f.cx, f.cy)
            val c = Offset(frame.left + u * frame.width, frame.top + v * frame.height)
            val inner = vm.sourceLengthToFinal(index, f.size) * min(frame.width, frame.height)
            val outer = inner * (1.05f + f.feather * 1.6f)
            val solid = Stroke(2.dp.toPx())
            val dashed = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f)))
            val line = Color.White.copy(alpha = 0.9f)
            if (f.mode == FocusBlur.RADIAL) {
                drawCircle(line, inner, c, style = solid)
                drawCircle(line.copy(alpha = 0.6f), outer, c, style = dashed)
            } else {
                val (fx, fy) = vm.sourceDirToFinal(index, f.dirX, f.dirY)
                val d = Offset(fx, fy)
                val n = Offset(-fy, fx)
                val far = max(frame.width, frame.height) * 2f
                for (side in listOf(1f, -1f)) {
                    drawLine(line, c + n * (inner * side) - d * far, c + n * (inner * side) + d * far, solid.width)
                    val p = Path().apply {
                        val a = c + n * (outer * side) - d * far
                        val b = c + n * (outer * side) + d * far
                        moveTo(a.x, a.y)
                        lineTo(b.x, b.y)
                    }
                    drawPath(p, line.copy(alpha = 0.6f), style = dashed)
                }
            }
            drawCircle(Color.White, 6.dp.toPx(), c)
        },
    )
}

@Composable
private fun FocusControls(vm: EditorViewModel, index: Int, edit: PhotoEdit) {
    val f = edit.focus
    Hint(stringResource(R.string.focus_hint))
    Row6 {
        listOf(R.string.focus_off to FocusBlur.OFF, R.string.focus_radial to FocusBlur.RADIAL, R.string.focus_linear to FocusBlur.LINEAR).forEach { (label, mode) ->
            FilterChip(
                selected = f.mode == mode,
                onClick = {
                    vm.setFocus("mode") {
                        if (mode == FocusBlur.LINEAR && it.mode != FocusBlur.LINEAR) {
                            val (dx, dy) = vm.finalDirToSource(index, 1f, 0f)
                            it.copy(mode = mode, dirX = dx, dirY = dy)
                        } else {
                            it.copy(mode = mode)
                        }
                    }
                },
                label = { Text(stringResource(label)) },
            )
        }
    }
    if (f.mode != FocusBlur.OFF) {
        LabeledSlider(stringResource(R.string.blur_amount), f.amount, 0.1f..1f, { v -> vm.setFocus("amount") { it.copy(amount = v) } }, showValue = true, default = 0.6f)
        LabeledSlider(stringResource(R.string.feather), f.feather, 0f..1f, { v -> vm.setFocus("feather") { it.copy(feather = v) } }, showValue = true, default = 0.5f)
    }
}

@Composable
private fun Row6(content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { content() }
}

// ================================================================ local brush

private fun brushColor(mode: Int): Color = when (mode) {
    LocalStroke.DODGE -> Color(0xFFFFF59D)
    LocalStroke.BURN -> Color(0xFF5C6BC0)
    LocalStroke.BLUR -> Color(0xFF80DEEA)
    LocalStroke.PIXELATE -> Color(0xFFCE93D8)
    LocalStroke.SATURATE -> Color(0xFFFF8A65)
    else -> Color(0xFFB0BEC5)
}

@Composable
private fun LocalStage(vm: EditorViewModel, index: Int, mode: Int, size: Float, strength: Float) {
    val live = remember { mutableStateListOf<Offset>() }
    val currentMode by rememberUpdatedState(mode)
    val currentSize by rememberUpdatedState(size)
    val currentStrength by rememberUpdatedState(strength)
    PhotoStage(
        vm, index,
        gestures = { frame ->
            pointerInput(frame) {
                fun norm(p: Offset) = Offset(((p.x - frame.left) / frame.width).coerceIn(0f, 1f), ((p.y - frame.top) / frame.height).coerceIn(0f, 1f))
                detectDragGestures(
                    onDragStart = { p ->
                        live.clear()
                        live += norm(p)
                    },
                    onDragEnd = {
                        if (live.isNotEmpty()) {
                            val pts = ArrayList<Float>(live.size * 2)
                            live.forEach { o ->
                                val (sx, sy) = vm.finalToSource(index, o.x, o.y)
                                pts += sx
                                pts += sy
                            }
                            vm.addLocalStroke(LocalStroke(pts, vm.finalLengthToSource(index, currentSize), currentMode, currentStrength))
                        }
                        live.clear()
                    },
                    onDragCancel = { live.clear() },
                ) { change, _ ->
                    change.consume()
                    live += norm(change.position)
                }
            }
        },
        overlay = { frame ->
            if (live.isEmpty()) return@PhotoStage
            val path = Path()
            live.forEachIndexed { i, o ->
                val x = frame.left + o.x * frame.width
                val y = frame.top + o.y * frame.height
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            if (live.size == 1) path.lineTo(frame.left + live[0].x * frame.width + 0.1f, frame.top + live[0].y * frame.height)
            drawPath(
                path,
                brushColor(mode).copy(alpha = 0.45f),
                style = Stroke(size * min(frame.width, frame.height) * 2f, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        },
    )
}

@Composable
private fun LocalControls(
    vm: EditorViewModel,
    edit: PhotoEdit,
    mode: Int,
    onMode: (Int) -> Unit,
    size: Float,
    onSize: (Float) -> Unit,
    strength: Float,
    onStrength: (Float) -> Unit,
) {
    Hint(stringResource(R.string.brush_hint))
    val modes = listOf(
        R.string.brush_dodge to LocalStroke.DODGE,
        R.string.brush_burn to LocalStroke.BURN,
        R.string.brush_blur to LocalStroke.BLUR,
        R.string.brush_pixelate to LocalStroke.PIXELATE,
        R.string.brush_saturate to LocalStroke.SATURATE,
        R.string.brush_desaturate to LocalStroke.DESATURATE,
    )
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(modes) { (label, m) ->
            FilterChip(selected = m == mode, onClick = { onMode(m) }, label = { Text(stringResource(label)) })
        }
    }
    LabeledSlider(stringResource(R.string.brush_size), size, 0.02f..0.2f, onSize, showValue = true, default = 0.07f)
    LabeledSlider(stringResource(R.string.strength), strength, 0.1f..1f, onStrength, showValue = true, default = 0.6f)
    Row6 {
        TextButton(onClick = vm::undoLocalStroke, enabled = edit.local.isNotEmpty()) { Text("↶ " + stringResource(R.string.undo_stroke)) }
        TextButton(onClick = vm::clearLocal, enabled = edit.local.isNotEmpty()) { Text(stringResource(R.string.clear_all)) }
    }
}
