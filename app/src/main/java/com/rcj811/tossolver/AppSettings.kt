package com.rcj811.tossolver

import android.content.Context
import kotlin.math.max
import kotlin.math.min

/** App 設定（自動轉珠與手動求解共用） */
class AppSettings(context: Context) {
    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    companion object {
        val STEP_CHOICES = intArrayOf(50, 70, 90, 110, 140, 180)
        val TIME_CHOICES = intArrayOf(4, 5, 6, 8, 10, 15)
    }

    /** true = 自動拖曳；false = 只在畫面上畫出路徑 */
    var autoDrag: Boolean
        get() = sp.getBoolean("autoDrag", true)
        set(v) = sp.edit().putBoolean("autoDrag", v).apply()

    /** 連續模式：盤面穩定就自動出手，直到再按一次圓鈕 */
    var continuous: Boolean
        get() = sp.getBoolean("continuous", false)
        set(v) = sp.edit().putBoolean("continuous", v).apply()

    /** 每移動一格的時間（毫秒） */
    var stepMs: Int
        get() = sp.getInt("stepMs", 90)
        set(v) = sp.edit().putInt("stepMs", v).apply()

    /** 遊戲允許的轉珠秒數，用來限制路徑長度 */
    var timeLimitSec: Int
        get() = sp.getInt("timeLimitSec", 5)
        set(v) = sp.edit().putInt("timeLimitSec", v).apply()

    var beam: Int
        get() = sp.getInt("beam", 450)
        set(v) = sp.edit().putInt("beam", v).apply()

    var dirs: Int
        get() = sp.getInt("dirs", 8)
        set(v) = sp.edit().putInt("dirs", v).apply()

    /** 懸浮工作列位置（-1 = 預設：上方中央） */
    var barX: Int
        get() = sp.getInt("barX", -1)
        set(v) = sp.edit().putInt("barX", v).apply()
    var barY: Int
        get() = sp.getInt("barY", -1)
        set(v) = sp.edit().putInt("barY", v).apply()

    /** 自動拖曳前先顯示路徑多久（毫秒） */
    val previewMs: Long get() = 250L

    val hasGeometry: Boolean get() = sp.contains("geoCell")

    var geometry: BoardGeometry
        get() = BoardGeometry(
            sp.getFloat("geoLeft", 0f), sp.getFloat("geoTop", 0f),
            sp.getFloat("geoCell", 100f), sp.getInt("geoRows", 5),
        )
        set(g) = sp.edit()
            .putFloat("geoLeft", g.left).putFloat("geoTop", g.top)
            .putFloat("geoCell", g.cell).putInt("geoRows", g.rows)
            .apply()

    /** 路徑長度上限：版面建議值與時間限制取較小者 */
    fun maxPathFor(rows: Int): Int {
        val byTime = (timeLimitSec * 1000 - 300) / max(1, stepMs)
        return max(6, min(25 + rows * 2, byTime))
    }
}

/** 關卡機制的存取 */
class StageRuleStore(context: Context) {
    private val sp = context.getSharedPreferences("stage", Context.MODE_PRIVATE)

    fun load(): StageRules = StageRules().also { r -> sp.getString("rules", null)?.let { r.deserialize(it) } }

    fun save(r: StageRules) = sp.edit().putString("rules", r.serialize()).apply()
}

/** 點擊規則的存取（JSON） */
class RuleStore(context: Context) {
    private val sp = context.getSharedPreferences("rules", Context.MODE_PRIVATE)

    fun load(): MutableList<TapRule> {
        val out = ArrayList<TapRule>()
        runCatching {
            val arr = org.json.JSONArray(sp.getString("rules", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val sig = o.getString("sig").split(',').map { it.toFloat() }.toFloatArray()
                out.add(TapRule(o.getDouble("x").toFloat(), o.getDouble("y").toFloat(), o.getDouble("size").toFloat(), sig))
            }
        }
        return out
    }

    fun save(rules: List<TapRule>) {
        val arr = org.json.JSONArray()
        for (r in rules) {
            val o = org.json.JSONObject()
            o.put("x", r.x.toDouble())
            o.put("y", r.y.toDouble())
            o.put("size", r.size.toDouble())
            o.put("sig", r.sig.joinToString(",") { String.format(java.util.Locale.US, "%.3f", it) })
            arr.put(o)
        }
        sp.edit().putString("rules", arr.toString()).apply()
    }

    fun add(rule: TapRule): Int {
        val list = load()
        list.add(rule)
        save(list)
        return list.size
    }

    fun removeLast(): Int {
        val list = load()
        if (list.isNotEmpty()) list.removeAt(list.size - 1)
        save(list)
        return list.size
    }

    val count: Int get() = load().size

    fun clear() = sp.edit().remove("rules").apply()
}

/** 顏色學習結果的存取 */
class ColorStore(context: Context) {
    private val sp = context.getSharedPreferences("colors", Context.MODE_PRIVATE)

    fun load(): ColorModel {
        val m = ColorModel.defaults()
        if (!sp.getBoolean("saved", false)) return m
        for (k in 0 until 6) {
            for (j in 0..2) m.centroids[k][j] = sp.getFloat("c${k}_$j", m.centroids[k][j])
            m.weights[k] = sp.getFloat("w$k", m.weights[k])
        }
        return m
    }

    fun save(m: ColorModel, addedSamples: Int) {
        val e = sp.edit().putBoolean("saved", true)
        for (k in 0 until 6) {
            for (j in 0..2) e.putFloat("c${k}_$j", m.centroids[k][j])
            e.putFloat("w$k", m.weights[k])
        }
        e.putInt("learned", learnedSamples + addedSamples)
        e.apply()
    }

    val learnedSamples: Int get() = sp.getInt("learned", 0)

    fun reset() = sp.edit().clear().apply()
}
