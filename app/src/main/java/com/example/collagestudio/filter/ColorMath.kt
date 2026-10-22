package com.example.collagestudio.filter

import com.example.collagestudio.model.Adjustments
import kotlin.math.pow
import kotlin.math.roundToInt

/** Everything the per-pixel pass needs, resolved from a filter + the user's adjustments. */
class PixelParams(
    /** Final lookup tables for red, green, blue. */
    val luts: Array<IntArray>,
    /** 1 = unchanged, 0 = grey, 2 = double saturation. */
    val saturation: Float,
    /** Duotone colours (ARGB) or null. */
    val duoShadow: Int?,
    val duoHighlight: Int?,
    val duoAmount: Float,
    /** 0..1 */
    val grain: Float,
    /** 0..1 */
    val sharpen: Float,
)

/**
 * Pure-Kotlin colour maths (no Android imports) so it can be unit-tested on the JVM.
 *
 * Order of operations: filter tone curve → light (exposure, contrast, highlights, shadows, fade)
 * → colour balance (warmth, tint) → saturation → duotone → grain → sharpen.
 */
object ColorMath {

    fun buildLuts(curve: ToneCurve, intensity: Float, adj: Adjustments): Array<IntArray> {
        val base = curve.luts(intensity)
        val tone = FloatArray(256) { v -> tone(v / 255f, adj) }
        val offsets = floatArrayOf(
            adj.warmth * 0.10f + adj.tint * 0.05f,
            -adj.tint * 0.09f,
            -adj.warmth * 0.12f + adj.tint * 0.05f,
        )
        return Array(3) { c ->
            IntArray(256) { v ->
                val t = tone[base[c][v]]
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

    private fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** Applies [p] to ARGB pixels in place. */
    fun processPixels(px: IntArray, w: Int, h: Int, p: PixelParams) {
        val (lr, lg, lb) = p.luts
        val sat = p.saturation
        val duo = p.duoShadow != null && p.duoHighlight != null && p.duoAmount > 0f
        val sR = ((p.duoShadow ?: 0) shr 16) and 0xFF
        val sG = ((p.duoShadow ?: 0) shr 8) and 0xFF
        val sB = (p.duoShadow ?: 0) and 0xFF
        val hR = ((p.duoHighlight ?: 0) shr 16) and 0xFF
        val hG = ((p.duoHighlight ?: 0) shr 8) and 0xFF
        val hB = (p.duoHighlight ?: 0) and 0xFF
        val grainAmp = p.grain * 42f

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
            if (duo) {
                val l = ((0.299f * r + 0.587f * g + 0.114f * b) / 255f).coerceIn(0f, 1f)
                val a = p.duoAmount
                r += ((sR + (hR - sR) * l) - r) * a
                g += ((sG + (hG - sG) * l) - g) * a
                b += ((sB + (hB - sB) * l) - b) * a
            }
            if (grainAmp > 0f) {
                val n = noise(i) * grainAmp
                r += n
                g += n
                b += n
            }
            px[i] = (c and 0xFF000000.toInt()) or
                (r.roundToInt().coerceIn(0, 255) shl 16) or
                (g.roundToInt().coerceIn(0, 255) shl 8) or
                b.roundToInt().coerceIn(0, 255)
        }
        if (p.sharpen > 0f) sharpen(px, w, h, p.sharpen)
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
        if (w < 3 || h < 3) return
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
}
