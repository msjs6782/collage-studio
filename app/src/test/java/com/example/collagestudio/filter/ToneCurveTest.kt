package com.example.collagestudio.filter

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File

class ToneCurveTest {

    private fun acv(vararg curves: List<Pair<Int, Int>>): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeShort(1)
            out.writeShort(curves.size)
            curves.forEach { pts ->
                out.writeShort(pts.size)
                pts.forEach { (x, y) ->
                    out.writeShort(y) // output first, as in Photoshop files
                    out.writeShort(x)
                }
            }
        }
        return bytes.toByteArray()
    }

    private val identity = listOf(0 to 0, 255 to 255)

    @Test
    fun identityCurveLeavesValuesUnchanged() {
        val (r, g, b) = ToneCurve.parseAcv(acv(identity, identity, identity, identity)).luts()
        val expected = IntArray(256) { it }
        assertArrayEquals(expected, r)
        assertArrayEquals(expected, g)
        assertArrayEquals(expected, b)
    }

    @Test
    fun splinePassesThroughControlPoints() {
        val table = ToneCurve.table(listOf(0f to 0f, 64f to 44f, 192f to 214f, 255f to 255f))
        assertEquals(0, table[0])
        assertEquals(44, table[64])
        assertEquals(214, table[192])
        assertEquals(255, table[255])
    }

    @Test
    fun channelCurveThenCompositeCurve() {
        val invert = listOf(0 to 255, 255 to 0)
        // Red is inverted by its own curve, then inverted again by the composite curve.
        val (r, g, _) = ToneCurve.parseAcv(acv(invert, invert, identity, identity)).luts()
        assertEquals(10, r[10])
        assertEquals(245, g[10])
    }

    @Test
    fun zeroIntensityIsIdentity() {
        val (r, _, _) = ToneCurve.parseAcv(acv(listOf(0 to 40, 255 to 230))).luts(intensity = 0f)
        assertArrayEquals(IntArray(256) { it }, r)
    }

    @Test
    fun missingChannelCurvesDefaultToIdentity() {
        val (r, _, _) = ToneCurve.parseAcv(acv(listOf(0 to 40, 255 to 230))).luts()
        assertEquals(40, r[0])
        assertEquals(230, r[255])
    }

    @Test
    fun bundledFilterFilesParse() {
        val dir = listOf("src/main/assets/filters", "app/src/main/assets/filters").map(::File).firstOrNull { it.isDirectory }
            ?: return
        val files = dir.listFiles { f -> f.extension == "acv" }.orEmpty()
        assertTrue(files.isNotEmpty())
        files.forEach { f ->
            val luts = ToneCurve.parseAcv(f.readBytes()).luts()
            luts.forEach { t -> assertTrue(f.name, t.all { it in 0..255 }) }
        }
    }
}
