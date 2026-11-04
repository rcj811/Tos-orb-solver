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

    /** 自動執行遇到沒記過的關卡時，自動長按王讀取技能說明 */
    var autoReadBoss: Boolean
        get() = sp.getBoolean("autoReadBoss", true)
        set(v) = sp.edit().putBoolean("autoReadBoss", v).apply()

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

/** 記住的關卡（敵人畫面特徵 ＋ 機制）的存取 */
class StagePresetStore(context: Context) {
    private val sp = context.getSharedPreferences("stage", Context.MODE_PRIVATE)

    fun load(): MutableList<StagePreset> {
        val out = ArrayList<StagePreset>()
        runCatching {
            val arr = org.json.JSONArray(sp.getString("presets", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                fun parseSig(t: String) = t.split(',').map { it.toFloat() }.toFloatArray()
                val sigs = ArrayList<FloatArray>()
                val sa = o.optJSONArray("sigs")
                if (sa != null) for (j in 0 until sa.length()) sigs.add(parseSig(sa.getString(j)))
                else sigs.add(parseSig(o.getString("sig")))          // 舊格式：只有一種樣子
                val r = StageRules().apply { deserialize(o.getString("rules")) }
                out.add(StagePreset(sigs, r, o.optInt("seen"), o.optInt("met"), o.optInt("unmet")))
            }
        }
        return out
    }

    fun save(list: List<StagePreset>) {
        val arr = org.json.JSONArray()
        for (p in list) {
            val sa = org.json.JSONArray()
            for (sg in p.sigs) sa.put(sg.joinToString(",") { String.format(java.util.Locale.US, "%.3f", it) })
            arr.put(org.json.JSONObject()
                .put("sigs", sa)
                .put("rules", p.rules.serialize())
                .put("seen", p.seen).put("met", p.met).put("unmet", p.unmet))
        }
        sp.edit().putString("presets", arr.toString()).apply()
    }

    val count: Int get() = load().size

    /** 顯示用：關卡數、累計回合、記住的樣子數 */
    fun summary(): String {
        val l = load()
        if (l.isEmpty()) return "記住的關卡：0 個"
        val turns = l.sumOf { it.seen }
        val looks = l.sumOf { it.sigs.size }
        val hard = l.count { it.hard }
        return "記住的關卡：${l.size} 個（累計 $turns 回合資料、$looks 種畫面樣子" +
            (if (hard > 0) "、$hard 關自動提高品質" else "") + "）"
    }

    fun clear() = sp.edit().remove("presets").apply()
}

/** 自訂符石類別的存取 */
class OrbTypeStore(context: Context) {
    private val sp = context.getSharedPreferences("orbtypes", Context.MODE_PRIVATE)

    fun load(): List<OrbType> {
        val out = ArrayList<OrbType>()
        runCatching {
            val arr = org.json.JSONArray(sp.getString("types", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(OrbType(o.getInt("id"), o.getString("name"), o.getBoolean("noTouch"), o.getInt("dissolve")))
            }
        }
        return out
    }

    private fun save(list: List<OrbType>) {
        val arr = org.json.JSONArray()
        for (t in list) arr.put(org.json.JSONObject().put("id", t.id).put("name", t.name)
            .put("noTouch", t.noTouch).put("dissolve", t.dissolve))
        sp.edit().putString("types", arr.toString()).apply()
    }

    /** 新增後回傳新類別；同時更新 OrbTypes.custom */
    fun add(name: String, noTouch: Boolean, dissolve: Int): OrbType {
        val id = sp.getInt("nextId", OrbTypes.FIRST_CUSTOM_ID)
        val t = OrbType(id, name, noTouch, dissolve)
        val list = load() + t
        sp.edit().putInt("nextId", id + 1).apply()
        save(list)
        OrbTypes.custom = list
        return t
    }

    fun delete(id: Int) {
        val list = load().filter { it.id != id }
        save(list)
        OrbTypes.custom = list
    }

    /** 載入到 OrbTypes.custom */
    fun apply() { OrbTypes.custom = load() }
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
                out.add(TapRule(o.getDouble("x").toFloat(), o.getDouble("y").toFloat(), o.getDouble("size").toFloat(), sig,
                    o.optInt("act", TapRule.ACTION_TAP)))
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
            o.put("act", r.action)
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
        runCatching {
            val arr = org.json.JSONArray(sp.getString("protos", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val f = o.getString("f").split(',').map { it.toFloat() }.toFloatArray()
                if (f.size == 3) { m.protos.add(f); m.protoLabels.add(o.getInt("c")) }
            }
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
        val arr = org.json.JSONArray()
        for (i in m.protos.indices) arr.put(org.json.JSONObject().put("c", m.protoLabels[i])
            .put("f", m.protos[i].joinToString(",") { String.format(java.util.Locale.US, "%.4f", it) }))
        e.putString("protos", arr.toString())
        e.apply()
    }

    val learnedSamples: Int get() = sp.getInt("learned", 0)

    /** 符石狀態的學習原型 */
    fun loadStates(): StateModel {
        val m = StateModel()
        runCatching {
            val arr = org.json.JSONArray(sp.getString("states", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val d = o.getString("d").split(',').map { it.toFloat() }.toFloatArray()
                if (d.size == StateModel.DIM) { m.protos.add(d); m.labels.add(o.getInt("s")) }
            }
        }
        return m
    }

    fun saveStates(m: StateModel) {
        val arr = org.json.JSONArray()
        for (i in m.protos.indices) {
            arr.put(org.json.JSONObject()
                .put("s", m.labels[i])
                .put("d", m.protos[i].joinToString(",") { String.format(java.util.Locale.US, "%.4f", it) }))
        }
        sp.edit().putString("states", arr.toString()).apply()
    }

    /** 各類別學了幾個樣子（顯示用） */
    fun stateSummary(): String {
        val m = loadStates()
        val parts = OrbTypes.all().filter { it.id != OrbState.NORMAL }.mapNotNull { t ->
            val n = m.labels.count { it == t.id }
            if (n > 0) "${t.name} $n" else null
        }
        return if (parts.isEmpty()) "尚未學習特殊符石" else "已學特殊符石：" + parts.joinToString("、")
    }

    /** 刪除某類別學過的樣子 */
    fun forgetState(id: Int) {
        val m = loadStates()
        val keep = m.labels.indices.filter { m.labels[it] != id }
        saveStates(StateModel(keep.map { m.protos[it] }.toMutableList(), keep.map { m.labels[it] }.toMutableList()))
    }

    fun reset() = sp.edit().clear().apply()
}
