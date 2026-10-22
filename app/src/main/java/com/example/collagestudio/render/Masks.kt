package com.example.collagestudio.render

import android.graphics.Path
import android.graphics.RectF
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Clip shapes for collage cells. Picart shipped a PNG per shape; drawing them as vector paths keeps
 * them sharp at any size and costs no APK space. A new shape = one new branch here + "mask" in JSON.
 */
object Masks {
    fun path(mask: String?, r: RectF, cornerRadius: Float): Path = Path().apply {
        when (mask) {
            "circle" -> addCircle(r.centerX(), r.centerY(), min(r.width(), r.height()) / 2f, Path.Direction.CW)
            "heart" -> heart(this, r)
            "star" -> star(this, r)
            else -> if (cornerRadius > 0f) {
                addRoundRect(r, cornerRadius, cornerRadius, Path.Direction.CW)
            } else {
                addRect(r, Path.Direction.CW)
            }
        }
    }

    private fun heart(p: Path, r: RectF) {
        val s = min(r.width(), r.height())
        val left = r.centerX() - s / 2f
        val top = r.centerY() - s / 2f
        fun x(u: Float) = left + u * s
        fun y(v: Float) = top + v * s
        p.moveTo(x(0.5f), y(0.25f))
        p.cubicTo(x(0.5f), y(0.22f), x(0.45f), y(0.05f), x(0.25f), y(0.05f))
        p.cubicTo(x(0.0f), y(0.05f), x(0.0f), y(0.35f), x(0.0f), y(0.35f))
        p.cubicTo(x(0.0f), y(0.55f), x(0.2f), y(0.77f), x(0.5f), y(0.95f))
        p.cubicTo(x(0.8f), y(0.77f), x(1.0f), y(0.55f), x(1.0f), y(0.35f))
        p.cubicTo(x(1.0f), y(0.35f), x(1.0f), y(0.05f), x(0.75f), y(0.05f))
        p.cubicTo(x(0.6f), y(0.05f), x(0.5f), y(0.22f), x(0.5f), y(0.25f))
        p.close()
    }

    private fun star(p: Path, r: RectF, points: Int = 5) {
        val outer = min(r.width(), r.height()) / 2f
        val inner = outer * 0.48f
        val cx = r.centerX()
        val cy = r.centerY() + outer * 0.06f // optical centring
        for (i in 0 until points * 2) {
            val radius = if (i % 2 == 0) outer else inner
            val angle = -PI / 2 + i * PI / points
            val px = cx + (radius * cos(angle)).toFloat()
            val py = cy + (radius * sin(angle)).toFloat()
            if (i == 0) p.moveTo(px, py) else p.lineTo(px, py)
        }
        p.close()
    }
}
