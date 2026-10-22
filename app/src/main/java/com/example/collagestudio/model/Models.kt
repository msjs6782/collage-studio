package com.example.collagestudio.model

import kotlinx.serialization.Serializable

/** One photo slot in a template. Coordinates are fractions (0..1) of the canvas. */
@Serializable
data class CellSpec(
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
    /** Optional clip shape: "circle", "heart" or "star". Null = rectangle. */
    val mask: String? = null,
)

/**
 * A collage layout loaded from assets/templates.json. aspect = width / height.
 * aspect = 0 means "use the first photo's own aspect ratio" (the "original" crop).
 */
@Serializable
data class Template(
    val id: String,
    val name: String,
    val aspect: Float,
    val cells: List<CellSpec>,
    /** "layout" or "crop" — crop templates are single-photo frames with a fixed ratio. */
    val group: String = "layout",
)

/** A colour filter loaded from assets/filters.json. */
data class FilterSpec(
    val id: String,
    val name: String,
    /** Path of a Photoshop .acv tone-curve file inside assets, or null for no curve. */
    val acv: String? = null,
    val saturation: Float = 1f,
    /** Optional duotone: luminance is remapped from [duoShadow] to [duoHighlight]. */
    val duoShadow: Int? = null,
    val duoHighlight: Int? = null,
    /** How strongly the duotone replaces the colour (0..1). */
    val duoAmount: Float = 1f,
    /** Grain added by the filter itself (0..1). */
    val grain: Float = 0f,
    /** Vignette added by the filter itself (0..1). */
    val vignette: Float = 0f,
)

/** A font bundled in assets/fonts. weight > 0 selects an instance of a variable font. */
data class FontSpec(
    val id: String,
    val name: String,
    val file: String,
    val weight: Int = 0,
)

/** Light/colour adjustments for one photo. All values are in -1..1 (or 0..1 where noted). */
@Serializable
data class Adjustments(
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val warmth: Float = 0f,
    val tint: Float = 0f,
    val highlights: Float = 0f,
    val shadows: Float = 0f,
    /** 0..1 */
    val fade: Float = 0f,
    /** 0..1 */
    val sharpen: Float = 0f,
    /** 0..1 */
    val vignette: Float = 0f,
    /** 0..1 */
    val grain: Float = 0f,
) {
    val isIdentity: Boolean get() = this == Adjustments()
}

/** Crop rectangle as fractions (0..1) of the rotated + straightened photo. */
@Serializable
data class CropRect(val l: Float = 0f, val t: Float = 0f, val r: Float = 1f, val b: Float = 1f) {
    val isFull: Boolean get() = l <= 0f && t <= 0f && r >= 1f && b >= 1f
    val width: Float get() = r - l
    val height: Float get() = b - t
}

/**
 * User tone curves. Each list holds control points flattened as x0, y0, x1, y1, ... in 0..1.
 * An empty list means "no change" for that channel.
 */
@Serializable
data class Curves(
    val master: List<Float> = emptyList(),
    val red: List<Float> = emptyList(),
    val green: List<Float> = emptyList(),
    val blue: List<Float> = emptyList(),
) {
    val isIdentity: Boolean get() = master.isEmpty() && red.isEmpty() && green.isEmpty() && blue.isEmpty()

    fun channel(i: Int): List<Float> = when (i) {
        1 -> red
        2 -> green
        3 -> blue
        else -> master
    }

    fun withChannel(i: Int, points: List<Float>): Curves = when (i) {
        1 -> copy(red = points)
        2 -> copy(green = points)
        3 -> copy(blue = points)
        else -> copy(master = points)
    }
}

/** Colour toning of shadows and highlights. Hues in degrees, amounts 0..1, balance -1..1. */
@Serializable
data class SplitTone(
    val shadowHue: Float = 215f,
    val shadowAmount: Float = 0f,
    val highlightHue: Float = 40f,
    val highlightAmount: Float = 0f,
    val balance: Float = 0f,
) {
    val isIdentity: Boolean get() = shadowAmount == 0f && highlightAmount == 0f
}

/**
 * Background blur that keeps a circle (mode 1) or a band (mode 2) sharp.
 * Centre is in source-photo fractions; size is a fraction of the photo's shorter side;
 * (dirX, dirY) is the band direction in source pixels.
 */
@Serializable
data class FocusBlur(
    val mode: Int = 0,
    val cx: Float = 0.5f,
    val cy: Float = 0.5f,
    val dirX: Float = 1f,
    val dirY: Float = 0f,
    val size: Float = 0.25f,
    val feather: Float = 0.5f,
    val amount: Float = 0.6f,
) {
    companion object {
        const val OFF = 0
        const val RADIAL = 1
        const val LINEAR = 2
    }
}

/**
 * A local-adjustment brush stroke. points = x0, y0, x1, y1, ... in source-photo fractions;
 * radius is a fraction of the photo's shorter side.
 */
@Serializable
data class LocalStroke(
    val points: List<Float>,
    val radius: Float,
    val mode: Int,
    val strength: Float,
) {
    companion object {
        const val DODGE = 0
        const val BURN = 1
        const val BLUR = 2
        const val PIXELATE = 3
        const val SATURATE = 4
        const val DESATURATE = 5
        const val COUNT = 6
    }
}

/** Number of HSL colour bands (red, orange, yellow, green, aqua, blue, purple, magenta). */
const val HSL_BANDS = 8

/** Every edit applied to one source photo. */
@Serializable
data class PhotoEdit(
    val filterId: String = "none",
    val intensity: Float = 1f,
    val adjust: Adjustments = Adjustments(),
    /** 0, 90, 180 or 270 degrees clockwise. */
    val rotation: Int = 0,
    val flipH: Boolean = false,
    val flipV: Boolean = false,
    /** Fine rotation in degrees, -45..45. The photo is zoomed so no empty corners show. */
    val straighten: Float = 0f,
    val crop: CropRect = CropRect(),
    val curves: Curves = Curves(),
    /** Per band: hue shift, saturation, lightness (each -1..1), flattened. */
    val hsl: List<Float> = List(HSL_BANDS * 3) { 0f },
    val split: SplitTone = SplitTone(),
    val focus: FocusBlur = FocusBlur(),
    val local: List<LocalStroke> = emptyList(),
) {
    val hasGeometry: Boolean get() = rotation != 0 || flipH || flipV || straighten != 0f || !crop.isFull

    /** The same photo with only its geometry (no colour, no effects): the "before" view. */
    fun geometryOnly(): PhotoEdit = PhotoEdit(rotation = rotation, flipH = flipH, flipV = flipV, straighten = straighten, crop = crop)
}

/** How the user has moved/zoomed the photo inside one cell. dx/dy are fractions of the cell size. */
@Serializable
data class CellTransform(
    val scale: Float = 1f,
    val dx: Float = 0f,
    val dy: Float = 0f,
)

/** Text look. Colours are ARGB ints; sizes are fractions of the font size. */
@Serializable
data class TextStyle(
    val font: String = "cairo",
    val color: Int = 0xFFFFFFFF.toInt(),
    /** 0 = start (right in Arabic), 1 = centre, 2 = end */
    val align: Int = 1,
    /** Outline width, 0..1 */
    val stroke: Float = 0f,
    val strokeColor: Int = 0xFF000000.toInt(),
    val shadow: Boolean = true,
    val background: Boolean = false,
    val backgroundColor: Int = 0xFF000000.toInt(),
    val backgroundOpacity: Float = 0.55f,
    /** Extra space between letters, 0..0.3 em */
    val letterSpacing: Float = 0f,
)

@Serializable
sealed interface OverlayKind {
    @Serializable
    data class Sticker(val emoji: String) : OverlayKind

    @Serializable
    data class Shape(val shape: String, val color: Int) : OverlayKind

    @Serializable
    data class Text(val text: String, val style: TextStyle) : OverlayKind
}

/** Something drawn on top of the collage. cx/cy are fractions of the canvas; rotation in degrees. */
@Serializable
data class Overlay(
    val id: Long,
    val kind: OverlayKind,
    val cx: Float = 0.5f,
    val cy: Float = 0.5f,
    val scale: Float = 1f,
    val rotation: Float = 0f,
    val opacity: Float = 1f,
    val flip: Boolean = false,
)

/** Applies a pinch/drag gesture to an overlay. Pan is in canvas fractions. */
fun Overlay.transformed(panX: Float, panY: Float, zoom: Float, rotate: Float): Overlay = copy(
    cx = (cx + panX).coerceIn(0f, 1f),
    cy = (cy + panY).coerceIn(0f, 1f),
    scale = (scale * zoom).coerceIn(0.2f, 8f),
    rotation = rotation + rotate,
)

/** A free-hand brush stroke. points = x0,y0,x1,y1,... in canvas fractions; width is a fraction of canvas width. */
@Serializable
data class Stroke(
    val points: FloatArray,
    val color: Int,
    val width: Float,
    val neon: Boolean,
) {
    // FloatArray uses identity equality by default; compare contents so undo history works.
    override fun equals(other: Any?): Boolean =
        other is Stroke && points.contentEquals(other.points) && color == other.color && width == other.width && neon == other.neon

    override fun hashCode(): Int = ((points.contentHashCode() * 31 + color) * 31 + width.hashCode()) * 31 + neon.hashCode()
}

/** Global look of the collage. gap and radius are fractions of the canvas width. */
@Serializable
data class CollageStyle(
    val background: Int = 0xFFFFFFFF.toInt(),
    val gap: Float = 0.02f,
    val radius: Float = 0f,
)

/** Everything needed to draw the collage (except the bitmaps). Immutable: every edit makes a copy. */
@Serializable
data class EditorDoc(
    val template: Template,
    val transforms: List<CellTransform>,
    val edits: List<PhotoEdit>,
    val overlays: List<Overlay> = emptyList(),
    val strokes: List<Stroke> = emptyList(),
    val style: CollageStyle = CollageStyle(),
)

sealed interface Selection {
    data object None : Selection
    data class Cell(val index: Int) : Selection
    data class Item(val id: Long) : Selection
}
