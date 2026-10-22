package com.example.collagestudio.filter

import android.graphics.Bitmap
import android.graphics.Matrix
import com.example.collagestudio.model.FilterSpec
import com.example.collagestudio.model.PhotoEdit

object PhotoProcessor {

    /** Resolves a filter + the user's edit into the parameters of the pixel pass. */
    fun params(edit: PhotoEdit, filter: FilterSpec, curve: ToneCurve): PixelParams {
        val t = if (filter.id == "none") 0f else edit.intensity.coerceIn(0f, 1f)
        val adj = edit.adjust
        return PixelParams(
            luts = ColorMath.buildLuts(curve, t, adj),
            saturation = (1f + (filter.saturation - 1f) * t) * (1f + adj.saturation),
            duoShadow = filter.duoShadow,
            duoHighlight = filter.duoHighlight,
            duoAmount = filter.duoAmount * t,
            grain = (filter.grain * t + adj.grain).coerceIn(0f, 1f),
            sharpen = adj.sharpen,
        )
    }

    /** Total vignette strength for a photo (filter + user slider), drawn by the renderer. */
    fun vignette(edit: PhotoEdit, filter: FilterSpec): Float {
        val t = if (filter.id == "none") 0f else edit.intensity
        return (filter.vignette * t + edit.adjust.vignette).coerceIn(0f, 1f)
    }

    /** Rotation and flips. Returns [src] itself when there is nothing to do. */
    fun geometry(src: Bitmap, edit: PhotoEdit): Bitmap {
        if (edit.rotation == 0 && !edit.flipH && !edit.flipV) return src
        val m = Matrix()
        m.postScale(if (edit.flipH) -1f else 1f, if (edit.flipV) -1f else 1f)
        m.postRotate(edit.rotation.toFloat())
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    /** Full pipeline: geometry, then colour. Never modifies [src]. Call off the main thread. */
    fun process(src: Bitmap, edit: PhotoEdit, filter: FilterSpec, curve: ToneCurve): Bitmap {
        val geo = geometry(src, edit)
        val p = params(edit, filter, curve)
        val identity = filter.id == "none" && edit.adjust.let {
            it.copy(vignette = 0f).isIdentity
        }
        if (identity) return geo
        val w = geo.width
        val h = geo.height
        val px = IntArray(w * h)
        geo.getPixels(px, 0, w, 0, 0, w, h)
        ColorMath.processPixels(px, w, h, p)
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }
}
