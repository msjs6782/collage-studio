package com.example.collagestudio.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.net.Uri
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.collagestudio.R
import com.example.collagestudio.data.AssetRepository
import com.example.collagestudio.data.ProjectStore
import com.example.collagestudio.filter.PhotoProcessor
import com.example.collagestudio.filter.ToneCurve
import com.example.collagestudio.image.ImageIO
import com.example.collagestudio.model.Adjustments
import com.example.collagestudio.model.CellTransform
import com.example.collagestudio.model.CollageStyle
import com.example.collagestudio.model.CropRect
import com.example.collagestudio.model.Curves
import com.example.collagestudio.model.EditorDoc
import com.example.collagestudio.model.FilterSpec
import com.example.collagestudio.model.FocusBlur
import com.example.collagestudio.model.FontSpec
import com.example.collagestudio.model.LocalStroke
import com.example.collagestudio.model.Overlay
import com.example.collagestudio.model.OverlayKind
import com.example.collagestudio.model.PhotoEdit
import com.example.collagestudio.model.Selection
import com.example.collagestudio.model.SplitTone
import com.example.collagestudio.model.Stroke
import com.example.collagestudio.model.Template
import com.example.collagestudio.model.TextStyle
import com.example.collagestudio.model.transformed
import com.example.collagestudio.render.CollageRenderer
import com.example.collagestudio.render.Fonts
import com.example.collagestudio.render.cellRect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class Brush(val color: Int = 0xFFE5486B.toInt(), val width: Float = 0.012f, val neon: Boolean = false)

/** Full-screen photo tools. */
enum class PhotoMode { Crop, Focus, Local }

/** An open full-screen photo tool; [snapshot] is restored on cancel. */
data class PhotoToolState(val mode: PhotoMode, val index: Int, val snapshot: PhotoEdit)

class EditorViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = AssetRepository(app.assets)
    val templates: List<Template> = repo.templates()
    val filters: List<FilterSpec> = repo.filters()
    val fonts: List<FontSpec> = repo.fonts().also { Fonts.init(app.assets, it) }
    private val filterById = filters.associateBy { it.id }
    private val curveCache = HashMap<String, ToneCurve>()

    /** Renderer for the on-screen preview (main thread only). */
    val renderer = CollageRenderer()

    /** Full-resolution sources (longest side ≤ 1600 px), used for saving. */
    private var originals: List<Bitmap> = emptyList()

    /** Smaller copies used for the live preview, so sliders stay smooth. */
    var previews: List<Bitmap> = emptyList()
        private set

    /** Preview photos with each photo's edit applied; this is what gets drawn on screen. */
    var photos by mutableStateOf<List<Bitmap>>(emptyList())
        private set
    var doc by mutableStateOf<EditorDoc?>(null)
        private set
    var selection by mutableStateOf<Selection>(Selection.None)
        private set
    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    /** While true the canvas shows the photos without colour edits ("before"). */
    var compare by mutableStateOf(false)
    private var beforeCache: Pair<List<PhotoEdit>, List<Bitmap>>? = null

    /** When true, finger drags on the canvas paint brush strokes instead of moving things. */
    var drawMode by mutableStateOf(false)
    var brush by mutableStateOf(Brush())
    var liveStroke by mutableStateOf<Stroke?>(null)
        private set

    /** Style used for the next new text. */
    var textStyle by mutableStateOf(TextStyle())

    var photoTool by mutableStateOf<PhotoToolState?>(null)
        private set

    var hasProject by mutableStateOf(false)
        private set
    var projectThumb by mutableStateOf<Bitmap?>(null)
        private set

    var filterThumbs by mutableStateOf<Map<String, Bitmap>>(emptyMap())
        private set
    private var thumbsKey: Pair<Int, PhotoEdit>? = null

    private val photoJobs = HashMap<Int, Job>()
    private var nextId = 1L

    init {
        refreshProjectInfo()
    }

    // ---- Undo / redo ----

    private val undoStack = ArrayDeque<EditorDoc>()
    private val redoStack = ArrayDeque<EditorDoc>()
    private var lastKey: String? = null
    private var lastTime = 0L

    /**
     * The single entry point for document changes. Rapid changes with the same [key] (a slider
     * drag, a pinch) are merged into one undo step.
     */
    private fun commit(key: String, change: (EditorDoc) -> EditorDoc) {
        val old = doc ?: return
        val new = change(old)
        if (new == old) return
        val now = SystemClock.uptimeMillis()
        if (key != lastKey || now - lastTime > 900) {
            undoStack.addLast(old)
            if (undoStack.size > 60) undoStack.removeFirst()
            redoStack.clear()
        }
        lastKey = key
        lastTime = now
        setDoc(old, new)
    }

    private fun setDoc(old: EditorDoc?, new: EditorDoc) {
        doc = new
        canUndo = undoStack.isNotEmpty()
        canRedo = redoStack.isNotEmpty()
        new.edits.indices.forEach { i -> if (old == null || old.edits.getOrNull(i) != new.edits[i]) refresh(i) }
        val s = selection
        if (s is Selection.Item && new.overlays.none { it.id == s.id }) selection = Selection.None
        if (s is Selection.Cell && s.index >= new.template.cells.size) selection = Selection.None
    }

    fun undo() {
        val current = doc ?: return
        val prev = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(current)
        lastKey = null
        setDoc(current, prev)
    }

    fun redo() {
        val current = doc ?: return
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(current)
        lastKey = null
        setDoc(current, next)
    }

    // ---- Opening / closing / project ----

    fun open(uris: List<Uri>) {
        if (uris.isEmpty() || busy) return
        viewModelScope.launch {
            busy = true
            try {
                val app = getApplication<Application>()
                val loaded = withContext(Dispatchers.IO) {
                    val bitmaps = uris.map { ImageIO.load(app.contentResolver, it) }
                    ProjectStore.savePhotos(app, bitmaps)
                    bitmaps
                }
                val template = if (loaded.size == 1) {
                    templates.first { it.id == "crop_original" }
                } else {
                    templates.firstOrNull { it.group == "layout" && it.cells.size == loaded.size } ?: templates.first()
                }
                start(
                    loaded,
                    EditorDoc(
                        template = template,
                        transforms = List(template.cells.size) { CellTransform() },
                        edits = List(loaded.size) { PhotoEdit() },
                    ),
                )
                saveProject()
            } catch (e: Exception) {
                message = getApplication<Application>().getString(R.string.load_failed)
            } finally {
                busy = false
            }
        }
    }

    /** Re-opens the last saved project. */
    fun resume() {
        if (busy) return
        viewModelScope.launch {
            busy = true
            try {
                val app = getApplication<Application>()
                val stored = withContext(Dispatchers.IO) { ProjectStore.load(app) }
                if (stored == null) {
                    message = app.getString(R.string.load_failed)
                    hasProject = false
                } else {
                    start(stored.second, stored.first)
                }
            } finally {
                busy = false
            }
        }
    }

    private suspend fun start(sources: List<Bitmap>, d: EditorDoc) {
        originals = sources
        previews = withContext(Dispatchers.Default) { sources.map { ImageIO.thumbnail(it, 1080) } }
        photos = previews
        selection = Selection.None
        undoStack.clear()
        redoStack.clear()
        lastKey = null
        thumbsKey = null
        beforeCache = null
        nextId = (d.overlays.maxOfOrNull { it.id } ?: 0L) + 1
        setDoc(null, d)
    }

    fun close() {
        saveProject()
        photoJobs.values.forEach { it.cancel() }
        photoJobs.clear()
        doc = null
        photoTool = null
        originals = emptyList()
        previews = emptyList()
        photos = emptyList()
        filterThumbs = emptyMap()
        selection = Selection.None
        drawMode = false
        compare = false
        beforeCache = null
        undoStack.clear()
        redoStack.clear()
        canUndo = false
        canRedo = false
    }

    /** Saves the document and a thumbnail in the background. Safe to call often. */
    fun saveProject() {
        val d = doc ?: return
        val shown = photos
        val aspect = canvasAspect(d)
        val vignettes = vignettes(d)
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO + NonCancellable) {
            runCatching {
                val w = 420
                val h = max(1, (w / aspect).roundToInt())
                val thumb = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                CollageRenderer().draw(Canvas(thumb), w.toFloat(), h.toFloat(), d, shown, vignettes, Selection.None)
                ProjectStore.saveDoc(app, d, thumb)
            }
            refreshProjectInfo()
        }
    }

    private fun refreshProjectInfo() {
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            val exists = ProjectStore.exists(app)
            val thumb = if (exists) ProjectStore.thumbnail(app) else null
            withContext(Dispatchers.Main) {
                hasProject = exists
                projectThumb = thumb
            }
        }
    }

    fun clearMessage() {
        message = null
    }

    /** width / height of the canvas. Crop "original" follows the first photo. */
    fun canvasAspect(d: EditorDoc): Float {
        if (d.template.aspect > 0f) return d.template.aspect
        val p = photos.firstOrNull() ?: return 1f
        return p.width.toFloat() / p.height
    }

    fun vignettes(d: EditorDoc): FloatArray =
        FloatArray(d.edits.size) { PhotoProcessor.vignette(d.edits[it], filterOf(d.edits[it].filterId)) }

    /** Photos with only their geometry applied, for the before/after comparison. */
    fun beforePhotos(): List<Bitmap> {
        val d = doc ?: return photos
        val geo = d.edits.map { it.geometryOnly() }
        beforeCache?.let { (key, bitmaps) -> if (key == geo) return bitmaps }
        val bitmaps = previews.mapIndexed { i, b -> PhotoProcessor.applyGeometry(b, geo[i]) }
        beforeCache = geo to bitmaps
        return bitmaps
    }

    // ---- Layout & style ----

    fun setTemplate(t: Template) = commit("template") {
        it.copy(template = t, transforms = List(t.cells.size) { CellTransform() })
    }

    fun setBackground(color: Int) = updateStyle("bg") { it.copy(background = color) }
    fun setGap(value: Float) = updateStyle("gap") { it.copy(gap = value) }
    fun setRadius(value: Float) = updateStyle("radius") { it.copy(radius = value) }

    private fun updateStyle(key: String, change: (CollageStyle) -> CollageStyle) =
        commit(key) { it.copy(style = change(it.style)) }

    // ---- Photo edits ----

    /** The photo the photo tools act on: the open photo tool, the selected cell, the only photo, or null (= all). */
    val targetPhoto: Int?
        get() {
            photoTool?.let { return it.index }
            val d = doc ?: return null
            if (d.edits.size == 1) return 0
            val s = selection
            return if (s is Selection.Cell) s.index % d.edits.size else null
        }

    /** The edit shown in the photo tools (the target's, or the first photo's when editing all). */
    val currentEdit: PhotoEdit
        get() = doc?.edits?.getOrNull(targetPhoto ?: 0) ?: PhotoEdit()

    private fun editPhotos(key: String, change: (PhotoEdit) -> PhotoEdit) {
        val target = targetPhoto
        commit("$key:$target") { d ->
            d.copy(edits = d.edits.mapIndexed { i, e -> if (target == null || i == target) change(e) else e })
        }
    }

    private fun stamp() = SystemClock.uptimeMillis()

    fun setFilter(id: String) = editPhotos("filter") { it.copy(filterId = id, intensity = 1f) }
    fun setIntensity(v: Float) = editPhotos("intensity") { it.copy(intensity = v) }
    fun setAdjust(key: String, change: (Adjustments) -> Adjustments) = editPhotos("adj-$key") { it.copy(adjust = change(it.adjust)) }
    fun rotate() = editPhotos("rotate-${stamp()}") { it.copy(rotation = (it.rotation + 90) % 360, crop = CropRect()) }
    fun flipH() = editPhotos("flipH-${stamp()}") { it.copy(flipH = !it.flipH) }
    fun flipV() = editPhotos("flipV-${stamp()}") { it.copy(flipV = !it.flipV) }
    fun resetPhoto() = editPhotos("reset-${stamp()}") { PhotoEdit() }

    fun setCrop(rect: CropRect) = editPhotos("crop") { it.copy(crop = rect) }
    fun setStraighten(deg: Float) = editPhotos("straighten") { it.copy(straighten = deg) }
    fun setCurve(channel: Int, points: List<Float>) = editPhotos("curve-$channel") { it.copy(curves = it.curves.withChannel(channel, points)) }
    fun resetCurves() = editPhotos("curves-reset-${stamp()}") { it.copy(curves = Curves()) }
    fun setHsl(band: Int, component: Int, value: Float) = editPhotos("hsl-$band-$component") { e ->
        e.copy(hsl = e.hsl.toMutableList().also { it[band * 3 + component] = value })
    }
    fun resetHsl() = editPhotos("hsl-reset-${stamp()}") { it.copy(hsl = PhotoEdit().hsl) }
    fun setSplit(key: String, change: (SplitTone) -> SplitTone) = editPhotos("split-$key") { it.copy(split = change(it.split)) }
    fun setFocus(key: String, change: (FocusBlur) -> FocusBlur) = editPhotos("focus-$key") { it.copy(focus = change(it.focus)) }
    fun addLocalStroke(s: LocalStroke) = editPhotos("local-${stamp()}") { it.copy(local = it.local + s) }
    fun undoLocalStroke() = editPhotos("local-undo-${stamp()}") { it.copy(local = it.local.dropLast(1)) }
    fun clearLocal() = editPhotos("local-clear-${stamp()}") { it.copy(local = emptyList()) }

    fun applyFilterToAll() {
        val source = currentEdit
        commit("filter-all") { d -> d.copy(edits = d.edits.map { it.copy(filterId = source.filterId, intensity = source.intensity) }) }
    }

    fun applyAdjustToAll() {
        val source = currentEdit
        commit("adjust-all") { d ->
            d.copy(edits = d.edits.map { it.copy(adjust = source.adjust, curves = source.curves, hsl = source.hsl, split = source.split) })
        }
    }

    private fun filterOf(id: String): FilterSpec = filterById[id] ?: filters.first()

    private fun curveFor(spec: FilterSpec): ToneCurve {
        val path = spec.acv ?: return ToneCurve.IDENTITY
        return synchronized(curveCache) { curveCache.getOrPut(path) { repo.curve(path) } }
    }

    /** Re-processes one preview photo in the background; a newer request cancels the older one. */
    private fun refresh(i: Int) {
        val d = doc ?: return
        val src = previews.getOrNull(i) ?: return
        val edit = d.edits[i]
        photoJobs[i]?.cancel()
        photoJobs[i] = viewModelScope.launch {
            val spec = filterOf(edit.filterId)
            val out = withContext(Dispatchers.Default) { PhotoProcessor.process(src, edit, spec, curveFor(spec)) }
            if (doc?.edits?.getOrNull(i) == edit) {
                photos = photos.toMutableList().also { if (i < it.size) it[i] = out }
            }
        }
    }

    /** The photo with colour + effects but without straighten/crop, for the crop screen. */
    suspend fun uncroppedPreview(i: Int, edit: PhotoEdit): Bitmap? {
        val src = previews.getOrNull(i) ?: return null
        val spec = filterOf(edit.filterId)
        return withContext(Dispatchers.Default) {
            PhotoProcessor.process(src, edit.copy(straighten = 0f, crop = CropRect()), spec, curveFor(spec))
        }
    }

    /** Builds one small preview per filter for the photo the filter tools act on. */
    fun ensureFilterThumbs() {
        val d = doc ?: return
        val index = targetPhoto ?: 0
        val base = previews.getOrNull(index) ?: return
        val geometry = d.edits[index].geometryOnly()
        val key = index to geometry
        if (key == thumbsKey) return
        thumbsKey = key
        viewModelScope.launch {
            filterThumbs = withContext(Dispatchers.Default) {
                val small = PhotoProcessor.applyGeometry(ImageIO.thumbnail(base, 200), geometry)
                filters.associate { f -> f.id to PhotoProcessor.process(small, PhotoEdit(filterId = f.id), f, curveFor(f)) }
            }
        }
    }

    // ---- Full-screen photo tools ----

    fun openPhotoTool(mode: PhotoMode, index: Int) {
        val d = doc ?: return
        val edit = d.edits.getOrNull(index) ?: return
        selection = Selection.None
        photoTool = PhotoToolState(mode, index, edit)
        lastKey = null
    }

    fun donePhotoTool() {
        photoTool = null
        lastKey = null
    }

    fun cancelPhotoTool() {
        val t = photoTool ?: return
        commit("cancel-${stamp()}") { d -> d.copy(edits = d.edits.toMutableList().also { it[t.index] = t.snapshot }) }
        photoTool = null
        lastKey = null
    }

    /** Geometry from source preview pixels to the final (cropped) preview, for photo [i]. */
    private fun geometryOf(i: Int): Triple<Matrix, Bitmap, PhotoEdit>? {
        val src = previews.getOrNull(i) ?: return null
        val e = doc?.edits?.getOrNull(i) ?: return null
        return Triple(PhotoProcessor.geometry(src.width, src.height, e).matrix, src, e)
    }

    private fun finalSize(i: Int): Pair<Float, Float>? {
        val src = previews.getOrNull(i) ?: return null
        val e = doc?.edits?.getOrNull(i) ?: return null
        val g = PhotoProcessor.geometry(src.width, src.height, e)
        return g.width.toFloat() to g.height.toFloat()
    }

    /** Final-photo fractions → source-photo fractions. */
    fun finalToSource(i: Int, u: Float, v: Float): Pair<Float, Float> {
        val (m, src, _) = geometryOf(i) ?: return u to v
        val (fw, fh) = finalSize(i) ?: return u to v
        val inv = Matrix()
        m.invert(inv)
        val p = floatArrayOf(u * fw, v * fh)
        inv.mapPoints(p)
        return p[0] / src.width to p[1] / src.height
    }

    /** Source-photo fractions → final-photo fractions. */
    fun sourceToFinal(i: Int, x: Float, y: Float): Pair<Float, Float> {
        val (m, src, _) = geometryOf(i) ?: return x to y
        val (fw, fh) = finalSize(i) ?: return x to y
        val p = floatArrayOf(x * src.width, y * src.height)
        m.mapPoints(p)
        return p[0] / fw to p[1] / fh
    }

    /** A length as a fraction of the source's shorter side → fraction of the final photo's shorter side. */
    fun sourceLengthToFinal(i: Int, len: Float): Float {
        val (m, src, _) = geometryOf(i) ?: return len
        val (fw, fh) = finalSize(i) ?: return len
        return m.mapRadius(len * min(src.width, src.height)) / min(fw, fh)
    }

    fun finalLengthToSource(i: Int, len: Float): Float {
        val (m, src, _) = geometryOf(i) ?: return len
        val (fw, fh) = finalSize(i) ?: return len
        val inv = Matrix()
        m.invert(inv)
        return inv.mapRadius(len * min(fw, fh)) / min(src.width, src.height)
    }

    /** Direction vector between the two spaces (pixels), normalised. */
    fun sourceDirToFinal(i: Int, dx: Float, dy: Float): Pair<Float, Float> {
        val (m, _, _) = geometryOf(i) ?: return dx to dy
        val v = floatArrayOf(dx, dy)
        m.mapVectors(v)
        val len = hypot(v[0], v[1]).coerceAtLeast(1e-6f)
        return v[0] / len to v[1] / len
    }

    fun finalDirToSource(i: Int, dx: Float, dy: Float): Pair<Float, Float> {
        val (m, _, _) = geometryOf(i) ?: return dx to dy
        val inv = Matrix()
        m.invert(inv)
        val v = floatArrayOf(dx, dy)
        inv.mapVectors(v)
        val len = hypot(v[0], v[1]).coerceAtLeast(1e-6f)
        return v[0] / len to v[1] / len
    }

    // ---- Touch on the collage ----

    fun tap(x: Float, y: Float, w: Float, h: Float) {
        val d = doc ?: return
        selection = renderer.hitTest(d, w, h, x, y)
    }

    fun clearSelection() {
        selection = Selection.None
    }

    /** Pinch / drag on the selected cell or overlay. Returns false when nothing is selected. */
    fun transform(panX: Float, panY: Float, zoom: Float, rotation: Float, w: Float, h: Float): Boolean {
        when (val s = selection) {
            is Selection.Cell -> commit("cell-${s.index}") { doc ->
                val r = cellRect(doc.template.cells[s.index], w, h, doc.style.gap * w)
                val t = doc.transforms[s.index]
                val nt = t.copy(
                    scale = (t.scale * zoom).coerceIn(1f, 5f),
                    dx = (t.dx + panX / r.width()).coerceIn(-1f, 1f),
                    dy = (t.dy + panY / r.height()).coerceIn(-1f, 1f),
                )
                doc.copy(transforms = doc.transforms.toMutableList().also { it[s.index] = nt })
            }
            is Selection.Item -> commit("item-${s.id}") { doc ->
                doc.copy(overlays = doc.overlays.map { if (it.id == s.id) it.transformed(panX / w, panY / h, zoom, rotation) else it })
            }
            Selection.None -> return false
        }
        return true
    }

    // ---- Overlays ----

    val selectedOverlay: Overlay?
        get() {
            val s = selection as? Selection.Item ?: return null
            return doc?.overlays?.firstOrNull { it.id == s.id }
        }

    private fun add(kind: OverlayKind, cy: Float = 0.5f) {
        val o = Overlay(id = nextId++, kind = kind, cy = cy)
        commit("add-${o.id}") { it.copy(overlays = it.overlays + o) }
        selection = Selection.Item(o.id)
    }

    fun addSticker(emoji: String) = add(OverlayKind.Sticker(emoji))
    fun addShape(shape: String, color: Int) = add(OverlayKind.Shape(shape, color))

    fun addText(text: String) {
        if (text.isBlank()) return
        add(OverlayKind.Text(text, textStyle), cy = 0.82f)
    }

    private fun updateSelected(key: String, change: (Overlay) -> Overlay) {
        val s = selection as? Selection.Item ?: return
        commit("$key-${s.id}") { d -> d.copy(overlays = d.overlays.map { if (it.id == s.id) change(it) else it }) }
    }

    /** Edits the selected text overlay's words. */
    fun setSelectedText(text: String) = updateSelected("text") { o ->
        val k = o.kind as? OverlayKind.Text ?: return@updateSelected o
        o.copy(kind = k.copy(text = text))
    }

    /** Changes the style of the selected text overlay, or of the next new text when none is selected. */
    fun setTextStyle(key: String, change: (TextStyle) -> TextStyle) {
        val o = selectedOverlay
        val k = o?.kind as? OverlayKind.Text
        if (k != null) {
            val st = change(k.style)
            textStyle = st
            updateSelected("style-$key") { it.copy(kind = k.copy(style = st)) }
        } else {
            textStyle = change(textStyle)
        }
    }

    fun setShapeColor(color: Int) = updateSelected("shape-color") { o ->
        val k = o.kind as? OverlayKind.Shape ?: return@updateSelected o
        o.copy(kind = k.copy(color = color))
    }

    fun setOpacity(v: Float) = updateSelected("opacity") { it.copy(opacity = v) }
    fun flipSelected() = updateSelected("flip-${stamp()}") { it.copy(flip = !it.flip) }

    fun duplicateSelected() {
        val o = selectedOverlay ?: return
        val copy = o.copy(id = nextId++, cx = (o.cx + 0.05f).coerceAtMost(1f), cy = (o.cy + 0.05f).coerceAtMost(1f))
        commit("dup-${copy.id}") { it.copy(overlays = it.overlays + copy) }
        selection = Selection.Item(copy.id)
    }

    /** Moves the selected overlay one layer up (+1) or down (-1). */
    fun moveLayer(direction: Int) {
        val o = selectedOverlay ?: return
        commit("layer-${stamp()}") { d ->
            val list = d.overlays.toMutableList()
            val i = list.indexOfFirst { it.id == o.id }
            val j = (i + direction).coerceIn(0, list.size - 1)
            if (i == j) d else d.copy(overlays = list.apply { add(j, removeAt(i)) })
        }
    }

    fun deleteSelected() {
        val s = selection as? Selection.Item ?: return
        commit("delete-${s.id}") { d -> d.copy(overlays = d.overlays.filterNot { it.id == s.id }) }
        selection = Selection.None
    }

    // ---- Brush ----

    private val strokePoints = ArrayList<Float>()
    private var strokeCount = 0

    fun startStroke(x: Float, y: Float, w: Float, h: Float) {
        strokePoints.clear()
        strokePoints += x / w
        strokePoints += y / h
        liveStroke = Stroke(strokePoints.toFloatArray(), brush.color, brush.width, brush.neon)
    }

    fun extendStroke(x: Float, y: Float, w: Float, h: Float) {
        if (strokePoints.isEmpty()) return
        strokePoints += x / w
        strokePoints += y / h
        liveStroke = Stroke(strokePoints.toFloatArray(), brush.color, brush.width, brush.neon)
    }

    fun endStroke() {
        val s = liveStroke ?: return
        liveStroke = null
        strokePoints.clear()
        commit("stroke-${strokeCount++}") { it.copy(strokes = it.strokes + s) }
    }

    fun clearStrokes() = commit("clear-strokes-${strokeCount++}") { it.copy(strokes = emptyList()) }

    // ---- Export ----

    fun export(onSaved: (Uri) -> Unit) {
        val d = doc ?: return
        if (busy) return
        val sources = originals
        val aspect = canvasAspect(d)
        val vignettes = vignettes(d)
        viewModelScope.launch {
            busy = true
            try {
                val app = getApplication<Application>()
                val uri = withContext(Dispatchers.Default) {
                    val processed = sources.mapIndexed { i, src ->
                        val spec = filterOf(d.edits[i].filterId)
                        PhotoProcessor.process(src, d.edits[i], spec, curveFor(spec))
                    }
                    val long = 2400f
                    val w = if (aspect >= 1f) long else long * aspect
                    val h = w / aspect
                    val out = Bitmap.createBitmap(w.roundToInt(), max(1, h.roundToInt()), Bitmap.Config.ARGB_8888)
                    // A separate renderer: Paint objects must not be shared across threads.
                    CollageRenderer().draw(Canvas(out), out.width.toFloat(), out.height.toFloat(), d, processed, vignettes, Selection.None)
                    withContext(Dispatchers.IO) { ImageIO.saveJpeg(app, out) }.also { out.recycle() }
                }
                onSaved(uri)
                saveProject()
            } catch (e: Exception) {
                message = getApplication<Application>().getString(R.string.save_failed)
            } finally {
                busy = false
            }
        }
    }
}
