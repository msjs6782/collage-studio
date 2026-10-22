package com.example.collagestudio.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.example.collagestudio.model.EditorDoc
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Keeps the last project in the app's private storage: the source photos (as decoded, ≤1600 px),
 * the document as JSON and a small thumbnail for the home screen.
 */
object ProjectStore {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    private fun dir(ctx: Context) = File(ctx.filesDir, "project")

    fun exists(ctx: Context): Boolean = File(dir(ctx), "doc.json").isFile

    /** Replaces the stored photos (called once when a new project starts). */
    fun savePhotos(ctx: Context, photos: List<Bitmap>) {
        val d = dir(ctx)
        d.deleteRecursively()
        d.mkdirs()
        photos.forEachIndexed { i, b ->
            File(d, "photo_$i.jpg").outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        }
    }

    /** Saves the document and thumbnail. Writes to a temp file first so a crash never leaves half a file. */
    fun saveDoc(ctx: Context, doc: EditorDoc, thumb: Bitmap?) {
        val d = dir(ctx)
        if (!d.isDirectory) return
        val tmp = File(d, "doc.json.tmp")
        tmp.writeText(json.encodeToString(EditorDoc.serializer(), doc))
        tmp.renameTo(File(d, "doc.json"))
        thumb?.let { t -> File(d, "thumb.jpg").outputStream().use { t.compress(Bitmap.CompressFormat.JPEG, 85, it) } }
    }

    fun load(ctx: Context): Pair<EditorDoc, List<Bitmap>>? {
        val d = dir(ctx)
        val doc = runCatching { json.decodeFromString(EditorDoc.serializer(), File(d, "doc.json").readText()) }.getOrNull() ?: return null
        val photos = doc.edits.indices.map { i ->
            val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
            BitmapFactory.decodeFile(File(d, "photo_$i.jpg").path, opts) ?: return null
        }
        return doc to photos
    }

    fun thumbnail(ctx: Context): Bitmap? = File(dir(ctx), "thumb.jpg").takeIf { it.isFile }?.let { BitmapFactory.decodeFile(it.path) }

    fun delete(ctx: Context) {
        dir(ctx).deleteRecursively()
    }
}
