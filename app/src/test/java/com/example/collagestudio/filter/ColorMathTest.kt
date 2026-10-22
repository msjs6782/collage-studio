package com.example.collagestudio.filter

import com.example.collagestudio.model.Adjustments
import com.example.collagestudio.model.Curves
import com.example.collagestudio.model.HSL_BANDS
import com.example.collagestudio.model.SplitTone
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorMathTest {

    private val identity = IntArray(256) { it }

    private fun params(
        adj: Adjustments = Adjustments(),
        saturation: Float = 1f,
        duo: Pair<Int, Int>? = null,
        hsl: List<Float>? = null,
        split: SplitTone? = null,
    ) = PixelParams(
        luts = ColorMath.buildLuts(ToneCurve.IDENTITY, 1f, adj),
        saturation = saturation,
        duoShadow = duo?.first,
        duoHighlight = duo?.second,
        duoAmount = if (duo != null) 1f else 0f,
        hsl = hsl?.let { ColorMath.hslTable(it) },
        split = split,
    )

    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    private fun r(c: Int) = (c shr 16) and 0xFF
    private fun g(c: Int) = (c shr 8) and 0xFF
    private fun b(c: Int) = c and 0xFF

    @Test
    fun neutralAdjustmentsAreIdentity() {
        ColorMath.buildLuts(ToneCurve.IDENTITY, 1f, Adjustments()).forEach { assertArrayEquals(identity, it) }
    }

    @Test
    fun brightnessRaisesMidtonesButKeepsEndpoints() {
        val (r, _, _) = ColorMath.buildLuts(ToneCurve.IDENTITY, 1f, Adjustments(brightness = 0.5f))
        assertEquals(0, r[0])
        assertEquals(255, r[255])
        assertTrue(r[128] > 140)
    }

    @Test
    fun contrastSpreadsValues() {
        val (r, _, _) = ColorMath.buildLuts(ToneCurve.IDENTITY, 1f, Adjustments(contrast = 0.5f))
        assertTrue(r[64] < 64)
        assertTrue(r[192] > 192)
    }

    @Test
    fun warmthAddsRedRemovesBlue() {
        val (r, g, b) = ColorMath.buildLuts(ToneCurve.IDENTITY, 1f, Adjustments(warmth = 1f))
        assertTrue(r[128] > 128)
        assertEquals(128, g[128])
        assertTrue(b[128] < 128)
    }

    @Test
    fun fadeLiftsBlacks() {
        val (r, _, _) = ColorMath.buildLuts(ToneCurve.IDENTITY, 1f, Adjustments(fade = 1f))
        assertTrue(r[0] > 30)
    }

    @Test
    fun userCurveIsApplied() {
        // Master curve that lifts the midpoint to 0.75.
        val luts = ColorMath.buildLuts(ToneCurve.IDENTITY, 1f, Adjustments(), Curves(master = listOf(0f, 0f, 0.5f, 0.75f, 1f, 1f)))
        luts.forEach { assertTrue(it[128] in 185..195) }
        // A red-only curve leaves green alone.
        val (r, g, _) = ColorMath.buildLuts(ToneCurve.IDENTITY, 1f, Adjustments(), Curves(red = listOf(0f, 0.2f, 1f, 1f)))
        assertTrue(r[0] > 40)
        assertEquals(0, g[0])
    }

    @Test
    fun zeroSaturationMakesGrey() {
        val px = intArrayOf(rgb(200, 50, 50))
        ColorMath.processPixels(px, params(saturation = 0f))
        assertEquals(r(px[0]), g(px[0]))
        assertEquals(g(px[0]), b(px[0]))
    }

    @Test
    fun duotoneMapsBlackAndWhiteToItsColours() {
        val shadow = rgb(10, 20, 30)
        val highlight = rgb(240, 220, 200)
        val px = intArrayOf(rgb(0, 0, 0), rgb(255, 255, 255))
        ColorMath.processPixels(px, params(duo = shadow to highlight))
        assertEquals(shadow, px[0])
        assertEquals(highlight, px[1])
    }

    @Test
    fun hslRoundTrip() {
        val out = FloatArray(3)
        for (c in listOf(rgb(200, 50, 50), rgb(10, 200, 90), rgb(30, 60, 220), rgb(128, 128, 128))) {
            ColorMath.rgbToHsl(r(c) / 255f, g(c) / 255f, b(c) / 255f, out)
            ColorMath.hslToRgb(out[0], out[1], out[2], out)
            assertEquals(r(c).toFloat(), out[0] * 255f, 1f)
            assertEquals(g(c).toFloat(), out[1] * 255f, 1f)
            assertEquals(b(c).toFloat(), out[2] * 255f, 1f)
        }
    }

    @Test
    fun hslTableIsNullWhenUntouched() {
        assertEquals(null, ColorMath.hslTable(List(HSL_BANDS * 3) { 0f }))
    }

    @Test
    fun hslDesaturatesOnlyItsBand() {
        val values = MutableList(HSL_BANDS * 3) { 0f }
        values[5 * 3 + 1] = -1f // blue band: saturation -100
        val blue = rgb(30, 60, 220)
        val red = rgb(220, 40, 40)
        val px = intArrayOf(blue, red)
        ColorMath.processPixels(px, params(hsl = values))
        // Blue became grey-ish; red unchanged.
        assertTrue(kotlin.math.abs(r(px[0]) - b(px[0])) < 20)
        assertEquals(red, px[1])
    }

    @Test
    fun hslLeavesGreysAlone() {
        val values = List(HSL_BANDS * 3) { 0.8f }
        val grey = rgb(120, 120, 120)
        val px = intArrayOf(grey)
        ColorMath.processPixels(px, params(hsl = values))
        assertEquals(grey, px[0])
    }

    @Test
    fun splitToneTintsShadowsAndHighlights() {
        val px = intArrayOf(rgb(30, 30, 30), rgb(230, 230, 230))
        // Blue shadows, orange highlights.
        ColorMath.processPixels(px, params(split = SplitTone(shadowHue = 220f, shadowAmount = 1f, highlightHue = 30f, highlightAmount = 1f)))
        assertTrue(b(px[0]) > r(px[0]))
        assertTrue(r(px[1]) > b(px[1]))
    }

    @Test
    fun grainIsDeterministicAndKeepsAlpha() {
        val a = IntArray(64) { rgb(128, 128, 128) }
        val b = a.copyOf()
        ColorMath.grain(a, 1f)
        ColorMath.grain(b, 1f)
        assertArrayEquals(a, b)
        assertTrue(a.all { (it ushr 24) == 0xFF })
        assertTrue(a.toSet().size > 1)
    }

    @Test
    fun noiseStaysInRange() {
        for (i in 0 until 10_000) assertTrue(ColorMath.noise(i) in -1f..1f)
    }

    @Test
    fun sharpenLeavesFlatAreasAlone() {
        val px = IntArray(25) { rgb(90, 90, 90) }
        ColorMath.sharpen(px, 5, 5, 1f)
        assertTrue(px.all { it == rgb(90, 90, 90) })
    }

    @Test
    fun sharpenIncreasesEdgeContrast() {
        val px = IntArray(9) { rgb(100, 100, 100) }
        px[4] = rgb(150, 150, 150)
        ColorMath.sharpen(px, 3, 3, 1f)
        assertTrue(r(px[4]) > 150)
    }
}
