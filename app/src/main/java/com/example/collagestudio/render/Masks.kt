package com.example.collagestudio.render

import android.graphics.Path
import android.graphics.RectF
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Vector shapes used two ways: as clip masks for collage cells ("mask" in templates.json) and as
 * coloured shape stickers. Picart shipped a PNG per shape; paths stay sharp at any size.
 */
object Masks {
    /** Shapes offered as stickers, in display order. */
    val stickerShapes = listOf("heart", "star", "circle", "square", "sparkle", "flower", "moon", "hexagon", "bubble", "arrow", "drop")

    fun path(mask: String?, r: RectF, cornerRadius: Float): Path = Path().apply {
        val s = min(r.width(), r.height())
        val sq = RectF(r.centerX() - s / 2f, r.centerY() - s / 2f, r.centerX() + s / 2f, r.centerY() + s / 2f)
        when (mask) {
            "circle" -> addCircle(r.centerX(), r.centerY(), s / 2f, Path.Direction.CW)
            "heart" -> heart(this, sq)
            "star" -> star(this, sq, 5, 0.48f)
            "sparkle" -> star(this, sq, 4, 0.28f)
            "square" -> addRoundRect(sq, s * 0.18f, s * 0.18f, Path.Direction.CW)
            "hexagon" -> polygon(this, sq, 6)
            "flower" -> flower(this, sq)
            "moon" -> moon(this, sq)
            "bubble" -> bubble(this, sq)
            "arrow" -> arrow(this, sq)
            "drop" -> drop(this, sq)
            else -> if (cornerRadius > 0f) {
                addRoundRect(r, cornerRadius, cornerRadius, Path.Direction.CW)
            } else {
                addRect(r, Path.Direction.CW)
            }
        }
    }

    private inline fun mapper(sq: RectF, crossinline f: (Float, Float) -> Unit) =
        { u: Float, v: Float -> f(sq.left + u * sq.width(), sq.top + v * sq.height()) }

    private fun heart(p: Path, sq: RectF) {
        fun x(u: Float) = sq.left + u * sq.width()
        fun y(v: Float) = sq.top + v * sq.height()
        p.moveTo(x(0.5f), y(0.25f))
        p.cubicTo(x(0.5f), y(0.22f), x(0.45f), y(0.05f), x(0.25f), y(0.05f))
        p.cubicTo(x(0.0f), y(0.05f), x(0.0f), y(0.35f), x(0.0f), y(0.35f))
        p.cubicTo(x(0.0f), y(0.55f), x(0.2f), y(0.77f), x(0.5f), y(0.95f))
        p.cubicTo(x(0.8f), y(0.77f), x(1.0f), y(0.55f), x(1.0f), y(0.35f))
        p.cubicTo(x(1.0f), y(0.35f), x(1.0f), y(0.05f), x(0.75f), y(0.05f))
        p.cubicTo(x(0.6f), y(0.05f), x(0.5f), y(0.22f), x(0.5f), y(0.25f))
        p.close()
    }

    private fun star(p: Path, sq: RectF, points: Int, innerRatio: Float) {
        val outer = sq.width() / 2f
        val inner = outer * innerRatio
        val cx = sq.centerX()
        val cy = sq.centerY() + if (points == 5) outer * 0.06f else 0f // optical centring
        for (i in 0 until points * 2) {
            val radius = if (i % 2 == 0) outer else inner
            val angle = -PI / 2 + i * PI / points
            val px = cx + (radius * cos(angle)).toFloat()
            val py = cy + (radius * sin(angle)).toFloat()
            if (i == 0) p.moveTo(px, py) else p.lineTo(px, py)
        }
        p.close()
    }

    private fun polygon(p: Path, sq: RectF, sides: Int) {
        val radius = sq.width() / 2f
        for (i in 0 until sides) {
            val angle = -PI / 2 + i * 2 * PI / sides
            val px = sq.centerX() + (radius * cos(angle)).toFloat()
            val py = sq.centerY() + (radius * sin(angle)).toFloat()
            if (i == 0) p.moveTo(px, py) else p.lineTo(px, py)
        }
        p.close()
    }

    private fun flower(p: Path, sq: RectF) {
        val r = sq.width() / 2f
        val petal = r * 0.42f
        val dist = r - petal
        for (i in 0 until 6) {
            val a = -PI / 2 + i * PI / 3
            p.addCircle(sq.centerX() + (dist * cos(a)).toFloat(), sq.centerY() + (dist * sin(a)).toFloat(), petal, Path.Direction.CW)
        }
        p.addCircle(sq.centerX(), sq.centerY(), dist, Path.Direction.CW)
    }

    private fun moon(p: Path, sq: RectF) {
        val r = sq.width() / 2f
        val full = Path().apply { addCircle(sq.centerX(), sq.centerY(), r, Path.Direction.CW) }
        val cut = Path().apply { addCircle(sq.centerX() + r * 0.45f, sq.centerY() - r * 0.2f, r * 0.85f, Path.Direction.CW) }
        p.op(full, cut, Path.Op.DIFFERENCE)
    }

    private fun bubble(p: Path, sq: RectF) {
        val w = sq.width()
        val body = RectF(sq.left, sq.top + w * 0.08f, sq.right, sq.top + w * 0.72f)
        p.addRoundRect(body, w * 0.18f, w * 0.18f, Path.Direction.CW)
        val tail = Path().apply {
            moveTo(sq.left + w * 0.22f, body.bottom - 2f)
            lineTo(sq.left + w * 0.14f, sq.top + w * 0.95f)
            lineTo(sq.left + w * 0.45f, body.bottom - 2f)
            close()
        }
        p.op(tail, Path.Op.UNION)
    }

    private fun arrow(p: Path, sq: RectF) {
        val m = mapper(sq) { x, y -> if (p.isEmpty) p.moveTo(x, y) else p.lineTo(x, y) }
        m(0.05f, 0.38f); m(0.55f, 0.38f); m(0.55f, 0.15f); m(0.95f, 0.5f)
        m(0.55f, 0.85f); m(0.55f, 0.62f); m(0.05f, 0.62f)
        p.close()
    }

    private fun drop(p: Path, sq: RectF) {
        fun x(u: Float) = sq.left + u * sq.width()
        fun y(v: Float) = sq.top + v * sq.height()
        p.moveTo(x(0.5f), y(0.02f))
        p.cubicTo(x(0.5f), y(0.02f), x(0.15f), y(0.45f), x(0.15f), y(0.63f))
        p.cubicTo(x(0.15f), y(0.83f), x(0.31f), y(0.98f), x(0.5f), y(0.98f))
        p.cubicTo(x(0.69f), y(0.98f), x(0.85f), y(0.83f), x(0.85f), y(0.63f))
        p.cubicTo(x(0.85f), y(0.45f), x(0.5f), y(0.02f), x(0.5f), y(0.02f))
        p.close()
    }
}
