package com.example.collagestudio.model

/** One photo slot in a template. Coordinates are fractions (0..1) of the canvas. */
data class CellSpec(
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
    /** Optional clip shape: "circle", "heart" or "star". Null = rectangle. */
    val mask: String? = null,
)

/** A collage layout loaded from assets/templates.json. aspect = width / height. */
data class Template(
    val id: String,
    val name: String,
    val aspect: Float,
    val cells: List<CellSpec>,
)

/** A colour filter loaded from assets/filters.json. */
data class FilterSpec(
    val id: String,
    val name: String,
    /** Path of a Photoshop .acv tone-curve file inside assets, or null for no curve. */
    val acv: String?,
    val saturation: Float = 1f,
)

/** How the user has moved/zoomed the photo inside one cell. dx/dy are fractions of the cell size. */
data class CellTransform(
    val scale: Float = 1f,
    val dx: Float = 0f,
    val dy: Float = 0f,
)

/** Something drawn on top of the collage. cx/cy are fractions of the canvas; rotation in degrees. */
sealed interface Overlay {
    val id: Long
    val cx: Float
    val cy: Float
    val scale: Float
    val rotation: Float
}

data class StickerOverlay(
    override val id: Long,
    val emoji: String,
    override val cx: Float = 0.5f,
    override val cy: Float = 0.5f,
    override val scale: Float = 1f,
    override val rotation: Float = 0f,
) : Overlay

data class TextOverlay(
    override val id: Long,
    val text: String,
    val color: Int,
    override val cx: Float = 0.5f,
    override val cy: Float = 0.5f,
    override val scale: Float = 1f,
    override val rotation: Float = 0f,
) : Overlay

/** Applies a pinch/drag gesture to an overlay. Pan is in canvas fractions. */
fun Overlay.transformed(panX: Float, panY: Float, zoom: Float, rotate: Float): Overlay {
    val nx = (cx + panX).coerceIn(0f, 1f)
    val ny = (cy + panY).coerceIn(0f, 1f)
    val ns = (scale * zoom).coerceIn(0.3f, 6f)
    val nr = rotation + rotate
    return when (this) {
        is StickerOverlay -> copy(cx = nx, cy = ny, scale = ns, rotation = nr)
        is TextOverlay -> copy(cx = nx, cy = ny, scale = ns, rotation = nr)
    }
}

/** Global look of the collage. gap and radius are fractions of the canvas width. */
data class CollageStyle(
    val background: Int = 0xFFFFFFFF.toInt(),
    val gap: Float = 0.02f,
    val radius: Float = 0f,
)

/** Everything needed to draw the collage (except the bitmaps themselves). Immutable: every edit makes a copy. */
data class EditorDoc(
    val template: Template,
    val transforms: List<CellTransform>,
    val overlays: List<Overlay> = emptyList(),
    val style: CollageStyle = CollageStyle(),
)

sealed interface Selection {
    data object None : Selection
    data class Cell(val index: Int) : Selection
    data class Item(val id: Long) : Selection
}
