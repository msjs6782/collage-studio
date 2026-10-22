package com.example.collagestudio.filter

import com.example.collagestudio.model.FocusBlur
import com.example.collagestudio.model.LocalStroke
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Spatial effects on ARGB pixel arrays: blur, pixelate, brush masks and focus masks.
 * Pure Kotlin (no Android imports) so it can be unit-tested on the JVM.
 * All coordinates are fractions of the photo, so results match between preview and full size.
 */
object Effects {

    /** Approximate Gaussian blur: three box-blur passes in each direction. */
    fun boxBlur(src: IntArray, w: Int, h: Int, radius: Int, passes: Int = 3): IntArray {
        val r = radius.coerceAtMost(max(w, h))
        if (r < 1) return src.copyOf()
        val a = src.copyOf()
        val b = IntArray(a.size)
        repeat(passes) {
            blurLine(a, b, w, h, r, horizontal = true)
            blurLine(b, a, w, h, r, horizontal = false)
        }
        return a
    }

    private fun blurLine(s: IntArray, d: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val lines = if (horizontal) h else w
        val len = if (horizontal) w else h
        val step = if (horizontal) 1 else w
        val div = 2 * r + 1
        for (line in 0 until lines) {
            val start = if (horizontal) line * w else line
            var sr = 0
            var sg = 0
            var sb = 0
            for (k in -r..r) {
                val c = s[start + k.coerceIn(0, len - 1) * step]
                sr += (c shr 16) and 0xFF
                sg += (c shr 8) and 0xFF
                sb += c and 0xFF
            }
            for (x in 0 until len) {
                val i = start + x * step
                d[i] = (s[i] and 0xFF000000.toInt()) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                val out = s[start + (x - r).coerceAtLeast(0) * step]
                val inn = s[start + (x + r + 1).coerceAtMost(len - 1) * step]
                sr += ((inn shr 16) and 0xFF) - ((out shr 16) and 0xFF)
                sg += ((inn shr 8) and 0xFF) - ((out shr 8) and 0xFF)
                sb += (inn and 0xFF) - (out and 0xFF)
            }
        }
    }

    /** Mosaic: every block takes its average colour. */
    fun pixelate(src: IntArray, w: Int, h: Int, block: Int): IntArray {
        val out = src.copyOf()
        val bs = block.coerceAtLeast(2)
        var by = 0
        while (by < h) {
            var bx = 0
            val yEnd = min(by + bs, h)
            while (bx < w) {
                val xEnd = min(bx + bs, w)
                var r = 0L
                var g = 0L
                var b = 0L
                var n = 0
                for (y in by until yEnd) for (x in bx until xEnd) {
                    val c = src[y * w + x]
                    r += (c shr 16) and 0xFF
                    g += (c shr 8) and 0xFF
                    b += c and 0xFF
                    n++
                }
                val avg = (0xFF shl 24) or ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (b / n).toInt()
                for (y in by until yEnd) for (x in bx until xEnd) {
                    val i = y * w + x
                    out[i] = (src[i] and 0xFF000000.toInt()) or (avg and 0xFFFFFF)
                }
                bx += bs
            }
            by += bs
        }
        return out
    }

    /** Linear blend: dst = dst + (other - dst) * mask * strength. */
    fun blend(dst: IntArray, other: IntArray, mask: FloatArray, strength: Float = 1f) {
        for (i in dst.indices) {
            val m = mask[i] * strength
            if (m <= 0f) continue
            val a = dst[i]
            val b = other[i]
            val k = m.coerceAtMost(1f)
            val r = (((a shr 16) and 0xFF) + ((((b shr 16) and 0xFF) - ((a shr 16) and 0xFF)) * k)).roundToInt()
            val g = (((a shr 8) and 0xFF) + ((((b shr 8) and 0xFF) - ((a shr 8) and 0xFF)) * k)).roundToInt()
            val bl = ((a and 0xFF) + (((b and 0xFF) - (a and 0xFF)) * k)).roundToInt()
            dst[i] = (a and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or bl
        }
    }

    /**
     * Rasterises the strokes of one [mode] into a soft mask (0..1 per pixel), or returns null if
     * there are none. Each stroke is stamped as overlapping soft circles along its path.
     */
    fun brushMask(w: Int, h: Int, strokes: List<LocalStroke>, mode: Int): FloatArray? {
        val mine = strokes.filter { it.mode == mode && it.points.size >= 2 }
        if (mine.isEmpty()) return null
        val mask = FloatArray(w * h)
        val minSide = min(w, h).toFloat()
        for (s in mine) {
            val rPx = max(1f, s.radius * minSide)
            val spacing = max(1f, rPx * 0.3f)
            val pts = s.points
            var px = pts[0] * w
            var py = pts[1] * h
            stamp(mask, w, h, px, py, rPx, s.strength)
            var i = 2
            while (i + 1 < pts.size) {
                val nx = pts[i] * w
                val ny = pts[i + 1] * h
                val dist = hypot(nx - px, ny - py)
                val steps = ceil(dist / spacing).toInt()
                for (k in 1..steps) {
                    val t = k.toFloat() / steps
                    stamp(mask, w, h, px + (nx - px) * t, py + (ny - py) * t, rPx, s.strength)
                }
                px = nx
                py = ny
                i += 2
            }
        }
        return mask
    }

    private fun stamp(mask: FloatArray, w: Int, h: Int, cx: Float, cy: Float, r: Float, strength: Float) {
        val x0 = (cx - r).toInt().coerceAtLeast(0)
        val x1 = ceil(cx + r).toInt().coerceAtMost(w - 1)
        val y0 = (cy - r).toInt().coerceAtLeast(0)
        val y1 = ceil(cy + r).toInt().coerceAtMost(h - 1)
        val r2 = r * r
        for (y in y0..y1) {
            val dy = y + 0.5f - cy
            for (x in x0..x1) {
                val dx = x + 0.5f - cx
                val d2 = (dx * dx + dy * dy) / r2
                if (d2 >= 1f) continue
                val falloff = (1f - d2) * (1f - d2) // soft edge
                val v = falloff * strength
                val i = y * w + x
                if (v > mask[i]) mask[i] = v
            }
        }
    }

    /** Blur weight per pixel for a focus effect: 0 inside the sharp zone, rising to 1 outside. */
    fun focusMask(w: Int, h: Int, f: FocusBlur): FloatArray? {
        if (f.mode == FocusBlur.OFF) return null
        val mask = FloatArray(w * h)
        val minSide = min(w, h).toFloat()
        val inner = max(1f, f.size * minSide)
        val outer = inner * (1.05f + f.feather * 1.6f)
        val cx = f.cx * w
        val cy = f.cy * h
        var dx = f.dirX
        var dy = f.dirY
        val len = sqrt(dx * dx + dy * dy)
        if (len < 1e-6f) {
            dx = 1f
            dy = 0f
        } else {
            dx /= len
            dy /= len
        }
        for (y in 0 until h) {
            val py = y + 0.5f - cy
            for (x in 0 until w) {
                val px = x + 0.5f - cx
                val d = if (f.mode == FocusBlur.RADIAL) sqrt(px * px + py * py) else abs(px * dy - py * dx)
                mask[y * w + x] = ColorMath.smoothstep(inner, outer, d)
            }
        }
        return mask
    }

    /** Lightens (dodge) or darkens (burn) where the mask is set. */
    fun dodgeBurn(px: IntArray, mask: FloatArray, lighten: Boolean) {
        for (i in px.indices) {
            val m = mask[i] * 0.55f
            if (m <= 0f) continue
            val c = px[i]
            fun ch(v: Int) = if (lighten) (v + (255 - v) * m).roundToInt() else (v * (1f - m)).roundToInt()
            px[i] = (c and 0xFF000000.toInt()) or (ch((c shr 16) and 0xFF) shl 16) or (ch((c shr 8) and 0xFF) shl 8) or ch(c and 0xFF)
        }
    }

    /** Raises (amount > 0) or lowers saturation where the mask is set. */
    fun saturate(px: IntArray, mask: FloatArray, amount: Float) {
        for (i in px.indices) {
            val m = mask[i]
            if (m <= 0f) continue
            val c = px[i]
            val r = ((c shr 16) and 0xFF).toFloat()
            val g = ((c shr 8) and 0xFF).toFloat()
            val b = (c and 0xFF).toFloat()
            val l = 0.299f * r + 0.587f * g + 0.114f * b
            val s = 1f + amount * m
            px[i] = (c and 0xFF000000.toInt()) or
                ((l + (r - l) * s).roundToInt().coerceIn(0, 255) shl 16) or
                ((l + (g - l) * s).roundToInt().coerceIn(0, 255) shl 8) or
                (l + (b - l) * s).roundToInt().coerceIn(0, 255)
        }
    }

    /**
     * Runs every spatial effect in order: sharpen → dodge/burn → saturation brushes → blur brush
     * → pixelate brush → focus blur → grain.
     */
    fun applyAll(
        px: IntArray,
        w: Int,
        h: Int,
        sharpen: Float,
        local: List<LocalStroke>,
        focus: FocusBlur,
        grain: Float,
    ) {
        val minSide = min(w, h)
        ColorMath.sharpen(px, w, h, sharpen)
        brushMask(w, h, local, LocalStroke.DODGE)?.let { dodgeBurn(px, it, lighten = true) }
        brushMask(w, h, local, LocalStroke.BURN)?.let { dodgeBurn(px, it, lighten = false) }
        brushMask(w, h, local, LocalStroke.SATURATE)?.let { saturate(px, it, 1.2f) }
        brushMask(w, h, local, LocalStroke.DESATURATE)?.let { saturate(px, it, -1f) }
        brushMask(w, h, local, LocalStroke.BLUR)?.let { m ->
            blend(px, boxBlur(px, w, h, max(1, (minSide * 0.02f).roundToInt())), m)
        }
        brushMask(w, h, local, LocalStroke.PIXELATE)?.let { m ->
            // Hard-edged mask so the mosaic blocks stay crisp.
            for (i in m.indices) m[i] = if (m[i] > 0.15f) 1f else 0f
            blend(px, pixelate(px, w, h, max(2, (minSide * 0.03f).roundToInt())), m)
        }
        focusMask(w, h, focus)?.let { m ->
            val radius = max(1, (minSide * 0.03f * focus.amount).roundToInt())
            blend(px, boxBlur(px, w, h, radius), m)
        }
        ColorMath.grain(px, grain)
    }
}
