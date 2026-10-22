package com.example.collagestudio.render

import android.content.res.AssetManager
import android.graphics.Typeface
import com.example.collagestudio.model.FontSpec

/** Loads bundled fonts once and caches them. Thread-safe: Typeface objects are immutable. */
object Fonts {
    private var assets: AssetManager? = null
    private var specs: Map<String, FontSpec> = emptyMap()
    private val cache = HashMap<String, Typeface>()

    fun init(assets: AssetManager, fonts: List<FontSpec>) {
        this.assets = assets
        specs = fonts.associateBy { it.id }
    }

    fun get(id: String): Typeface = synchronized(cache) {
        cache.getOrPut(id) { load(id) }
    }

    private fun load(id: String): Typeface {
        val spec = specs[id] ?: return Typeface.DEFAULT_BOLD
        val am = assets ?: return Typeface.DEFAULT_BOLD
        return try {
            val builder = Typeface.Builder(am, spec.file)
            if (spec.weight > 0) builder.setFontVariationSettings("'wght' ${spec.weight}")
            builder.build() ?: Typeface.DEFAULT_BOLD
        } catch (e: Exception) {
            Typeface.DEFAULT_BOLD
        }
    }
}
