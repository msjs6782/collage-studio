package com.example.collagestudio.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.collagestudio.R
import com.example.collagestudio.model.EditorDoc
import com.example.collagestudio.model.Template
import com.example.collagestudio.render.Masks
import com.example.collagestudio.render.cellRect

private val PanelHeight = 120.dp

private val Palette = listOf(
    0xFFFFFFFF, 0xFF000000, 0xFFE5486B, 0xFFF7B32B, 0xFF2EC4B6,
    0xFF3A86FF, 0xFF8338EC, 0xFFF4E1D2, 0xFF2D3142, 0xFFFFC8DD,
).map { it.toInt() }

private val Stickers = listOf(
    "❤️", "😍", "😂", "😎", "🥰", "⭐", "🔥", "🌸", "🎉", "👑",
    "🌙", "☀️", "🦋", "🍓", "🎈", "💫", "🌈", "😘", "👍", "🐱",
)

@Composable
fun TemplatesPanel(vm: EditorViewModel, doc: EditorDoc) {
    LazyRow(
        modifier = Modifier.height(PanelHeight),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(vm.templates, key = { it.id }) { t ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { vm.setTemplate(t) }) {
                TemplateThumb(t, selected = t.id == doc.template.id)
                Spacer(Modifier.height(4.dp))
                Text(t.name, style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
        }
    }
}

/** Draws a template's cells with the same geometry and mask paths the renderer uses. */
@Composable
private fun TemplateThumb(t: Template, selected: Boolean) {
    val color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
    val boxHeight = 64.dp
    Canvas(
        Modifier
            .width(boxHeight * t.aspect)
            .height(boxHeight)
            .border(if (selected) 2.dp else 1.dp, color, RoundedCornerShape(4.dp)),
    ) {
        val gap = size.width * 0.05f
        t.cells.forEach { cell ->
            val r = cellRect(cell, size.width, size.height, gap)
            drawPath(Masks.path(cell.mask, r, size.width * 0.03f).asComposePath(), color)
        }
    }
}

@Composable
fun FiltersPanel(vm: EditorViewModel) {
    Column(Modifier.height(PanelHeight + 44.dp)) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(vm.filters, key = { it.id }) { f ->
                val selected = f.id == vm.filterId
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .width(68.dp)
                        .clickable { vm.setFilter(f.id) },
                ) {
                    Box(
                        Modifier
                            .size(64.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .border(
                                if (selected) 3.dp else 0.dp,
                                MaterialTheme.colorScheme.primary,
                                RoundedCornerShape(10.dp),
                            ),
                    ) {
                        vm.filterThumbs[f.id]?.let {
                            Image(it.asImageBitmap(), contentDescription = f.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        }
                    }
                    Text(f.name, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (vm.filterId != "none") {
            // Apply on release only: re-filtering every photo on each slider tick would stutter.
            var local by remember(vm.filterId) { mutableFloatStateOf(vm.intensity) }
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.intensity), style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.width(12.dp))
                Slider(value = local, onValueChange = { local = it }, onValueChangeFinished = { vm.updateIntensity(local) })
            }
        }
    }
}

@Composable
fun StickersPanel(vm: EditorViewModel) {
    LazyRow(
        modifier = Modifier.height(PanelHeight),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(Stickers) { emoji ->
            Box(
                Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { vm.addSticker(emoji) },
                contentAlignment = Alignment.Center,
            ) { Text(emoji, fontSize = 32.sp) }
        }
    }
}

@Composable
fun TextPanel(vm: EditorViewModel) {
    var text by rememberSaveable { mutableStateOf("") }
    var color by rememberSaveable { mutableIntStateOf(0xFFFFFFFF.toInt()) }
    Column(Modifier.padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                placeholder = { Text(stringResource(R.string.text_hint)) },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Button(
                enabled = text.isNotBlank(),
                onClick = {
                    vm.addText(text.trim(), color)
                    text = ""
                },
            ) { Text(stringResource(R.string.add)) }
        }
        Spacer(Modifier.height(8.dp))
        ColorRow(selected = color) {
            color = it
            vm.setTextColor(it)
        }
    }
}

@Composable
fun StylePanel(vm: EditorViewModel, doc: EditorDoc) {
    Column(Modifier.padding(12.dp)) {
        ColorRow(selected = doc.style.background, onPick = vm::setBackground)
        LabeledSlider(stringResource(R.string.gap), doc.style.gap, 0f..0.06f, vm::setGap)
        LabeledSlider(stringResource(R.string.radius), doc.style.radius, 0f..0.08f, vm::setRadius)
    }
}

@Composable
private fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(64.dp))
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}

@Composable
private fun ColorRow(selected: Int, onPick: (Int) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        items(Palette) { c ->
            Box(
                Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(Color(c))
                    .border(
                        width = if (c == selected) 3.dp else 1.dp,
                        color = if (c == selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        shape = CircleShape,
                    )
                    .clickable { onPick(c) },
            )
        }
    }
}
