package com.example.collagestudio.filter

import android.graphics.Bitmap

object ImageFilter {
    /**
     * Applies a tone curve and a saturation change to every pixel. Runs on the CPU; call it from a
     * background dispatcher. Returns a new bitmap and leaves [src] untouched.
     */
    fun apply(src: Bitmap, curve: ToneCurve, intensity: Float, saturation: Float): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)

        val (r, g, b) = curve.luts(intensity)
        val sat = 1f + (saturation - 1f) * intensity.coerceIn(0f, 1f)

        for (i in px.indices) {
            val c = px[i]
            var rr = r[(c shr 16) and 0xFF]
            var gg = g[(c shr 8) and 0xFF]
            var bb = b[c and 0xFF]
            if (sat != 1f) {
                val l = 0.299f * rr + 0.587f * gg + 0.114f * bb
                rr = (l + (rr - l) * sat).toInt().coerceIn(0, 255)
                gg = (l + (gg - l) * sat).toInt().coerceIn(0, 255)
                bb = (l + (bb - l) * sat).toInt().coerceIn(0, 255)
            }
            px[i] = (c and 0xFF000000.toInt()) or (rr shl 16) or (gg shl 8) or bb
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }
}
