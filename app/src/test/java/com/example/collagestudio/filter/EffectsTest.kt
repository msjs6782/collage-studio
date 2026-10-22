package com.example.collagestudio.filter

import com.example.collagestudio.model.FocusBlur
import com.example.collagestudio.model.LocalStroke
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EffectsTest {

    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    private fun r(c: Int) = (c shr 16) and 0xFF

    /** Left half black, right half white. */
    private fun edge(w: Int, h: Int) = IntArray(w * h) { i -> if (i % w < w / 2) rgb(0, 0, 0) else rgb(255, 255, 255) }

    @Test
    fun blurKeepsFlatImageFlat() {
        val px = IntArray(100) { rgb(70, 80, 90) }
        assertArrayEquals(px, Effects.boxBlur(px, 10, 10, 3))
    }

    @Test
    fun blurSoftensAnEdge() {
        val w = 20
        val px = edge(w, 4)
        val out = Effects.boxBlur(px, w, 4, 3)
        val mid = r(out[w + w / 2])
        assertTrue(mid in 60..200)
        assertTrue(r(out[w]) < 20)
        assertTrue(r(out[w + w - 1]) > 235)
    }

    @Test
    fun pixelateMakesUniformBlocks() {
        val px = IntArray(16) { rgb(it * 10, 0, 0) }
        val out = Effects.pixelate(px, 4, 4, 2)
        assertEquals(out[0], out[1])
        assertEquals(out[0], out[4])
        assertEquals(out[0], out[5])
    }

    @Test
    fun brushMaskCoversStrokeOnly() {
        val stroke = LocalStroke(listOf(0.5f, 0.5f), radius = 0.1f, mode = LocalStroke.DODGE, strength = 1f)
        val mask = Effects.brushMask(100, 100, listOf(stroke), LocalStroke.DODGE)!!
        assertTrue(mask[50 * 100 + 50] > 0.9f)
        assertEquals(0f, mask[0])
        // Strokes of another mode do not count.
        assertEquals(null, Effects.brushMask(100, 100, listOf(stroke), LocalStroke.BURN))
    }

    @Test
    fun brushMaskFollowsAPath() {
        val stroke = LocalStroke(listOf(0.1f, 0.5f, 0.9f, 0.5f), radius = 0.05f, mode = LocalStroke.BLUR, strength = 1f)
        val mask = Effects.brushMask(100, 100, listOf(stroke), LocalStroke.BLUR)!!
        for (x in 15..85 step 10) assertTrue("x=$x", mask[50 * 100 + x] > 0.8f)
        assertEquals(0f, mask[10 * 100 + 50])
    }

    @Test
    fun radialFocusIsSharpInsideBlurredOutside() {
        val mask = Effects.focusMask(100, 100, FocusBlur(mode = FocusBlur.RADIAL, size = 0.2f, feather = 0.3f))!!
        assertEquals(0f, mask[50 * 100 + 50])
        assertTrue(mask[0] > 0.99f)
    }

    @Test
    fun linearFocusFollowsItsDirection() {
        // Horizontal band through the middle.
        val mask = Effects.focusMask(100, 100, FocusBlur(mode = FocusBlur.LINEAR, dirX = 1f, dirY = 0f, size = 0.1f, feather = 0.2f))!!
        assertEquals(0f, mask[50 * 100 + 2])
        assertEquals(0f, mask[50 * 100 + 97])
        assertTrue(mask[2 * 100 + 50] > 0.99f)
    }

    @Test
    fun dodgeLightensBurnDarkens() {
        val mask = FloatArray(1) { 1f }
        val a = intArrayOf(rgb(100, 100, 100))
        Effects.dodgeBurn(a, mask, lighten = true)
        assertTrue(r(a[0]) > 100)
        val b = intArrayOf(rgb(100, 100, 100))
        Effects.dodgeBurn(b, mask, lighten = false)
        assertTrue(r(b[0]) < 100)
    }

    @Test
    fun applyAllWithNothingToDoChangesNothing() {
        val px = IntArray(64) { rgb(it, it * 2, it * 3) }
        val copy = px.copyOf()
        Effects.applyAll(px, 8, 8, sharpen = 0f, local = emptyList(), focus = FocusBlur(), grain = 0f)
        assertArrayEquals(copy, px)
    }
}
