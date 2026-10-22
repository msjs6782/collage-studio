package com.example.collagestudio.filter

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import com.example.collagestudio.model.FilterSpec
import com.example.collagestudio.model.PhotoEdit
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/** A geometry transform from source-photo pixels to output pixels, plus the output size. */
class GeometryResult(val matrix: Matrix, val width: Int, val height: Int)

/**
 * The per-photo pipeline. Colour and spatial effects run first, in source-photo coordinates, so
 * brush strokes and the focus point stay on the right spot whatever the later rotation or crop.
 * Geometry (90° turns, flips, straighten, crop) runs last, in one resampling step.
 */
object PhotoProcessor {

    fun params(edit: PhotoEdit, filter: FilterSpec, curve: ToneCurve): PixelParams {
        val t = if (filter.id == "none") 0f else edit.intensity.coerceIn(0f, 1f)
        val adj = edit.adjust
        return PixelParams(
            luts = ColorMath.buildLuts(curve, t, adj, edit.curves),
            saturation = (1f + (filter.saturation - 1f) * t) * (1f + adj.saturation),
            duoShadow = filter.duoShadow,
            duoHighlight = filter.duoHighlight,
            duoAmount = filter.duoAmount * t,
            hsl = ColorMath.hslTable(edit.hsl),
            split = edit.split,
        )
    }

    /** Total vignette strength for a photo (filter + user slider), drawn by the renderer. */
    fun vignette(edit: PhotoEdit, filter: FilterSpec): Float {
        val t = if (filter.id == "none") 0f else edit.intensity
        return (filter.vignette * t + edit.adjust.vignette).coerceIn(0f, 1f)
    }

    private fun grainOf(edit: PhotoEdit, filter: FilterSpec): Float {
        val t = if (filter.id == "none") 0f else edit.intensity
        return (filter.grain * t + edit.adjust.grain).coerceIn(0f, 1f)
    }

    /** True when no colour or spatial work is needed (geometry may still apply). */
    fun isColorIdentity(edit: PhotoEdit, filter: FilterSpec): Boolean =
        filter.id == "none" &&
            edit.adjust.copy(vignette = 0f).isIdentity &&
            edit.curves.isIdentity &&
            edit.hsl.all { it == 0f } &&
            edit.split.isIdentity &&
            edit.focus.mode == 0 &&
            edit.local.isEmpty()

    /** Zoom needed after rotating a w×h image by [deg] so it still covers the w×h frame. */
    fun coverScale(w: Float, h: Float, deg: Float): Float {
        val a = Math.toRadians(abs(deg).toDouble())
        val c = cos(a).toFloat()
        val s = sin(a).toFloat()
        return max(c + s * h / w, c + s * w / h)
    }

    /**
     * Source pixels → output pixels: 90° rotation, flips (in the rotated view), straighten with
     * cover zoom, then the crop (when [withCrop]).
     */
    fun geometry(srcW: Int, srcH: Int, e: PhotoEdit, withCrop: Boolean = true): GeometryResult {
        val turned = e.rotation % 180 != 0
        val ow = if (turned) srcH else srcW
        val oh = if (turned) srcW else srcH
        val m = Matrix()
        m.postTranslate(-srcW / 2f, -srcH / 2f)
        m.postRotate(e.rotation.toFloat())
        m.postScale(if (e.flipH) -1f else 1f, if (e.flipV) -1f else 1f)
        if (e.straighten != 0f) {
            m.postRotate(e.straighten)
            val s = coverScale(ow.toFloat(), oh.toFloat(), e.straighten)
            m.postScale(s, s)
        }
        m.postTranslate(ow / 2f, oh / 2f)
        if (!withCrop || e.crop.isFull) return GeometryResult(m, ow, oh)
        val c = e.crop
        m.postTranslate(-c.l * ow, -c.t * oh)
        return GeometryResult(m, max(1, (c.width * ow).roundToInt()), max(1, (c.height * oh).roundToInt()))
    }

    fun applyGeometry(src: Bitmap, e: PhotoEdit, withCrop: Boolean = true): Bitmap {
        if (!e.hasGeometry || (!withCrop && e.rotation == 0 && !e.flipH && !e.flipV && e.straighten == 0f)) return src
        val g = geometry(src.width, src.height, e, withCrop)
        val out = Bitmap.createBitmap(g.width, g.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, g.matrix, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        return out
    }

    /** Colour + effects in source coordinates, without geometry. Never modifies [src]. */
    fun processColor(src: Bitmap, edit: PhotoEdit, filter: FilterSpec, curve: ToneCurve): Bitmap {
        if (isColorIdentity(edit, filter)) return src
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        ColorMath.processPixels(px, params(edit, filter, curve))
        Effects.applyAll(px, w, h, edit.adjust.sharpen, edit.local, edit.focus, grainOf(edit, filter))
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    /** Full pipeline. Call off the main thread. */
    fun process(src: Bitmap, edit: PhotoEdit, filter: FilterSpec, curve: ToneCurve, withCrop: Boolean = true): Bitmap =
        applyGeometry(processColor(src, edit, filter, curve), edit, withCrop)
}
