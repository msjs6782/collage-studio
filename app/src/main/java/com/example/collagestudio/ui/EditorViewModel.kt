package com.example.collagestudio.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.collagestudio.R
import com.example.collagestudio.data.AssetRepository
import com.example.collagestudio.filter.ImageFilter
import com.example.collagestudio.filter.ToneCurve
import com.example.collagestudio.image.ImageIO
import com.example.collagestudio.model.CellTransform
import com.example.collagestudio.model.CollageStyle
import com.example.collagestudio.model.EditorDoc
import com.example.collagestudio.model.FilterSpec
import com.example.collagestudio.model.Selection
import com.example.collagestudio.model.StickerOverlay
import com.example.collagestudio.model.Template
import com.example.collagestudio.model.TextOverlay
import com.example.collagestudio.model.transformed
import com.example.collagestudio.render.CollageRenderer
import com.example.collagestudio.render.cellRect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class EditorViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = AssetRepository(app.assets)
    val templates: List<Template> = repo.templates()
    val filters: List<FilterSpec> = repo.filters()
    private val curveCache = HashMap<String, ToneCurve>()

    /** Renderer for the on-screen preview (main thread only). */
    val renderer = CollageRenderer()

    private var originals: List<Bitmap> = emptyList()

    /** Photos with the current filter applied; this is what gets drawn. */
    var photos by mutableStateOf<List<Bitmap>>(emptyList())
        private set
    var doc by mutableStateOf<EditorDoc?>(null)
        private set
    var selection by mutableStateOf<Selection>(Selection.None)
        private set
    var filterId by mutableStateOf("none")
        private set
    var intensity by mutableFloatStateOf(1f)
        private set
    var filterThumbs by mutableStateOf<Map<String, Bitmap>>(emptyMap())
        private set
    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
        private set

    private var filterJob: Job? = null
    private var nextId = 1L

    // ---- Opening / closing ----

    fun open(uris: List<Uri>) {
        if (uris.isEmpty() || busy) return
        viewModelScope.launch {
            busy = true
            try {
                val resolver = getApplication<Application>().contentResolver
                val bitmaps = withContext(Dispatchers.IO) { uris.map { ImageIO.load(resolver, it) } }
                originals = bitmaps
                photos = bitmaps
                filterId = "none"
                intensity = 1f
                selection = Selection.None
                val template = templates.firstOrNull { it.cells.size == bitmaps.size } ?: templates.first()
                doc = EditorDoc(template, List(template.cells.size) { CellTransform() })
                buildFilterThumbs(bitmaps.first())
            } catch (e: Exception) {
                message = getApplication<Application>().getString(R.string.load_failed)
            } finally {
                busy = false
            }
        }
    }

    fun close() {
        filterJob?.cancel()
        doc = null
        originals = emptyList()
        photos = emptyList()
        filterThumbs = emptyMap()
        selection = Selection.None
    }

    fun clearMessage() {
        message = null
    }

    // ---- Layout & style ----

    fun setTemplate(t: Template) {
        val d = doc ?: return
        doc = d.copy(template = t, transforms = List(t.cells.size) { CellTransform() })
        selection = Selection.None
    }

    fun setBackground(color: Int) = updateStyle { it.copy(background = color) }
    fun setGap(value: Float) = updateStyle { it.copy(gap = value) }
    fun setRadius(value: Float) = updateStyle { it.copy(radius = value) }

    private fun updateStyle(change: (CollageStyle) -> CollageStyle) {
        val d = doc ?: return
        doc = d.copy(style = change(d.style))
    }

    // ---- Filters ----

    fun setFilter(id: String) {
        filterId = id
        intensity = 1f
        applyFilter()
    }

    fun updateIntensity(value: Float) {
        intensity = value
        applyFilter()
    }

    private fun curveFor(spec: FilterSpec): ToneCurve {
        val path = spec.acv ?: return ToneCurve.IDENTITY
        return synchronized(curveCache) { curveCache.getOrPut(path) { repo.curve(path) } }
    }

    private fun applyFilter() {
        filterJob?.cancel()
        val spec = filters.first { it.id == filterId }
        val src = originals
        val t = intensity
        filterJob = viewModelScope.launch {
            val out = withContext(Dispatchers.Default) {
                if (spec.acv == null && spec.saturation == 1f) {
                    src
                } else {
                    val curve = curveFor(spec)
                    src.map {
                        ensureActive()
                        ImageFilter.apply(it, curve, t, spec.saturation)
                    }
                }
            }
            photos = out
        }
    }

    private fun buildFilterThumbs(first: Bitmap) {
        viewModelScope.launch {
            filterThumbs = withContext(Dispatchers.Default) {
                val base = ImageIO.thumbnail(first, 160)
                filters.associate { it.id to ImageFilter.apply(base, curveFor(it), 1f, it.saturation) }
            }
        }
    }

    // ---- Touch ----

    fun tap(x: Float, y: Float, w: Float, h: Float) {
        val d = doc ?: return
        selection = renderer.hitTest(d, w, h, x, y)
    }

    /** Pinch / drag. If nothing is selected yet, the gesture selects what is under the fingers. */
    fun transform(cx: Float, cy: Float, panX: Float, panY: Float, zoom: Float, rotation: Float, w: Float, h: Float) {
        val d = doc ?: return
        if (selection == Selection.None) selection = renderer.hitTest(d, w, h, cx, cy)
        when (val s = selection) {
            is Selection.Cell -> {
                val r = cellRect(d.template.cells[s.index], w, h, d.style.gap * w)
                val t = d.transforms[s.index]
                val nt = t.copy(
                    scale = (t.scale * zoom).coerceIn(1f, 5f),
                    dx = (t.dx + panX / r.width()).coerceIn(-1f, 1f),
                    dy = (t.dy + panY / r.height()).coerceIn(-1f, 1f),
                )
                doc = d.copy(transforms = d.transforms.toMutableList().also { it[s.index] = nt })
            }
            is Selection.Item -> doc = d.copy(
                overlays = d.overlays.map { if (it.id == s.id) it.transformed(panX / w, panY / h, zoom, rotation) else it },
            )
            Selection.None -> Unit
        }
    }

    // ---- Stickers & text ----

    fun addSticker(emoji: String) {
        val d = doc ?: return
        val o = StickerOverlay(id = nextId++, emoji = emoji)
        doc = d.copy(overlays = d.overlays + o)
        selection = Selection.Item(o.id)
    }

    fun addText(text: String, color: Int) {
        val d = doc ?: return
        if (text.isBlank()) return
        val o = TextOverlay(id = nextId++, text = text, color = color, cy = 0.85f)
        doc = d.copy(overlays = d.overlays + o)
        selection = Selection.Item(o.id)
    }

    /** Recolours the selected text, if a text overlay is selected. */
    fun setTextColor(color: Int) {
        val d = doc ?: return
        val s = selection as? Selection.Item ?: return
        doc = d.copy(overlays = d.overlays.map { if (it.id == s.id && it is TextOverlay) it.copy(color = color) else it })
    }

    fun deleteSelected() {
        val d = doc ?: return
        val s = selection as? Selection.Item ?: return
        doc = d.copy(overlays = d.overlays.filterNot { it.id == s.id })
        selection = Selection.None
    }

    // ---- Export ----

    fun export(onSaved: (Uri) -> Unit) {
        val d = doc ?: return
        if (busy) return
        val currentPhotos = photos
        viewModelScope.launch {
            busy = true
            try {
                val app = getApplication<Application>()
                val uri = withContext(Dispatchers.IO) {
                    val w = 2000
                    val h = (w / d.template.aspect).roundToInt()
                    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    // A separate renderer: Paint objects must not be shared across threads.
                    CollageRenderer().draw(Canvas(out), w.toFloat(), h.toFloat(), d, currentPhotos, Selection.None)
                    ImageIO.saveJpeg(app, out).also { out.recycle() }
                }
                onSaved(uri)
            } catch (e: Exception) {
                message = getApplication<Application>().getString(R.string.save_failed)
            } finally {
                busy = false
            }
        }
    }
}
