package com.example.collagestudio.filter

import com.example.collagestudio.model.Adjustments
import com.example.collagestudio.model.Curves
import com.example.collagestudio.model.HSL_BANDS
import com.example.collagestudio.model.SplitTone
import kotlin.math.pow
import kotlin.math.roundToInt

/** Everything the per-pixel colour pass needs, resolved from a filter + the user's edits. */
class PixelParams(
    /** Final lookup tables for red, green, blue. */
    val luts: Array<IntArray>,
    /** 1 = unchanged, 0 = grey, 2 = double saturation. */
    val saturation: Float,
    /** Duotone colours (ARGB) or null. */
    val duoShadow: Int? = null,
    val duoHighlight: Int? = null,
    val duoAmount: Float = 0f,
    /** Per-degree HSL table from [ColorMath.hslTable], or null when HSL is untouched. */
    val hsl: FloatArray? = null,
    val split: SplitTone? = null,
)

/**
 * Pure-Kotlin colour maths (no Android imports) so it can be unit-tested on the JVM.
 *
 * Order: filter tone curve → user curves → light (exposure, contrast, highlights, shadows, fade)
 * → colour balance (warmth, tint) → saturation → HSL per colour → split toning → duotone.
 */
object ColorMath {

    private val IDENTITY = IntArray(256) { it }

    /** Converts flattened 0..1 control points to a 256-entry table. */
    fun userTable(points: List<Float>): IntArray {
        if (points.size < 4) return IDENTITY
        val pairs = (points.indices step 2).map { points[it] * 255f to points[it + 1] * 255f }
        return ToneCurve.table(pairs)
    }

    fun buildLuts(curve: ToneCurve, intensity: Float, adj: Adjustments, user: Curves = Curves()): Array<IntArray> {
        val base = curve.luts(intensity)
        val master = userTable(user.master)
        val channels = arrayOf(userTable(user.red), userTable(user.green), userTable(user.blue))
        val tone = FloatArray(256) { v -> tone(v / 255f, adj) }
        val offsets = floatArrayOf(
            adj.warmth * 0.10f + adj.tint * 0.05f,
            -adj.tint * 0.09f,
            -adj.warmth * 0.12f + adj.tint * 0.05f,
        )
        return Array(3) { c ->
            IntArray(256) { v ->
                val t = tone[master[channels[c][base[c][v]]]]
                val shifted = t + offsets[c] * (0.35f + 0.65f * t)
                (shifted * 255f).roundToInt().coerceIn(0, 255)
            }
        }
    }

    /** Light adjustments on one value in 0..1. */
    fun tone(x0: Float, adj: Adjustments): Float {
        var x = x0
        if (adj.brightness != 0f) x = x.coerceIn(0f, 1f).pow(2f.pow(-adj.brightness * 0.9f))
        if (adj.contrast != 0f) {
            val k = if (adj.contrast > 0f) 1f + adj.contrast * 1.1f else 1f + adj.contrast * 0.7f
            x = (x - 0.5f) * k + 0.5f
        }
        if (adj.highlights != 0f) x += adj.highlights * 0.25f * smoothstep(0.45f, 1f, x)
        if (adj.shadows != 0f) x += adj.shadows * 0.25f * (1f - smoothstep(0f, 0.55f, x))
        if (adj.fade != 0f) x = x * (1f - adj.fade * 0.3f) + adj.fade * 0.18f
        return x.coerceIn(0f, 1f)
    }

    fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    // ---- HSL per colour band ----

    /** Centre hue (degrees) of each band: red, orange, yellow, green, aqua, blue, purple, magenta. */
    val bandHues = floatArrayOf(0f, 30f, 60f, 120f, 180f, 225f, 275f, 320f)

    /**
     * Builds a 360 × 3 table (hue shift in degrees, saturation multiplier, lightness offset) by
     * blending the two bands around each hue. Returns null when every value is zero.
     */
    fun hslTable(values: List<Float>): FloatArray? {
        if (values.size < HSL_BANDS * 3 || values.all { it == 0f }) return null
        val table = FloatArray(360 * 3)
        for (deg in 0 until 360) {
            var i = HSL_BANDS - 1
            for (b in 0 until HSL_BANDS) if (bandHues[b] <= deg) i = b
            val j = (i + 1) % HSL_BANDS
            val start = bandHues[i]
            val end = if (j == 0) 360f else bandHues[j]
            val t = ((deg - start) / (end - start)).coerceIn(0f, 1f)
            fun mix(k: Int) = values[i * 3 + k] * (1f - t) + values[j * 3 + k] * t
            table[deg * 3] = mix(0) * 30f
            table[deg * 3 + 1] = 1f + mix(1)
            table[deg * 3 + 2] = mix(2) * 0.3f
        }
        return table
    }

    // ---- Pixel pass ----

    /** Applies the colour part of [p] to ARGB pixels in place. */
    fun processPixels(px: IntArray, p: PixelParams) {
        val (lr, lg, lb) = p.luts
        val sat = p.saturation
        val duo = p.duoShadow != null && p.duoHighlight != null && p.duoAmount > 0f
        val sR = ((p.duoShadow ?: 0) shr 16) and 0xFF
        val sG = ((p.duoShadow ?: 0) shr 8) and 0xFF
        val sB = (p.duoShadow ?: 0) and 0xFF
        val hR = ((p.duoHighlight ?: 0) shr 16) and 0xFF
        val hG = ((p.duoHighlight ?: 0) shr 8) and 0xFF
        val hB = (p.duoHighlight ?: 0) and 0xFF
        val hsl = p.hsl
        val split = p.split?.takeUnless { it.isIdentity }
        val shadowTint = split?.let { hueOffset(it.shadowHue) }
        val highlightTint = split?.let { hueOffset(it.highlightHue) }
        val hslOut = FloatArray(3)

        for (i in px.indices) {
            val c = px[i]
            var r = lr[(c shr 16) and 0xFF].toFloat()
            var g = lg[(c shr 8) and 0xFF].toFloat()
            var b = lb[c and 0xFF].toFloat()
            if (sat != 1f) {
                val l = 0.299f * r + 0.587f * g + 0.114f * b
                r = l + (r - l) * sat
                g = l + (g - l) * sat
                b = l + (b - l) * sat
            }
            if (hsl != null) {
                rgbToHsl(r.coerceIn(0f, 255f) / 255f, g.coerceIn(0f, 255f) / 255f, b.coerceIn(0f, 255f) / 255f, hslOut)
                val s0 = hslOut[1]
                if (s0 > 0.02f) {
                    val deg = ((hslOut[0] * 360f).toInt() % 360 + 360) % 360
                    val h2 = (hslOut[0] + hsl[deg * 3] / 360f + 1f) % 1f
                    val s2 = (s0 * hsl[deg * 3 + 1]).coerceIn(0f, 1f)
                    // Weight the lightness change by how colourful the pixel is, so greys stay put.
                    val l2 = (hslOut[2] + hsl[deg * 3 + 2] * minOf(1f, s0 * 2f)).coerceIn(0f, 1f)
                    hslToRgb(h2, s2, l2, hslOut)
                    r = hslOut[0] * 255f
                    g = hslOut[1] * 255f
                    b = hslOut[2] * 255f
                }
            }
            if (split != null) {
                val l = ((0.299f * r + 0.587f * g + 0.114f * b) / 255f).coerceIn(0f, 1f)
                val ws = (1f - l) * (1f - l) * split.shadowAmount * (1f - split.balance * 0.6f)
                val wh = l * l * split.highlightAmount * (1f + split.balance * 0.6f)
                r += (shadowTint!![0] * ws + highlightTint!![0] * wh) * 110f
                g += (shadowTint[1] * ws + highlightTint[1] * wh) * 110f
                b += (shadowTint[2] * ws + highlightTint[2] * wh) * 110f
            }
            if (duo) {
                val l = ((0.299f * r + 0.587f * g + 0.114f * b) / 255f).coerceIn(0f, 1f)
                val a = p.duoAmount
                r += ((sR + (hR - sR) * l) - r) * a
                g += ((sG + (hG - sG) * l) - g) * a
                b += ((sB + (hB - sB) * l) - b) * a
            }
            px[i] = (c and 0xFF000000.toInt()) or
                (r.roundToInt().coerceIn(0, 255) shl 16) or
                (g.roundToInt().coerceIn(0, 255) shl 8) or
                b.roundToInt().coerceIn(0, 255)
        }
    }

    /** A fully saturated colour of [hueDeg] as an offset from grey, each channel in -0.5..0.5. */
    fun hueOffset(hueDeg: Float): FloatArray {
        val out = FloatArray(3)
        hslToRgb(((hueDeg / 360f) % 1f + 1f) % 1f, 1f, 0.5f, out)
        return floatArrayOf(out[0] - 0.5f, out[1] - 0.5f, out[2] - 0.5f)
    }

    fun rgbToHsl(r: Float, g: Float, b: Float, out: FloatArray) {
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val l = (max + min) / 2f
        val d = max - min
        if (d < 1e-6f) {
            out[0] = 0f
            out[1] = 0f
            out[2] = l
            return
        }
        val s = if (l > 0.5f) d / (2f - max - min) else d / (max + min)
        val h = when (max) {
            r -> ((g - b) / d + (if (g < b) 6f else 0f))
            g -> ((b - r) / d + 2f)
            else -> ((r - g) / d + 4f)
        } / 6f
        out[0] = h
        out[1] = s
        out[2] = l
    }

    fun hslToRgb(h: Float, s: Float, l: Float, out: FloatArray) {
        if (s <= 0f) {
            out[0] = l
            out[1] = l
            out[2] = l
            return
        }
        val q = if (l < 0.5f) l * (1f + s) else l + s - l * s
        val p = 2f * l - q
        out[0] = hue2rgb(p, q, h + 1f / 3f)
        out[1] = hue2rgb(p, q, h)
        out[2] = hue2rgb(p, q, h - 1f / 3f)
    }

    private fun hue2rgb(p: Float, q: Float, t0: Float): Float {
        var t = t0
        if (t < 0f) t += 1f
        if (t > 1f) t -= 1f
        return when {
            t < 1f / 6f -> p + (q - p) * 6f * t
            t < 0.5f -> q
            t < 2f / 3f -> p + (q - p) * (2f / 3f - t) * 6f
            else -> p
        }
    }

    /** Deterministic noise in -1..1 for pixel [i], so the grain looks the same on every redraw. */
    fun noise(i: Int): Float {
        var x = i * -0x61c88647 // golden-ratio hash
        x = x xor (x ushr 15)
        x *= -0x7a143595
        x = x xor (x ushr 13)
        return ((x and 0xFF) - 127.5f) / 127.5f
    }

    @Suppress("NOTHING_TO_INLINE")
    private inline fun channel(c: Int, n: Int, s: Int, e: Int, w: Int, shift: Int, k: Float): Int {
        val cv = (c shr shift) and 0xFF
        val sum = ((n shr shift) and 0xFF) + ((s shr shift) and 0xFF) + ((e shr shift) and 0xFF) + ((w shr shift) and 0xFF)
        return (cv + k * (4 * cv - sum)).roundToInt().coerceIn(0, 255)
    }

    /** Unsharp mask with a 4-neighbour kernel. */
    fun sharpen(px: IntArray, w: Int, h: Int, amount: Float) {
        if (w < 3 || h < 3 || amount <= 0f) return
        val k = amount * 0.7f
        val src = px.copyOf()
        for (y in 1 until h - 1) {
            val row = y * w
            for (x in 1 until w - 1) {
                val i = row + x
                val c = src[i]
                val n = src[i - w]
                val s = src[i + w]
                val e = src[i + 1]
                val wv = src[i - 1]
                px[i] = (c and 0xFF000000.toInt()) or
                    (channel(c, n, s, e, wv, 16, k) shl 16) or
                    (channel(c, n, s, e, wv, 8, k) shl 8) or
                    channel(c, n, s, e, wv, 0, k)
            }
        }
    }

    /** Adds deterministic film grain. */
    fun grain(px: IntArray, amount: Float) {
        if (amount <= 0f) return
        val amp = amount * 42f
        for (i in px.indices) {
            val c = px[i]
            val n = (noise(i) * amp).roundToInt()
            val r = (((c shr 16) and 0xFF) + n).coerceIn(0, 255)
            val g = (((c shr 8) and 0xFF) + n).coerceIn(0, 255)
            val b = ((c and 0xFF) + n).coerceIn(0, 255)
            px[i] = (c and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
        }
    }
}
