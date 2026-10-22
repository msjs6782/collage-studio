package com.example.collagestudio.data

import android.content.res.AssetManager
import android.graphics.Color
import com.example.collagestudio.filter.ToneCurve
import com.example.collagestudio.model.CellSpec
import com.example.collagestudio.model.FilterSpec
import com.example.collagestudio.model.FontSpec
import com.example.collagestudio.model.Template
import org.json.JSONObject

/**
 * Reads templates and filters from JSON files in assets. This replaces Picart's giant
 * hand-written switch statements: new content = a new JSON entry, no new code.
 */
class AssetRepository(private val assets: AssetManager) {

    fun templates(): List<Template> {
        val root = JSONObject(read("templates.json"))
        val arr = root.getJSONArray("templates")
        return List(arr.length()) { i ->
            val t = arr.getJSONObject(i)
            val cells = t.getJSONArray("cells")
            Template(
                id = t.getString("id"),
                name = t.getString("name"),
                aspect = t.optDouble("aspect", 1.0).toFloat(),
                group = t.optString("group", "layout"),
                cells = List(cells.length()) { j ->
                    val c = cells.getJSONObject(j)
                    CellSpec(
                        x = c.getDouble("x").toFloat(),
                        y = c.getDouble("y").toFloat(),
                        w = c.getDouble("w").toFloat(),
                        h = c.getDouble("h").toFloat(),
                        mask = if (c.has("mask")) c.getString("mask") else null,
                    )
                },
            )
        }
    }

    fun filters(): List<FilterSpec> {
        val arr = JSONObject(read("filters.json")).getJSONArray("filters")
        return List(arr.length()) { i ->
            val f = arr.getJSONObject(i)
            FilterSpec(
                id = f.getString("id"),
                name = f.getString("name"),
                acv = if (f.has("acv")) f.getString("acv") else null,
                saturation = f.optDouble("saturation", 1.0).toFloat(),
                duoShadow = if (f.has("duoShadow")) Color.parseColor(f.getString("duoShadow")) else null,
                duoHighlight = if (f.has("duoHighlight")) Color.parseColor(f.getString("duoHighlight")) else null,
                duoAmount = f.optDouble("duoAmount", 1.0).toFloat(),
                grain = f.optDouble("grain", 0.0).toFloat(),
                vignette = f.optDouble("vignette", 0.0).toFloat(),
            )
        }
    }

    fun fonts(): List<FontSpec> {
        val arr = JSONObject(read("fonts.json")).getJSONArray("fonts")
        return List(arr.length()) { i ->
            val f = arr.getJSONObject(i)
            FontSpec(
                id = f.getString("id"),
                name = f.getString("name"),
                file = f.getString("file"),
                weight = f.optInt("weight", 0),
            )
        }
    }

    fun curve(path: String): ToneCurve = assets.open(path).use { ToneCurve.parseAcv(it.readBytes()) }

    private fun read(name: String): String = assets.open(name).bufferedReader().use { it.readText() }
}
