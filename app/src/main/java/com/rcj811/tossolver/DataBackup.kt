package com.rcj811.tossolver

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 匯出／匯入所有學習資料（移除程式前先匯出，重裝後匯入）。
 * 資料都存在這幾個設定檔：學過的顏色與符石樣子、符石類別、記住的關卡、黑白盤面形狀、App 設定（含校正位置）。
 */
object DataBackup {
    private val STORES = listOf("colors", "orbtypes", "stage", "shapes", "settings")
    private const val MAGIC = "tos-orb-solver-backup"

    fun suggestedName(): String =
        "自轉珠備份-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()) + ".json"

    /** 打包成 JSON 文字 */
    fun export(ctx: Context): String {
        val root = JSONObject()
        root.put("magic", MAGIC)
        root.put("version", appVersion(ctx))
        root.put("time", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
        val stores = JSONObject()
        for (name in STORES) {
            val sp = ctx.getSharedPreferences(name, Context.MODE_PRIVATE)
            val o = JSONObject()
            for ((k, v) in sp.all) {
                val e = JSONObject()
                when (v) {
                    is Boolean -> e.put("t", "b").put("v", v)
                    is Int -> e.put("t", "i").put("v", v)
                    is Long -> e.put("t", "l").put("v", v)
                    is Float -> e.put("t", "f").put("v", v.toDouble())
                    is String -> e.put("t", "s").put("v", v)
                    is Set<*> -> e.put("t", "ss").put("v", JSONArray(v.map { it.toString() }))
                    else -> continue
                }
                o.put(k, e)
            }
            stores.put(name, o)
        }
        root.put("stores", stores)
        return root.toString()
    }

    fun exportTo(ctx: Context, uri: Uri) {
        val text = export(ctx)
        ctx.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            ?: error("沒辦法寫入這個檔案")
    }

    /** 從檔案還原：先檢查是不是自轉珠的備份，再整個取代目前的資料。回傳備份的版本與時間 */
    fun importFrom(ctx: Context, uri: Uri): String = importText(ctx, readFile(ctx, uri))

    private fun parse(text: String): JSONObject {
        val root = runCatching { JSONObject(text) }.getOrNull() ?: error("這不是自轉珠的備份檔")
        if (root.optString("magic") != MAGIC) error("這不是自轉珠的備份檔")
        return root
    }

    private fun readFile(ctx: Context, uri: Uri): String =
        ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: error("讀不到這個檔案")

    fun mergeFrom(ctx: Context, uri: Uri): String = smartImport(ctx, readFile(ctx, uri))

    /** 先試著比對合併；比對出問題（舊格式、資料怪）就放棄比對，直接整個匯入 */
    fun smartImport(ctx: Context, text: String): String {
        parse(text)   // 不是備份檔就直接報錯，不動目前資料
        val before = export(ctx)
        return runCatching { mergeText(ctx, text) }.getOrElse { e ->
            CrashLog.record(ctx, "合併匯入失敗，改成直接匯入", e)
            runCatching { importText(ctx, before) }   // 先還原成合併前，再整個匯入
            "（比對失敗，已改成直接匯入全部資料）\n備份 " + importText(ctx, text)
        }
    }

    // ---------------- 合併：逐筆比對，只新增目前沒有的 ----------------

    /** 備份裡某個設定檔的某個值（字串） */
    private fun storeString(stores: JSONObject, store: String, key: String): String? =
        stores.optJSONObject(store)?.optJSONObject(key)?.takeIf { it.optString("t") == "s" }?.optString("v")

    /** JSON 陣列合併：用 key() 判斷重複，回傳（新增數, 重複數） */
    private fun unionArray(cur: JSONArray, add: JSONArray, key: (JSONObject) -> String, map: (JSONObject) -> JSONObject = { it }): Pair<Int, Int> {
        val seen = HashSet<String>()
        for (i in 0 until cur.length()) seen.add(key(cur.getJSONObject(i)))
        var added = 0; var dup = 0
        for (i in 0 until add.length()) {
            val o = map(add.getJSONObject(i))
            if (seen.add(key(o))) { cur.put(o); added++ } else dup++
        }
        return added to dup
    }

    /**
     * 合併匯入：目前的資料都保留，備份裡「目前沒有的」才加進來。回傳給使用者看的報告。
     * - 顏色／符石樣子／黑白形狀：完全一樣的樣本算重複
     * - 自訂符石類別：同名算同一類（樣子的類別編號會對應過去）
     * - 記住的關卡：敵人畫面相同且條件相同算重複；畫面相同但條件不同 → 保留目前的
     * - App 設定（含校正位置）：目前有的保留，只補目前沒有的
     */
    fun mergeText(ctx: Context, text: String): String {
        val root = parse(text)
        val stores = root.getJSONObject("stores")
        val report = ArrayList<String>()

        // 1) 自訂符石類別：同名對應，新的給新編號
        val typesSp = ctx.getSharedPreferences("orbtypes", Context.MODE_PRIVATE)
        val curTypes = JSONArray(typesSp.getString("types", "[]"))
        val idMap = HashMap<Int, Int>()
        var nextId = typesSp.getInt("nextId", OrbTypes.FIRST_CUSTOM_ID)
        for (i in 0 until curTypes.length()) nextId = maxOf(nextId, curTypes.getJSONObject(i).getInt("id") + 1)
        var typesAdded = 0
        storeString(stores, "orbtypes", "types")?.let { JSONArray(it) }?.let { bt ->
            for (i in 0 until bt.length()) {
                val t = bt.getJSONObject(i)
                val same = (0 until curTypes.length()).map { curTypes.getJSONObject(it) }.firstOrNull { it.getString("name") == t.getString("name") }
                if (same != null) idMap[t.getInt("id")] = same.getInt("id")
                else {
                    val nid = nextId++
                    idMap[t.getInt("id")] = nid
                    curTypes.put(JSONObject(t.toString()).put("id", nid))
                    typesAdded++
                }
            }
        }
        if (typesAdded > 0) typesSp.edit().putString("types", curTypes.toString()).putInt("nextId", nextId).commit()
        report.add("自訂符石類別：新增 $typesAdded 個")

        // 2) 顏色樣本、符石樣子
        val colorsSp = ctx.getSharedPreferences("colors", Context.MODE_PRIVATE)
        val ce = colorsSp.edit()
        val protos = JSONArray(colorsSp.getString("protos", "[]"))
        val (pa, pd) = storeString(stores, "colors", "protos")?.let { unionArray(protos, JSONArray(it), { o -> "${o.optInt("c")}|${o.optString("f")}" }) } ?: (0 to 0)
        if (pa > 0) ce.putString("protos", protos.toString())
        val states = JSONArray(colorsSp.getString("states", "[]"))
        val (sa, sd) = storeString(stores, "colors", "states")?.let {
            unionArray(states, JSONArray(it), { o -> "${o.optInt("s")}|${o.optString("d")}" }) { o ->
                val sId = o.optInt("s")
                if (sId >= OrbTypes.FIRST_CUSTOM_ID) JSONObject(o.toString()).put("s", idMap[sId] ?: sId) else o
            }
        } ?: (0 to 0)
        if (sa > 0) ce.putString("states", states.toString())
        // 顏色中心：目前還沒學過才用備份的
        val bc = stores.optJSONObject("colors")
        val fresh = !colorsSp.getBoolean("saved", false)
        if (fresh && bc != null) {
            for (k in bc.keys()) {
                val e = bc.getJSONObject(k)
                if (e.optString("t") == "f") ce.putFloat(k, e.getDouble("v").toFloat())
            }
        }
        if (fresh && (pa > 0 || sa > 0 || bc?.has("saved") == true)) ce.putBoolean("saved", true)
        val bakLearned = bc?.optJSONObject("learned")?.optInt("v") ?: 0
        val curLearned = colorsSp.getInt("learned", 0)
        if (curLearned == 0 && bakLearned > 0) ce.putInt("learned", bakLearned)
        else if (pa > 0) ce.putInt("learned", curLearned + pa)
        ce.commit()
        report.add("學過的顏色樣本：新增 $pa 筆（重複 $pd 筆跳過）")
        report.add("符石樣子：新增 $sa 筆（重複 $sd 筆跳過）")

        // 3) 黑白盤面形狀
        val shapesSp = ctx.getSharedPreferences("shapes", Context.MODE_PRIVATE)
        val shapes = JSONArray(shapesSp.getString("list", "[]"))
        val (ha, hd) = storeString(stores, "shapes", "list")?.let { unionArray(shapes, JSONArray(it), { o -> "${o.optInt("c")}|${o.optString("d")}" }) } ?: (0 to 0)
        if (ha > 0) shapesSp.edit().putString("list", shapes.toString()).commit()
        if (ha + hd > 0) report.add("黑白盤面形狀：新增 $ha 筆（重複 $hd 筆跳過）")

        // 4) 記住的關卡：用敵人畫面比對
        val presetStore = StagePresetStore(ctx)
        val cur = presetStore.load()
        var stAdded = 0; var stDup = 0; var stKept = 0; var sigAdded = 0
        storeString(stores, "stage", "presets")?.let { txt ->
            // 借用 StagePresetStore 的讀法：先寫到暫存的設定檔再讀出來
            val tmp = ctx.getSharedPreferences("stage_import_tmp", Context.MODE_PRIVATE)
            tmp.edit().putString("presets", txt).commit()
            val bak = StagePresetStore(ctx, "stage_import_tmp").load()
            tmp.edit().clear().commit()
            for (p in bak) {
                val (idx, _) = StageMatcher.best(p.sigs.firstOrNull(), cur)
                when {
                    idx < 0 -> { cur.add(p); stAdded++ }
                    cur[idx].rules.serialize() == p.rules.serialize() -> {
                        // 同一關、同樣條件：把備份裡新的畫面樣子補進去
                        for (sg in p.sigs) if (cur[idx].sigs.none { it.contentEquals(sg) }) { cur[idx].sigs.add(sg); sigAdded++ }
                        stDup++
                    }
                    else -> stKept++
                }
            }
            if (stAdded + sigAdded > 0) presetStore.save(cur)   // 沒有新東西就不重寫
        }
        report.add("記住的關卡：新增 $stAdded 關（重複 $stDup 關跳過" + (if (stKept > 0) "；$stKept 關畫面相同但條件不同，保留目前的" else "") + "）")

        // 5) App 設定：只補目前沒有的
        val setSp = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val se = setSp.edit()
        var setAdded = 0
        stores.optJSONObject("settings")?.let { o ->
            for (k in o.keys()) {
                if (setSp.contains(k)) continue
                val e = o.getJSONObject(k)
                when (e.getString("t")) {
                    "b" -> se.putBoolean(k, e.getBoolean("v"))
                    "i" -> se.putInt(k, e.getInt("v"))
                    "l" -> se.putLong(k, e.getLong("v"))
                    "f" -> se.putFloat(k, e.getDouble("v").toFloat())
                    "s" -> se.putString(k, e.getString("v"))
                    else -> continue
                }
                setAdded++
            }
        }
        se.commit()
        report.add("App 設定：補上 $setAdded 項（目前已有的設定不變）")
        return "備份 ${root.optString("version")}（${root.optString("time")}）\n" + report.joinToString("\n") { "・$it" }
    }

    /** 完全取代：用備份的資料整個蓋掉目前的 */
    fun importText(ctx: Context, text: String): String {
        val root = parse(text)
        val stores = root.getJSONObject("stores")
        for (name in STORES) {
            val o = stores.optJSONObject(name) ?: continue
            val ed = ctx.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
            for (k in o.keys()) {
                val e = o.getJSONObject(k)
                when (e.getString("t")) {
                    "b" -> ed.putBoolean(k, e.getBoolean("v"))
                    "i" -> ed.putInt(k, e.getInt("v"))
                    "l" -> ed.putLong(k, e.getLong("v"))
                    "f" -> ed.putFloat(k, e.getDouble("v").toFloat())
                    "s" -> ed.putString(k, e.getString("v"))
                    "ss" -> {
                        val a = e.getJSONArray("v")
                        ed.putStringSet(k, (0 until a.length()).map { a.getString(it) }.toSet())
                    }
                }
            }
            ed.commit()
        }
        return "${root.optString("version")}（${root.optString("time")}）"
    }
}
