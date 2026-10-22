package com.example.collagestudio.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import com.example.collagestudio.model.CellSpec
import com.example.collagestudio.model.CellTransform
import com.example.collagestudio.model.EditorDoc
import com.example.collagestudio.model.Overlay
import com.example.collagestudio.model.Selection
import com.example.collagestudio.model.StickerOverlay
import com.example.collagestudio.model.TextOverlay
import kotlin.math.max

/**
 * Pixel rectangle of a cell. Half a gap of outer padding + half a gap of inset per cell gives the
 * same spacing between cells and around the edge.
 */
fun cellRect(cell: CellSpec, w: Float, h: Float, gapPx: Float): RectF {
    val half = gapPx / 2f
    val iw = w - gapPx
    val ih = h - gapPx
    return RectF(
        half + cell.x * iw + half,
        half + cell.y * ih + half,
        half + (cell.x + cell.w) * iw - half,
        half + (cell.y + cell.h) * ih - half,
    )
}

/**
 * Draws a collage onto any android.graphics.Canvas. The same code draws the on-screen preview and
 * the saved full-size image, so what you see is exactly what you save.
 *
 * Not thread-safe (it reuses Paint objects): use one instance per thread.
 */
class CollageRenderer {
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFF2F80ED.toInt()
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val tmpRect = RectF()

    fun draw(
        canvas: Canvas,
        w: Float,
        h: Float,
        doc: EditorDoc,
        photos: List<Bitmap>,
        selection: Selection,
    ) {
        canvas.drawColor(doc.style.background)
        val gap = doc.style.gap * w
        val radius = doc.style.radius * w
        selectionPaint.strokeWidth = w * 0.006f

        doc.template.cells.forEachIndexed { i, cell ->
            val r = cellRect(cell, w, h, gap)
            val clip = Masks.path(cell.mask, r, radius)
            val bmp = if (photos.isEmpty()) null else photos[i % photos.size]
            if (bmp != null) {
                canvas.save()
                canvas.clipPath(clip)
                drawPhoto(canvas, bmp, r, doc.transforms.getOrElse(i) { CellTransform() })
                canvas.restore()
            }
            if (selection == Selection.Cell(i)) canvas.drawPath(clip, selectionPaint)
        }

        doc.overlays.forEach { o -> drawOverlay(canvas, o, w, h, selection == Selection.Item(o.id)) }
    }

    /** Centre-crop the photo into the cell, then apply the user's zoom and offset. */
    private fun drawPhoto(canvas: Canvas, bmp: Bitmap, r: RectF, t: CellTransform) {
        val base = max(r.width() / bmp.width, r.height() / bmp.height) * t.scale
        val dw = bmp.width * base
        val dh = bmp.height * base
        val cx = r.centerX() + t.dx * r.width()
        val cy = r.centerY() + t.dy * r.height()
        tmpRect.set(cx - dw / 2f, cy - dh / 2f, cx + dw / 2f, cy + dh / 2f)
        canvas.drawBitmap(bmp, null, tmpRect, bitmapPaint)
    }

    private fun drawOverlay(canvas: Canvas, o: Overlay, w: Float, h: Float, selected: Boolean) {
        val label = configurePaint(o, w)
        val cx = o.cx * w
        val cy = o.cy * h
        canvas.save()
        canvas.rotate(o.rotation, cx, cy)
        val fm = textPaint.fontMetrics
        canvas.drawText(label, cx, cy - (fm.ascent + fm.descent) / 2f, textPaint)
        if (selected) {
            textPaint.clearShadowLayer()
            val b = overlayBounds(o, w, h)
            val pad = w * 0.01f
            b.inset(-pad, -pad)
            canvas.drawRoundRect(b, pad, pad, selectionPaint)
        }
        canvas.restore()
    }

    /** Unrotated bounds of an overlay, in pixels. Used for hit-testing and the selection frame. */
    fun overlayBounds(o: Overlay, w: Float, h: Float): RectF {
        val label = configurePaint(o, w)
        val tw = textPaint.measureText(label)
        val th = textPaint.textSize
        val cx = o.cx * w
        val cy = o.cy * h
        return RectF(cx - tw / 2f, cy - th * 0.6f, cx + tw / 2f, cy + th * 0.6f)
    }

    /** Finds what is under a touch: overlays first (they are on top), then cells. */
    fun hitTest(doc: EditorDoc, w: Float, h: Float, x: Float, y: Float): Selection {
        doc.overlays.asReversed().forEach { o ->
            val b = overlayBounds(o, w, h)
            b.inset(-w * 0.02f, -w * 0.02f)
            if (b.contains(x, y)) return Selection.Item(o.id)
        }
        val gap = doc.style.gap * w
        doc.template.cells.indices.reversed().forEach { i ->
            if (cellRect(doc.template.cells[i], w, h, gap).contains(x, y)) return Selection.Cell(i)
        }
        return Selection.None
    }

    private fun configurePaint(o: Overlay, w: Float): String = when (o) {
        is StickerOverlay -> {
            textPaint.typeface = Typeface.DEFAULT
            textPaint.textSize = w * 0.16f * o.scale
            textPaint.color = 0xFF000000.toInt()
            textPaint.clearShadowLayer()
            o.emoji
        }
        is TextOverlay -> {
            textPaint.typeface = Typeface.DEFAULT_BOLD
            textPaint.textSize = w * 0.075f * o.scale
            textPaint.color = o.color
            textPaint.setShadowLayer(w * 0.006f, 0f, w * 0.002f, 0x88000000.toInt())
            o.text
        }
    }
}
