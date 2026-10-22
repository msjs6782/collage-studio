package com.example.collagestudio.filter

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import kotlin.math.roundToInt

/**
 * A Photoshop-style tone curve: one composite (RGB) curve plus one curve per channel.
 * Each curve is stored as a 256-entry lookup table.
 *
 * This is the same idea Picart used through GPUImage's ToneCurve filter, but in plain Kotlin
 * (no Android imports), so it can be unit-tested on the JVM.
 */
class ToneCurve(
    private val master: IntArray,
    private val red: IntArray,
    private val green: IntArray,
    private val blue: IntArray,
) {
    /**
     * Returns the final lookup tables [red, green, blue]: channel curve first, then the composite
     * curve. [intensity] blends between the original value (0) and the full effect (1).
     */
    fun luts(intensity: Float = 1f): Array<IntArray> {
        val t = intensity.coerceIn(0f, 1f)
        return arrayOf(red, green, blue).map { channel ->
            IntArray(256) { v ->
                val full = master[channel[v]]
                (v + (full - v) * t).roundToInt().coerceIn(0, 255)
            }
        }.toTypedArray()
    }

    companion object {
        private val IDENTITY_TABLE = IntArray(256) { it }

        val IDENTITY = ToneCurve(IDENTITY_TABLE, IDENTITY_TABLE, IDENTITY_TABLE, IDENTITY_TABLE)

        /**
         * Parses an .acv file: big-endian shorts — version, curve count, then for each curve a
         * point count followed by (output, input) pairs. Curve 0 = composite, 1..3 = R, G, B.
         */
        fun parseAcv(bytes: ByteArray): ToneCurve {
            val input = DataInputStream(ByteArrayInputStream(bytes))
            input.readShort() // version (1 or 4)
            val count = input.readShort().toInt()
            val tables = ArrayList<IntArray>(count)
            repeat(count) {
                val n = input.readShort().toInt()
                val points = ArrayList<Pair<Float, Float>>(n)
                repeat(n) {
                    val output = input.readShort().toFloat()
                    val inputValue = input.readShort().toFloat()
                    points += inputValue to output
                }
                tables += table(points)
            }
            fun at(i: Int) = tables.getOrElse(i) { IDENTITY_TABLE }
            return ToneCurve(at(0), at(1), at(2), at(3))
        }

        /** Builds a 256-entry table through the control points with a natural cubic spline. */
        fun table(points: List<Pair<Float, Float>>): IntArray {
            val pts = points.sortedBy { it.first }.distinctBy { it.first }
            if (pts.size < 2) return IDENTITY_TABLE.copyOf()
            val n = pts.size
            val xs = DoubleArray(n) { pts[it].first.toDouble() }
            val ys = DoubleArray(n) { pts[it].second.toDouble() }

            // Second derivatives of the natural spline (Numerical Recipes "spline").
            val y2 = DoubleArray(n)
            val u = DoubleArray(n)
            for (i in 1 until n - 1) {
                val sig = (xs[i] - xs[i - 1]) / (xs[i + 1] - xs[i - 1])
                val p = sig * y2[i - 1] + 2.0
                y2[i] = (sig - 1.0) / p
                val d = (ys[i + 1] - ys[i]) / (xs[i + 1] - xs[i]) - (ys[i] - ys[i - 1]) / (xs[i] - xs[i - 1])
                u[i] = (6.0 * d / (xs[i + 1] - xs[i - 1]) - sig * u[i - 1]) / p
            }
            for (k in n - 2 downTo 0) y2[k] = y2[k] * y2[k + 1] + u[k]

            return IntArray(256) { xi ->
                val x = xi.toDouble()
                val y = when {
                    x <= xs[0] -> ys[0]
                    x >= xs[n - 1] -> ys[n - 1]
                    else -> {
                        var hi = 1
                        while (xs[hi] < x) hi++
                        val lo = hi - 1
                        val h = xs[hi] - xs[lo]
                        val a = (xs[hi] - x) / h
                        val b = (x - xs[lo]) / h
                        a * ys[lo] + b * ys[hi] +
                            ((a * a * a - a) * y2[lo] + (b * b * b - b) * y2[hi]) * h * h / 6.0
                    }
                }
                y.roundToInt().coerceIn(0, 255)
            }
        }
    }
}
