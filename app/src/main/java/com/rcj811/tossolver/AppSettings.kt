package com.rcj811.tossolver

import android.content.Context
import kotlin.math.max
import kotlin.math.min

/** App 設定（自動轉珠與手動求解共用） */
class AppSettings(context: Context) {
    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    companion object {
        const val SAFETY_MS = 400
        val STEP_CHOICES = intArrayOf(30, 40, 50, 60, 70, 90, 110, 140)
        /** 預設轉珠速度：每格 60ms（比最快慢一點，遊戲更不容易漏格） */
        const val DEFAULT_STEP = 60
        val TIME_CHOICES = intArrayOf(4, 5, 6, 8, 10, 15)
    }

    /** true = 自動拖曳；false = 只在畫面上畫出路徑 */
    var autoDrag: Boolean
        get() = sp.getBoolean("autoDrag", true)
        set(v) = sp.edit().putBoolean("autoDrag", v).apply()

    /** 每移動一格的時間（毫秒） */
    var stepMs: Int
        get() {
            // 一次性改成預設速度 60ms（舊版預設 90ms、40ms）
            if (!sp.getBoolean("speedV3", false)) sp.edit().putInt("stepMs", DEFAULT_STEP).putBoolean("speedV3", true).apply()
            return sp.getInt("stepMs", DEFAULT_STEP)
        }
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

    /**
     * 路徑長度上限：把轉珠時間用滿。
     * 實際拖曳時間 = 按住 60ms + 步數 × 每步 + 放開前 40ms；再預留 SAFETY_MS 給系統手勢延遲。
     * 例：5 秒、每步 40ms → (5000 − 100 − 400) / 40 = 112 步（舊版固定 35 步）。
     */
    fun maxPathFor(rows: Int): Int {
        val byTime = (timeLimitSec * 1000 - 100 - SAFETY_MS) / max(1, stepMs)
        return byTime.coerceIn(6, 120)
    }

    /** 解題計算時間（在拖曳前，不佔遊戲轉珠時間）：時間夠就加寬搜尋，找更多 combo / 消更多珠 */
    val solveBudgetMs: Int get() = 1500
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

    fun delete(index: Int) {
        val l = load()
        if (index in l.indices) { l.removeAt(index); save(l) }
    }
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
                out.add(OrbType(o.getInt("id"), o.getString("name"), o.getBoolean("noTouch"), o.getInt("dissolve"),
                    mustStart = o.optBoolean("mustStart", false)))
            }
        }
        return out
    }

    private fun save(list: List<OrbType>) {
        val arr = org.json.JSONArray()
        for (t in list) arr.put(org.json.JSONObject().put("id", t.id).put("name", t.name)
            .put("noTouch", t.noTouch).put("dissolve", t.dissolve).put("mustStart", t.mustStart))
        sp.edit().putString("types", arr.toString()).apply()
    }

    /** 新增後回傳新類別；同時更新 OrbTypes.custom */
    fun add(name: String, noTouch: Boolean, dissolve: Int, mustStart: Boolean = false): OrbType {
        val id = sp.getInt("nextId", OrbTypes.FIRST_CUSTOM_ID)
        val t = OrbType(id, name, noTouch, dissolve, mustStart = mustStart)
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

    /** 修改規則（保留 id 與學過的樣子） */
    fun update(id: Int, name: String, noTouch: Boolean, dissolve: Int, mustStart: Boolean = false) {
        val list = load().map { if (it.id == id) OrbType(id, name, noTouch, dissolve, mustStart = mustStart) else it }
        save(list)
        OrbTypes.custom = list
    }

    /** 載入到 OrbTypes.custom */
    fun apply() { OrbTypes.custom = load() }
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

    /** 每種顏色有幾顆範例珠（顯示用） */
    fun protoCounts(): IntArray {
        val m = load()
        return IntArray(6) { c -> m.protoLabels.count { it == c } }
    }

    /** 清除某一種顏色的範例珠（例如不小心把木珠標成火） */
    fun clearProtos(color: Int) {
        val m = load()
        val keep = m.protoLabels.indices.filter { m.protoLabels[it] != color }
        val m2 = ColorModel(m.centroids, m.weights, keep.map { m.protos[it] }.toMutableList(), keep.map { m.protoLabels[it] }.toMutableList())
        save(m2, 0)
    }

    /** 每種符石類別學過幾個樣子 */
    fun stateCount(id: Int): Int = loadStates().labels.count { it == id }

    /** 刪除某類別學過的樣子 */
    fun forgetState(id: Int) {
        val m = loadStates()
        val keep = m.labels.indices.filter { m.labels[it] != id }
        saveStates(StateModel(keep.map { m.protos[it] }.toMutableList(), keep.map { m.labels[it] }.toMutableList()))
    }

    fun reset() = sp.edit().clear().apply()
}

/** 目前安裝的版本（對應 GitHub 發布頁的 build-N），例如「build-29（v0.1.29）」 */
fun appVersion(ctx: Context): String = runCatching {
    val p = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
    @Suppress("DEPRECATION")
    "build-${p.versionCode}（v${p.versionName}）"
}.getOrDefault("版本未知")

/** 黑白盤面的形狀資料庫：使用者在「校」確認／修正過的黑白珠形狀 */
class ShapeStore(context: Context) {
    private val sp = context.getSharedPreferences("shapes", Context.MODE_PRIVATE)

    fun load(): List<Pair<Int, FloatArray>> = runCatching {
        val arr = org.json.JSONArray(sp.getString("list", "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val b = java.util.Base64.getDecoder().decode(o.getString("d"))
            o.getInt("c") to ShapeMatcher.normalized(FloatArray(b.size) { b[it].toFloat() })
        }
    }.getOrDefault(emptyList())

    private fun save(list: List<Pair<Int, FloatArray>>) {
        val arr = org.json.JSONArray()
        for ((c, d) in list) {
            var m = 1e-6f
            for (v in d) m = maxOf(m, kotlin.math.abs(v))
            val b = ByteArray(d.size) { (d[it] / m * 127f).toInt().coerceIn(-127, 127).toByte() }
            arr.put(org.json.JSONObject().put("c", c).put("d", java.util.Base64.getEncoder().encodeToString(b)))
        }
        sp.edit().putString("list", arr.toString()).apply()
    }

    /** 載入到辨識器（服務啟動、設定變更時呼叫） */
    fun apply() {
        ShapeMatcher.learned.clear()
        ShapeMatcher.learned.addAll(load())
    }

    fun add(color: Int, shape: FloatArray) {
        val list = load().toMutableList()
        list.add(color to shape)
        // 每種顏色最多留 30 個（留最新的）
        while (list.count { it.first == color } > 30) list.removeAt(list.indexOfFirst { it.first == color })
        save(list)
        apply()
    }

    fun count(color: Int) = load().count { it.first == color }

    fun clear(color: Int? = null) {
        save(if (color == null) emptyList() else load().filter { it.first != color })
        apply()
    }
}
