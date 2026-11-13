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
    fun importFrom(ctx: Context, uri: Uri): String {
        val text = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: error("讀不到這個檔案")
        return importText(ctx, text)
    }

    fun importText(ctx: Context, text: String): String {
        val root = runCatching { JSONObject(text) }.getOrNull() ?: error("這不是自轉珠的備份檔")
        if (root.optString("magic") != MAGIC) error("這不是自轉珠的備份檔")
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
