package com.example.collagestudio.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.collagestudio.R
import com.example.collagestudio.filter.ColorMath
import com.example.collagestudio.model.EditorDoc
import com.example.collagestudio.model.HSL_BANDS
import kotlin.math.abs

private val ColorPanelPadding = 12.dp

@Composable
fun ColorPanel(vm: EditorViewModel, doc: EditorDoc) {
    var sub by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.padding(ColorPanelPadding)) {
        if (doc.edits.size > 1) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (vm.targetPhoto == null) {
                    Text(
                        stringResource(R.string.editing_all),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    TextButton(onClick = vm::applyAdjustToAll) { Text(stringResource(R.string.apply_all)) }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(R.string.curves, R.string.hsl, R.string.split_tone).forEachIndexed { i, label ->
                FilterChip(selected = sub == i, onClick = { sub = i }, label = { Text(stringResource(label)) })
            }
        }
        Spacer(Modifier.size(6.dp))
        when (sub) {
            0 -> CurvesSection(vm)
            1 -> HslSection(vm)
            else -> SplitSection(vm)
        }
    }
}

// ---------------------------------------------------------------- curves

private val ChannelColors = listOf(Color(0xFFEEEEEE), Color(0xFFFF5252), Color(0xFF69F0AE), Color(0xFF448AFF))

@Composable
private fun CurvesSection(vm: EditorViewModel) {
    var channel by rememberSaveable { mutableIntStateOf(0) }
    val curves = vm.currentEdit.curves
    Row {
        CurveEditor(
            points = curves.channel(channel),
            color = ChannelColors[channel],
            onChange = { vm.setCurve(channel, it) },
        )
        Spacer(Modifier.width(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            listOf(R.string.channel_rgb, R.string.channel_red, R.string.channel_green, R.string.channel_blue).forEachIndexed { i, label ->
                FilterChip(
                    selected = channel == i,
                    onClick = { channel = i },
                    label = { Text(stringResource(label) + if (curves.channel(i).isNotEmpty()) " •" else "") },
                    leadingIcon = { Box(Modifier.size(10.dp).clip(CircleShape).background(ChannelColors[i])) },
                )
            }
            TextButton(onClick = vm::resetCurves) { Text(stringResource(R.string.reset)) }
        }
    }
    Text(
        stringResource(R.string.curve_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Points are flattened x, y pairs in 0..1 (y up). Empty = straight diagonal. */
@Composable
private fun CurveEditor(points: List<Float>, color: Color, onChange: (List<Float>) -> Unit) {
    val pts = if (points.size >= 4) points else listOf(0f, 0f, 1f, 1f)
    val current by rememberUpdatedState(pts)
    val change by rememberUpdatedState(onChange)
    var dragging by remember { mutableIntStateOf(-1) }
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val bg = MaterialTheme.colorScheme.surfaceVariant

    fun pairs(list: List<Float>) = (list.indices step 2).map { list[it] to list[it + 1] }
    fun flat(list: List<Pair<Float, Float>>) = list.flatMap { listOf(it.first, it.second) }

    Canvas(
        Modifier
            .size(168.dp)
            .clip(MaterialTheme.shapes.small)
            .background(bg)
            .border(1.dp, gridColor, MaterialTheme.shapes.small)
            // Curves are always drawn left-to-right (dark → light), whatever the layout direction.
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { p ->
                    val list = pairs(current)
                    val x = p.x / size.width
                    val y = 1f - p.y / size.height
                    val i = list.indices.minByOrNull { abs(list[it].first - x) + abs(list[it].second - y) } ?: return@detectTapGestures
                    if (i != 0 && i != list.lastIndex) change(flat(list.filterIndexed { k, _ -> k != i }))
                })
            }
            .pointerInput(Unit) {
                val touch = 24.dp.toPx()
                detectDragGestures(
                    onDragStart = { p ->
                        val list = pairs(current).toMutableList()
                        val near = list.indices.minByOrNull { i ->
                            val dx = list[i].first * size.width - p.x
                            val dy = (1f - list[i].second) * size.height - p.y
                            dx * dx + dy * dy
                        }
                        val nearDist = near?.let {
                            val dx = list[it].first * size.width - p.x
                            val dy = (1f - list[it].second) * size.height - p.y
                            dx * dx + dy * dy
                        } ?: Float.MAX_VALUE
                        if (near != null && nearDist < touch * touch) {
                            dragging = near
                        } else {
                            val x = (p.x / size.width).coerceIn(0.01f, 0.99f)
                            val y = (1f - p.y / size.height).coerceIn(0f, 1f)
                            val at = list.indexOfFirst { it.first > x }.let { if (it < 0) list.size else it }
                            list.add(at, x to y)
                            dragging = at
                            change(flat(list))
                        }
                    },
                    onDragEnd = { dragging = -1 },
                    onDragCancel = { dragging = -1 },
                ) { c, drag ->
                    c.consume()
                    val i = dragging
                    val list = pairs(current).toMutableList()
                    if (i !in list.indices) return@detectDragGestures
                    val lo = if (i == 0) 0f else list[i - 1].first + 0.02f
                    val hi = if (i == list.lastIndex) 1f else list[i + 1].first - 0.02f
                    val (x, y) = list[i]
                    list[i] = (x + drag.x / size.width).coerceIn(lo, hi) to (y - drag.y / size.height).coerceIn(0f, 1f)
                    change(flat(list))
                }
            },
    ) {
        for (k in 1..3) {
            val x = size.width * k / 4f
            val y = size.height * k / 4f
            drawLine(gridColor, Offset(x, 0f), Offset(x, size.height), 1f)
            drawLine(gridColor, Offset(0f, y), Offset(size.width, y), 1f)
        }
        drawLine(gridColor, Offset(0f, size.height), Offset(size.width, 0f), 1f)
        val table = ColorMath.userTable(pts)
        val path = Path()
        for (v in 0..255) {
            val x = v / 255f * size.width
            val y = (1f - table[v] / 255f) * size.height
            if (v == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(2.5.dp.toPx()))
        pairs(pts).forEach { (x, y) ->
            val c = Offset(x * size.width, (1f - y) * size.height)
            drawCircle(Color.Black.copy(alpha = 0.5f), 7.dp.toPx(), c)
            drawCircle(color, 5.dp.toPx(), c)
        }
    }
}

// ---------------------------------------------------------------- HSL

private val BandLabels = listOf(
    R.string.band_red, R.string.band_orange, R.string.band_yellow, R.string.band_green,
    R.string.band_aqua, R.string.band_blue, R.string.band_purple, R.string.band_magenta,
)

@Composable
private fun HslSection(vm: EditorViewModel) {
    var band by rememberSaveable { mutableIntStateOf(0) }
    val hsl = vm.currentEdit.hsl
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        items(HSL_BANDS) { b ->
            val selected = b == band
            val changed = (0..2).any { hsl[b * 3 + it] != 0f }
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { band = b }) {
                Box(
                    Modifier
                        .size(if (selected) 34.dp else 28.dp)
                        .clip(CircleShape)
                        .background(Color.hsv(ColorMath.bandHues[b], 0.85f, 0.95f))
                        .border(if (selected) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape),
                )
                Text(stringResource(BandLabels[b]) + if (changed) "•" else "", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
    LabeledSlider(stringResource(R.string.hue), hsl[band * 3], -1f..1f, { vm.setHsl(band, 0, it) }, showValue = true, default = 0f)
    LabeledSlider(stringResource(R.string.adj_saturation), hsl[band * 3 + 1], -1f..1f, { vm.setHsl(band, 1, it) }, showValue = true, default = 0f)
    LabeledSlider(stringResource(R.string.lightness), hsl[band * 3 + 2], -1f..1f, { vm.setHsl(band, 2, it) }, showValue = true, default = 0f)
    TextButton(onClick = vm::resetHsl) { Text(stringResource(R.string.reset)) }
}

// ---------------------------------------------------------------- split toning

@Composable
private fun SplitSection(vm: EditorViewModel) {
    val s = vm.currentEdit.split
    Column(Modifier.heightIn(max = 230.dp).verticalScroll(rememberScrollState())) {
        ToneRow(stringResource(R.string.shadows_tone), s.shadowHue, s.shadowAmount,
            onHue = { h -> vm.setSplit("sh") { it.copy(shadowHue = h) } },
            onAmount = { a -> vm.setSplit("sa") { it.copy(shadowAmount = a) } })
        ToneRow(stringResource(R.string.highlights_tone), s.highlightHue, s.highlightAmount,
            onHue = { h -> vm.setSplit("hh") { it.copy(highlightHue = h) } },
            onAmount = { a -> vm.setSplit("ha") { it.copy(highlightAmount = a) } })
        LabeledSlider(stringResource(R.string.balance), s.balance, -1f..1f, { b -> vm.setSplit("bal") { it.copy(balance = b) } }, showValue = true, default = 0f)
    }
}

@Composable
private fun ToneRow(title: String, hue: Float, amount: Float, onHue: (Float) -> Unit, onAmount: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(18.dp).clip(CircleShape).background(Color.hsv(hue.coerceIn(0f, 359.9f), 0.8f, 0.95f)))
        Spacer(Modifier.width(8.dp))
        Text(title, style = MaterialTheme.typography.labelLarge)
    }
    LabeledSlider(stringResource(R.string.hue), hue, 0f..359f, onHue, format = { "${it.toInt()}°" })
    LabeledSlider(stringResource(R.string.amount), amount, 0f..1f, onAmount, showValue = true, default = 0f)
}
