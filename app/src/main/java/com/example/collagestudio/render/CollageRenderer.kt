package com.example.collagestudio.render

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.example.collagestudio.model.CellSpec
import com.example.collagestudio.model.CellTransform
import com.example.collagestudio.model.EditorDoc
import com.example.collagestudio.model.Overlay
import com.example.collagestudio.model.OverlayKind
import com.example.collagestudio.model.Selection
import com.example.collagestudio.model.Stroke
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

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
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val vignettePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val tmpRect = RectF()

    fun draw(
        canvas: Canvas,
        w: Float,
        h: Float,
        doc: EditorDoc,
        photos: List<Bitmap>,
        vignettes: FloatArray,
        selection: Selection,
        liveStroke: Stroke? = null,
    ) {
        canvas.drawColor(doc.style.background)
        val gap = doc.style.gap * w
        val radius = doc.style.radius * w
        selectionPaint.strokeWidth = w * 0.006f

        doc.template.cells.forEachIndexed { i, cell ->
            val r = cellRect(cell, w, h, gap)
            val clip = Masks.path(cell.mask, r, radius)
            if (photos.isNotEmpty()) {
                val p = i % photos.size
                canvas.save()
                canvas.clipPath(clip)
                drawPhoto(canvas, photos[p], r, doc.transforms.getOrElse(i) { CellTransform() })
                val v = vignettes.getOrElse(p) { 0f }
                if (v > 0f) drawVignette(canvas, r, v)
                canvas.restore()
            }
            if (selection == Selection.Cell(i)) canvas.drawPath(clip, selectionPaint)
        }

        doc.strokes.forEach { drawStroke(canvas, it, w, h) }
        liveStroke?.let { drawStroke(canvas, it, w, h) }

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

    private fun drawVignette(canvas: Canvas, r: RectF, amount: Float) {
        val radius = max(r.width(), r.height()) * 0.78f
        vignettePaint.shader = RadialGradient(
            r.centerX(), r.centerY(), radius,
            intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Color.argb((amount * 220).toInt(), 0, 0, 0)),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(r, vignettePaint)
        vignettePaint.shader = null
    }

    private fun drawStroke(canvas: Canvas, s: Stroke, w: Float, h: Float) {
        val pts = s.points
        if (pts.size < 2) return
        val width = s.width * w
        val path = Path()
        path.moveTo(pts[0] * w, pts[1] * h)
        if (pts.size == 2) {
            path.lineTo(pts[0] * w + 0.1f, pts[1] * h)
        } else {
            var i = 2
            while (i < pts.size - 2) {
                val x = pts[i] * w
                val y = pts[i + 1] * h
                val nx = pts[i + 2] * w
                val ny = pts[i + 3] * h
                path.quadTo(x, y, (x + nx) / 2f, (y + ny) / 2f)
                i += 2
            }
            path.lineTo(pts[pts.size - 2] * w, pts[pts.size - 1] * h)
        }
        if (s.neon) {
            strokePaint.color = s.color
            strokePaint.strokeWidth = width * 2.2f
            strokePaint.maskFilter = BlurMaskFilter(width * 1.3f, BlurMaskFilter.Blur.NORMAL)
            canvas.drawPath(path, strokePaint)
            strokePaint.maskFilter = null
            strokePaint.strokeWidth = width * 0.9f
            canvas.drawPath(path, strokePaint)
            strokePaint.color = Color.WHITE
            strokePaint.strokeWidth = width * 0.35f
            canvas.drawPath(path, strokePaint)
        } else {
            strokePaint.color = s.color
            strokePaint.strokeWidth = width
            canvas.drawPath(path, strokePaint)
        }
    }

    // ---- Overlays ----

    private fun drawOverlay(canvas: Canvas, o: Overlay, w: Float, h: Float, selected: Boolean) {
        val cx = o.cx * w
        val cy = o.cy * h
        val (ow, oh) = overlaySize(o, w)
        val bounds = RectF(cx - ow / 2f, cy - oh / 2f, cx + ow / 2f, cy + oh / 2f)

        canvas.save()
        canvas.rotate(o.rotation, cx, cy)
        if (o.flip) canvas.scale(-1f, 1f, cx, cy)
        val layered = o.opacity < 1f
        if (layered) {
            val glow = w * 0.05f
            canvas.saveLayerAlpha(bounds.left - glow, bounds.top - glow, bounds.right + glow, bounds.bottom + glow, (o.opacity * 255).toInt())
        }

        when (val k = o.kind) {
            is OverlayKind.Sticker -> {
                setupEmoji(o, w)
                val fm = textPaint.fontMetrics
                textPaint.textAlign = Paint.Align.CENTER
                canvas.drawText(k.emoji, cx, cy - (fm.ascent + fm.descent) / 2f, textPaint)
                textPaint.textAlign = Paint.Align.LEFT
            }
            is OverlayKind.Shape -> {
                fillPaint.color = k.color
                fillPaint.style = Paint.Style.FILL
                canvas.drawPath(Masks.path(k.shape, bounds, 0f), fillPaint)
            }
            is OverlayKind.Text -> drawText(canvas, k, o.scale, w, cx, cy, bounds)
        }

        if (layered) canvas.restore()
        if (selected) {
            val pad = w * 0.012f
            tmpRect.set(bounds.left - pad, bounds.top - pad, bounds.right + pad, bounds.bottom + pad)
            canvas.drawRoundRect(tmpRect, pad, pad, selectionPaint)
        }
        canvas.restore()
    }

    private fun drawText(canvas: Canvas, k: OverlayKind.Text, scale: Float, w: Float, cx: Float, cy: Float, bounds: RectF) {
        val layout = textLayout(k, scale, w)
        val st = k.style
        val size = textPaint.textSize
        if (st.background) {
            fillPaint.style = Paint.Style.FILL
            fillPaint.color = st.backgroundColor
            fillPaint.alpha = (st.backgroundOpacity * 255).toInt()
            canvas.drawRoundRect(bounds, size * 0.28f, size * 0.28f, fillPaint)
        }
        canvas.save()
        canvas.translate(cx - layout.width / 2f, cy - layout.height / 2f)
        if (st.stroke > 0f) {
            textPaint.style = Paint.Style.STROKE
            textPaint.strokeJoin = Paint.Join.ROUND
            textPaint.strokeWidth = size * st.stroke * 0.22f
            textPaint.color = st.strokeColor
            layout.draw(canvas)
        }
        textPaint.style = Paint.Style.FILL
        textPaint.color = st.color
        if (st.shadow) textPaint.setShadowLayer(size * 0.08f, 0f, size * 0.04f, 0x99000000.toInt())
        layout.draw(canvas)
        textPaint.clearShadowLayer()
        canvas.restore()
    }

    private fun setupEmoji(o: Overlay, w: Float) {
        textPaint.typeface = Typeface.DEFAULT
        textPaint.textSize = w * 0.16f * o.scale
        textPaint.letterSpacing = 0f
        textPaint.style = Paint.Style.FILL
        textPaint.color = Color.BLACK
        textPaint.clearShadowLayer()
    }

    private fun textLayout(k: OverlayKind.Text, scale: Float, w: Float): StaticLayout {
        val st = k.style
        textPaint.typeface = Fonts.get(st.font)
        textPaint.textSize = w * 0.075f * scale
        textPaint.letterSpacing = st.letterSpacing
        textPaint.style = Paint.Style.FILL
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.clearShadowLayer()
        val text = k.text.ifEmpty { " " }
        val widest = text.split('\n').maxOf { textPaint.measureText(it) }
        val width = ceil(widest + textPaint.textSize * 0.15f).toInt().coerceAtLeast(1)
        val align = when (st.align) {
            0 -> Layout.Alignment.ALIGN_NORMAL
            2 -> Layout.Alignment.ALIGN_OPPOSITE
            else -> Layout.Alignment.ALIGN_CENTER
        }
        return StaticLayout.Builder.obtain(text, 0, text.length, textPaint, width)
            .setAlignment(align)
            .setIncludePad(false)
            .build()
    }

    /** Width and height of an overlay in pixels, before rotation. */
    fun overlaySize(o: Overlay, w: Float): Pair<Float, Float> = when (val k = o.kind) {
        is OverlayKind.Sticker -> {
            setupEmoji(o, w)
            textPaint.measureText(k.emoji) to textPaint.textSize * 1.15f
        }
        is OverlayKind.Shape -> {
            val s = w * 0.28f * o.scale
            s to s
        }
        is OverlayKind.Text -> {
            val layout = textLayout(k, o.scale, w)
            val pad = textPaint.textSize * 0.35f
            (layout.width + pad * 2f) to (layout.height + pad)
        }
    }

    /** Finds what is under a touch: overlays first (they are on top), then cells. */
    fun hitTest(doc: EditorDoc, w: Float, h: Float, x: Float, y: Float): Selection {
        doc.overlays.asReversed().forEach { o ->
            val cx = o.cx * w
            val cy = o.cy * h
            // Rotate the touch point into the overlay's own frame.
            val a = Math.toRadians(-o.rotation.toDouble())
            val dx = x - cx
            val dy = y - cy
            val lx = (dx * cos(a) - dy * sin(a)).toFloat()
            val ly = (dx * sin(a) + dy * cos(a)).toFloat()
            val (ow, oh) = overlaySize(o, w)
            val slop = w * 0.02f
            if (lx in -(ow / 2f + slop)..(ow / 2f + slop) && ly in -(oh / 2f + slop)..(oh / 2f + slop)) {
                return Selection.Item(o.id)
            }
        }
        val gap = doc.style.gap * w
        doc.template.cells.indices.reversed().forEach { i ->
            if (cellRect(doc.template.cells[i], w, h, gap).contains(x, y)) return Selection.Cell(i)
        }
        return Selection.None
    }
}
