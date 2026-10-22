package com.example.collagestudio.filter

import com.example.collagestudio.model.Adjustments
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorMathTest {

    private val identity = IntArray(256) { it }

    private fun params(adj: Adjustments = Adjustments(), saturation: Float = 1f, duo: Pair<Int, Int>? = null, grain: Float = 0f) = PixelParams(
        luts = ColorMath.buildLuts(ToneCurve.IDENTITY, 1f, adj),
        saturation = saturation,
        duoShadow = duo?.first,
        duoHighlight = duo?.second,
        duoAmount = 1f,
        grain = grain,
        sharpen = adj.sharpen,
    )

    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    @Test
    fun neutralAdjustmentsAreIdentity() {
        val luts = ColorMath.buildLuts(ToneCurve.IDENTITY, 1f, Adjustments())
        luts.forEach { assertArrayEquals(identity, it) }
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
    fun zeroSaturationMakesGrey() {
        val px = intArrayOf(rgb(200, 50, 50))
        ColorMath.processPixels(px, 1, 1, params(saturation = 0f))
        val c = px[0]
        assertEquals((c shr 16) and 0xFF, (c shr 8) and 0xFF)
        assertEquals((c shr 8) and 0xFF, c and 0xFF)
    }

    @Test
    fun duotoneMapsBlackAndWhiteToItsColours() {
        val shadow = rgb(10, 20, 30)
        val highlight = rgb(240, 220, 200)
        val px = intArrayOf(rgb(0, 0, 0), rgb(255, 255, 255))
        ColorMath.processPixels(px, 2, 1, params(duo = shadow to highlight))
        assertEquals(shadow, px[0])
        assertEquals(highlight, px[1])
    }

    @Test
    fun grainIsDeterministicAndKeepsAlpha() {
        val a = IntArray(64) { rgb(128, 128, 128) }
        val b = a.copyOf()
        ColorMath.processPixels(a, 8, 8, params(grain = 1f))
        ColorMath.processPixels(b, 8, 8, params(grain = 1f))
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
        // 3x3 with a bright centre: the centre should get brighter.
        val px = IntArray(9) { rgb(100, 100, 100) }
        px[4] = rgb(150, 150, 150)
        ColorMath.sharpen(px, 3, 3, 1f)
        assertTrue(((px[4] shr 16) and 0xFF) > 150)
    }
}
