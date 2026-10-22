package com.example.collagestudio.ui

import android.graphics.RectF
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.collagestudio.R
import com.example.collagestudio.model.Adjustments
import com.example.collagestudio.model.EditorDoc
import com.example.collagestudio.model.Overlay
import com.example.collagestudio.model.OverlayKind
import com.example.collagestudio.model.Template
import com.example.collagestudio.render.Fonts
import com.example.collagestudio.render.Masks
import com.example.collagestudio.render.cellRect
import kotlin.math.roundToInt

private val PanelPadding = 12.dp

private val Palette = listOf(
    0xFFFFFFFF, 0xFF000000, 0xFF8A8A8A, 0xFFE5486B, 0xFFFF7A95, 0xFFFFC8DD,
    0xFFF94144, 0xFFF3722C, 0xFFF7B32B, 0xFFFFE066, 0xFF90BE6D, 0xFF2EC4B6,
    0xFF43AA8B, 0xFF3A86FF, 0xFF1D3557, 0xFF8338EC, 0xFFB5838D, 0xFFF4E1D2,
    0xFF6D4C41, 0xFF2D3142,
).map { it.toInt() }

// ---------------------------------------------------------------- shared bits

@Composable
fun ColorRow(selected: Int, onPick: (Int) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        items(Palette) { c ->
            Box(
                Modifier
                    .size(30.dp)
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

/**
 * A slider with a label. Double-tapping the label (or the value) resets it to [default].
 * The value shows as ×100 when [showValue], or through [format] when given.
 */
@Composable
fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    showValue: Boolean = false,
    default: Float? = null,
    format: ((Float) -> String)? = null,
) {
    val reset = Modifier.pointerInput(default) {
        detectTapGestures(onDoubleTap = { default?.let(onChange) })
    }
    val changed = default != null && value != default
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label + if (changed) " •" else "",
            style = MaterialTheme.typography.labelMedium,
            color = if (changed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.width(80.dp).then(reset),
            maxLines = 1,
        )
        Slider(value = value, onValueChange = onChange, valueRange = range, modifier = Modifier.weight(1f))
        if (showValue || format != null) {
            Text(
                format?.invoke(value) ?: "${(value * 100).roundToInt()}",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.width(44.dp).padding(start = 6.dp).then(reset),
                maxLines = 1,
            )
        }
    }
}

/** Lets the user pick which photo a full-screen tool should open. */
@Composable
fun PhotoChooserPanel(vm: EditorViewModel, mode: PhotoMode) {
    Column(Modifier.padding(PanelPadding)) {
        Hint(stringResource(R.string.choose_photo))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(vm.photos.size) { i ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.clickable { vm.openPhotoTool(mode, i) },
                ) {
                    Image(
                        vm.photos[i].asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(72.dp).clip(RoundedCornerShape(10.dp)),
                    )
                    Text(stringResource(R.string.photo_n, i + 1), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun SubTabs(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(labels.size) { i ->
            FilterChip(selected = i == selected, onClick = { onSelect(i) }, label = { Text(labels[i]) })
        }
    }
}

@Composable
fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 4.dp),
    )
}

// ---------------------------------------------------------------- templates

@Composable
fun TemplatesPanel(vm: EditorViewModel, doc: EditorDoc) {
    val crops = vm.templates.filter { it.group == "crop" }
    val layouts = vm.templates.filter { it.group != "crop" }
    LazyRow(
        modifier = Modifier.height(124.dp),
        contentPadding = PaddingValues(PanelPadding),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item { SectionLabel(stringResource(R.string.section_crop)) }
        items(crops, key = { it.id }) { t -> TemplateItem(t, t.id == doc.template.id) { vm.setTemplate(t) } }
        item { VerticalDivider(Modifier.height(72.dp)) }
        item { SectionLabel(stringResource(R.string.section_layouts)) }
        items(layouts, key = { it.id }) { t -> TemplateItem(t, t.id == doc.template.id) { vm.setTemplate(t) } }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun TemplateItem(t: Template, selected: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable(onClick = onClick)) {
        TemplateThumb(t, selected)
        Spacer(Modifier.height(4.dp))
        Text(t.name, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

/** Draws a template's cells with the same geometry and mask paths the renderer uses. */
@Composable
private fun TemplateThumb(t: Template, selected: Boolean) {
    val color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
    val aspect = if (t.aspect > 0f) t.aspect else 0.8f
    val box = 60.dp
    val width = if (aspect >= 1f) box else box * aspect
    val height = if (aspect >= 1f) box / aspect else box
    Box(Modifier.size(box), contentAlignment = Alignment.Center) {
        Canvas(
            Modifier
                .width(width)
                .height(height)
                .border(if (selected) 2.dp else 1.dp, color, RoundedCornerShape(4.dp)),
        ) {
            val gap = size.width * 0.05f
            t.cells.forEach { cell ->
                val r = cellRect(cell, size.width, size.height, gap)
                drawPath(Masks.path(cell.mask, r, size.width * 0.03f).asComposePath(), color)
            }
        }
        if (t.aspect == 0f) Text("↺", color = MaterialTheme.colorScheme.surface, style = MaterialTheme.typography.titleLarge)
    }
}

// ---------------------------------------------------------------- adjust

private class AdjDef(
    val key: String,
    val label: Int,
    val range: ClosedFloatingPointRange<Float>,
    val get: (Adjustments) -> Float,
    val set: (Adjustments, Float) -> Adjustments,
)

private val AdjDefs = listOf(
    AdjDef("brightness", R.string.adj_brightness, -1f..1f, { it.brightness }, { a, v -> a.copy(brightness = v) }),
    AdjDef("contrast", R.string.adj_contrast, -1f..1f, { it.contrast }, { a, v -> a.copy(contrast = v) }),
    AdjDef("saturation", R.string.adj_saturation, -1f..1f, { it.saturation }, { a, v -> a.copy(saturation = v) }),
    AdjDef("warmth", R.string.adj_warmth, -1f..1f, { it.warmth }, { a, v -> a.copy(warmth = v) }),
    AdjDef("tint", R.string.adj_tint, -1f..1f, { it.tint }, { a, v -> a.copy(tint = v) }),
    AdjDef("highlights", R.string.adj_highlights, -1f..1f, { it.highlights }, { a, v -> a.copy(highlights = v) }),
    AdjDef("shadows", R.string.adj_shadows, -1f..1f, { it.shadows }, { a, v -> a.copy(shadows = v) }),
    AdjDef("fade", R.string.adj_fade, 0f..1f, { it.fade }, { a, v -> a.copy(fade = v) }),
    AdjDef("sharpen", R.string.adj_sharpen, 0f..1f, { it.sharpen }, { a, v -> a.copy(sharpen = v) }),
    AdjDef("vignette", R.string.adj_vignette, 0f..1f, { it.vignette }, { a, v -> a.copy(vignette = v) }),
    AdjDef("grain", R.string.adj_grain, 0f..1f, { it.grain }, { a, v -> a.copy(grain = v) }),
)

@Composable
fun AdjustPanel(vm: EditorViewModel, doc: EditorDoc) {
    var activeKey by rememberSaveable { mutableStateOf("brightness") }
    val active = AdjDefs.first { it.key == activeKey }
    val edit = vm.currentEdit
    val multi = doc.edits.size > 1
    Column(Modifier.padding(PanelPadding)) {
        if (multi && vm.targetPhoto == null) Hint(stringResource(R.string.editing_all))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            item { AssistChip(onClick = vm::rotate, label = { Text("↻ " + stringResource(R.string.rotate)) }) }
            item { AssistChip(onClick = vm::flipH, label = { Text("⇋ " + stringResource(R.string.flip_h)) }) }
            item { AssistChip(onClick = vm::flipV, label = { Text("⇵ " + stringResource(R.string.flip_v)) }) }
            item { AssistChip(onClick = vm::resetPhoto, label = { Text(stringResource(R.string.reset)) }) }
            if (multi && vm.targetPhoto != null) {
                item { AssistChip(onClick = vm::applyAdjustToAll, label = { Text(stringResource(R.string.apply_all)) }) }
            }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(AdjDefs, key = { it.key }) { d ->
                val changed = d.get(edit.adjust) != 0f
                FilterChip(
                    selected = d.key == activeKey,
                    onClick = { activeKey = d.key },
                    label = { Text(stringResource(d.label) + if (changed) " •" else "") },
                )
            }
        }
        LabeledSlider(
            label = stringResource(active.label),
            value = active.get(edit.adjust),
            range = active.range,
            onChange = { v -> vm.setAdjust(active.key) { active.set(it, v) } },
            showValue = true,
            default = 0f,
        )
        Hint(stringResource(R.string.slider_hint))
    }
}

// ---------------------------------------------------------------- filters

@Composable
fun FiltersPanel(vm: EditorViewModel, doc: EditorDoc) {
    val edit = vm.currentEdit
    val multi = doc.edits.size > 1
    Column(Modifier.padding(vertical = 8.dp)) {
        if (multi) {
            Row(Modifier.padding(horizontal = PanelPadding), verticalAlignment = Alignment.CenterVertically) {
                if (vm.targetPhoto == null) {
                    Hint(stringResource(R.string.editing_all))
                } else {
                    TextButton(onClick = vm::applyFilterToAll) { Text(stringResource(R.string.apply_all)) }
                }
            }
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = PanelPadding),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(vm.filters, key = { it.id }) { f ->
                val selected = f.id == edit.filterId
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
                            .border(if (selected) 3.dp else 0.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp)),
                    ) {
                        vm.filterThumbs[f.id]?.let {
                            Image(it.asImageBitmap(), contentDescription = f.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        }
                    }
                    Text(f.name, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (edit.filterId != "none") {
            Box(Modifier.padding(horizontal = PanelPadding)) {
                LabeledSlider(stringResource(R.string.intensity), edit.intensity, 0f..1f, vm::setIntensity, showValue = true, default = 1f)
            }
        }
    }
}

// ---------------------------------------------------------------- text

@Composable
fun TextPanel(vm: EditorViewModel) {
    val selectedText = vm.selectedOverlay?.kind as? OverlayKind.Text
    val style = selectedText?.style ?: vm.textStyle
    var draft by rememberSaveable { mutableStateOf("") }
    var sub by rememberSaveable { mutableIntStateOf(0) }

    Column(Modifier.padding(PanelPadding)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = selectedText?.text ?: draft,
                onValueChange = { if (selectedText != null) vm.setSelectedText(it) else draft = it },
                maxLines = 3,
                placeholder = { Text(stringResource(R.string.text_hint)) },
                modifier = Modifier.weight(1f),
            )
            if (selectedText == null) {
                Spacer(Modifier.width(8.dp))
                Button(
                    enabled = draft.isNotBlank(),
                    onClick = {
                        vm.addText(draft.trim())
                        draft = ""
                    },
                ) { Text(stringResource(R.string.add)) }
            }
        }
        Spacer(Modifier.height(8.dp))
        SubTabs(
            listOf(
                stringResource(R.string.text_font),
                stringResource(R.string.text_color),
                stringResource(R.string.text_outline),
                stringResource(R.string.text_background),
                stringResource(R.string.text_layout),
            ),
            sub,
        ) { sub = it }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.height(96.dp)) {
            when (sub) {
                0 -> LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(vm.fonts, key = { it.id }) { f ->
                        val family = remember(f.id) { FontFamily(androidx.compose.ui.text.font.Typeface(Fonts.get(f.id))) }
                        val selected = f.id == style.font
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .border(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
                                .clickable { vm.setTextStyle("font") { it.copy(font = f.id) } }
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                        ) { Text(f.name, fontFamily = family, fontSize = 20.sp) }
                    }
                }
                1 -> Column {
                    ColorRow(style.color) { c -> vm.setTextStyle("color") { it.copy(color = c) } }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.shadow), style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.width(12.dp))
                        Switch(checked = style.shadow, onCheckedChange = { on -> vm.setTextStyle("shadow") { it.copy(shadow = on) } })
                    }
                }
                2 -> Column {
                    LabeledSlider(stringResource(R.string.thickness), style.stroke, 0f..1f, { v -> vm.setTextStyle("stroke") { it.copy(stroke = v) } })
                    ColorRow(style.strokeColor) { c -> vm.setTextStyle("stroke-color") { it.copy(strokeColor = c, stroke = if (it.stroke == 0f) 0.4f else it.stroke) } }
                }
                3 -> Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.enabled), style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.width(12.dp))
                        Switch(checked = style.background, onCheckedChange = { on -> vm.setTextStyle("bg") { it.copy(background = on) } })
                        Spacer(Modifier.width(12.dp))
                        Box(Modifier.weight(1f)) {
                            LabeledSlider(stringResource(R.string.opacity), style.backgroundOpacity, 0.1f..1f, { v -> vm.setTextStyle("bg-opacity") { it.copy(backgroundOpacity = v, background = true) } })
                        }
                    }
                    ColorRow(style.backgroundColor) { c -> vm.setTextStyle("bg-color") { it.copy(backgroundColor = c, background = true) } }
                }
                else -> Column {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(R.string.align_start, R.string.align_center, R.string.align_end).forEachIndexed { i, label ->
                            FilterChip(selected = style.align == i, onClick = { vm.setTextStyle("align") { it.copy(align = i) } }, label = { Text(stringResource(label)) })
                        }
                    }
                    LabeledSlider(stringResource(R.string.spacing), style.letterSpacing, 0f..0.3f, { v -> vm.setTextStyle("spacing") { it.copy(letterSpacing = v) } })
                }
            }
        }
    }
}

// ---------------------------------------------------------------- stickers

private val EmojiCategories = listOf(
    R.string.cat_faces to "😀 😃 😄 😁 😆 😅 😂 🤣 😊 😇 🙂 😉 😍 🥰 😘 😋 😛 😜 🤪 😎 🤩 🥳 😏 😴 🤗 🤭 😱 🥺 😢 😭 😡 🤯",
    R.string.cat_hearts to "❤️ 🧡 💛 💚 💙 💜 🖤 🤍 🤎 💖 💗 💓 💞 💕 💘 💝 💟 ❣️ 💔 💋 🌹 💐 😻 🫶",
    R.string.cat_nature to "🌸 🌺 🌻 🌼 🌷 🍀 🌿 🌴 🌵 🍁 🍂 🌙 ⭐ 🌟 ✨ ☀️ 🌈 ☁️ ❄️ 🔥 🌊 🦋 🐝 🐞 🐱 🐶 🦄 🐼 🕊️ 🐬",
    R.string.cat_food to "🍓 🍒 🍑 🍉 🍋 🍍 🥑 🍕 🍔 🍟 🌮 🍣 🍩 🍪 🎂 🧁 🍰 🍫 🍦 🍭 ☕ 🧋 🥤 🍹",
    R.string.cat_party to "🎉 🎊 🎈 🎁 🎀 🥂 🍾 🎆 🎇 🏆 👑 💍 🎵 🎶 📸 🎤 🕌 🏮 🌙 ⭐ 🎓 🎮",
    R.string.cat_symbols to "✅ ❌ ⭕ ❗ ❓ 💯 ✨ 💫 ⚡ 💥 💢 💤 🔔 📍 ➡️ ⬅️ ⬆️ ⬇️ ♻️ ♾️ 🆕 🆒 🔝 📌",
).map { (label, list) -> label to list.split(" ") }

@Composable
fun StickersPanel(vm: EditorViewModel) {
    var cat by rememberSaveable { mutableIntStateOf(0) }
    var shapeColor by rememberSaveable { mutableIntStateOf(0xFFE5486B.toInt()) }
    val labels = listOf(stringResource(R.string.cat_shapes)) + EmojiCategories.map { stringResource(it.first) }

    Column(Modifier.padding(vertical = 8.dp)) {
        Box(Modifier.padding(horizontal = PanelPadding)) { SubTabs(labels, cat) { cat = it } }
        Spacer(Modifier.height(6.dp))
        if (cat == 0) {
            Box(Modifier.padding(horizontal = PanelPadding)) { ColorRow(shapeColor) { shapeColor = it } }
            LazyRow(
                modifier = Modifier.height(72.dp),
                contentPadding = PaddingValues(horizontal = PanelPadding),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items(Masks.stickerShapes) { shape ->
                    Canvas(
                        Modifier
                            .size(52.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { vm.addShape(shape, shapeColor) }
                            .padding(6.dp),
                    ) {
                        drawPath(Masks.path(shape, RectF(0f, 0f, size.width, size.height), 0f).asComposePath(), Color(shapeColor))
                    }
                }
            }
        } else {
            LazyHorizontalGrid(
                rows = GridCells.Fixed(2),
                modifier = Modifier.height(116.dp),
                contentPadding = PaddingValues(horizontal = PanelPadding),
            ) {
                items(EmojiCategories[cat - 1].second) { emoji ->
                    Box(
                        Modifier
                            .size(54.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { vm.addSticker(emoji) },
                        contentAlignment = Alignment.Center,
                    ) { Text(emoji, fontSize = 30.sp) }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- draw

@Composable
fun DrawPanel(vm: EditorViewModel) {
    val brush = vm.brush
    Column(Modifier.padding(PanelPadding)) {
        Hint(stringResource(R.string.draw_hint))
        ColorRow(brush.color) { vm.brush = brush.copy(color = it) }
        LabeledSlider(stringResource(R.string.brush_size), brush.width, 0.004f..0.04f, { vm.brush = brush.copy(width = it) })
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.neon), style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.width(12.dp))
            Switch(checked = brush.neon, onCheckedChange = { vm.brush = brush.copy(neon = it) })
            Spacer(Modifier.weight(1f))
            TextButton(onClick = vm::undo, enabled = vm.canUndo) { Text(stringResource(R.string.undo)) }
            TextButton(onClick = vm::clearStrokes) { Text(stringResource(R.string.clear_drawing)) }
        }
    }
}

// ---------------------------------------------------------------- background

@Composable
fun StylePanel(vm: EditorViewModel, doc: EditorDoc) {
    Column(Modifier.padding(PanelPadding)) {
        ColorRow(selected = doc.style.background, onPick = vm::setBackground)
        LabeledSlider(stringResource(R.string.gap), doc.style.gap, 0f..0.06f, vm::setGap)
        LabeledSlider(stringResource(R.string.radius), doc.style.radius, 0f..0.08f, vm::setRadius)
    }
}

// ---------------------------------------------------------------- selected overlay

@Composable
fun OverlayBar(vm: EditorViewModel, overlay: Overlay, onEditText: () -> Unit) {
    Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
            if (overlay.kind is OverlayKind.Text) {
                item { TextButton(onClick = onEditText) { Text("✎ " + stringResource(R.string.edit_text)) } }
            }
            item { TextButton(onClick = vm::duplicateSelected) { Text("⧉ " + stringResource(R.string.duplicate)) } }
            item { TextButton(onClick = { vm.moveLayer(1) }) { Text("▲ " + stringResource(R.string.forward)) } }
            item { TextButton(onClick = { vm.moveLayer(-1) }) { Text("▼ " + stringResource(R.string.backward)) } }
            item { TextButton(onClick = vm::flipSelected) { Text("⇋ " + stringResource(R.string.flip)) } }
            item {
                TextButton(onClick = vm::deleteSelected) {
                    Text("✕ " + stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            }
        }
        LabeledSlider(stringResource(R.string.opacity), overlay.opacity, 0.1f..1f, vm::setOpacity)
        val shape = overlay.kind as? OverlayKind.Shape
        if (shape != null) ColorRow(shape.color, vm::setShapeColor)
    }
}

@Suppress("unused")
private fun Modifier.fullHeight() = this.fillMaxHeight()
