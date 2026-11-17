package com.rcj811.tossolver

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 機制報告：自轉珠遇到「處理不好」的情況（條件沒做到、條件衝突、認不出的珠、技能卡有不認識的技能、
 * 沒辦法轉、拖曳中斷…）時自動記下來。使用者在 App 按「匯出機制報告」得到一個 zip，交給開發者就能照著修改。
 *
 * - 只存盤面區域的小圖（不含隊伍、帳號等玩家資訊）
 * - 同一種情況（同類型＋同標題）只留最新的 3 筆細節，其餘只累計次數，不會越存越大
 * - 寫檔在背景執行緒，不影響轉珠速度
 */
object MechReport {
    private const val DIR = "report"
    private const val EVENTS = "events.jsonl"
    private const val COUNTS = "counts.json"
    private const val MAX_EVENTS = 400
    private const val MAX_IMAGES = 80
    private const val FULL_PER_KEY = 3

    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "mech-report").apply { isDaemon = true } }
    private val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    private fun dir(ctx: Context) = File(ctx.filesDir, DIR).apply { mkdirs() }

    /** 盤面文字：每格固定 3 個字「顏色＋狀態＋?」，對齊好讀。狀態：·普通 +強化 風風化 電觸電 冰冰封 G起點 自自訂類別 */
    fun boardText(rec: Recognition): String {
        val c = "火水木光暗心"
        val st = mapOf(OrbState.NORMAL to '·', OrbState.ENHANCED to '+', OrbState.NO_TOUCH to '風', OrbState.ELECTRIC to '電', OrbState.LOCKED to '冰', OrbState.START to 'G')
        val custom = LinkedHashMap<Int, String>()
        val sb = StringBuilder()
        for (r in 0 until rec.rows) {
            for (x in 0 until 6) {
                val i = r * 6 + x
                val s = rec.states[i]
                val sc = st[s] ?: '自'.also { custom[s] = OrbTypes.get(s).name }
                sb.append(c.getOrElse(rec.colors[i]) { '－' }).append(sc).append(if (rec.isUnknown(i)) '?' else '　')
                if (x < 5) sb.append(' ')
            }
            sb.append('\n')
        }
        sb.append("（·普通 +強化 風風化 電觸電 冰冰封 G起點 ?認不出")
        if (custom.isNotEmpty()) sb.append("；自＝自訂：").append(custom.values.joinToString("、"))
        sb.append('）')
        return sb.toString()
    }

    /** 從截圖切出盤面（只有盤面，不含玩家資訊）縮小成 JPEG；在呼叫的執行緒只做複製，壓縮在背景 */
    private fun cropBoard(frame: Frame?, geo: BoardGeometry?): IntArray? {
        if (frame == null || geo == null) return null
        val l = geo.left.toInt().coerceIn(0, frame.width - 1)
        val t = geo.top.toInt().coerceIn(0, frame.height - 1)
        val w = (geo.right.toInt() - l).coerceIn(1, frame.width - l)
        val h = (geo.bottom.toInt() - t).coerceIn(1, frame.height - t)
        val out = IntArray(w * h + 2)
        out[0] = w; out[1] = h
        for (y in 0 until h) System.arraycopy(frame.pixels, (t + y) * frame.width + l, out, 2 + y * w, w)
        return out
    }

    private fun jpeg(crop: IntArray): ByteArray {
        val w = crop[0]; val h = crop[1]
        val bm = Bitmap.createBitmap(crop, 2, w, w, h, Bitmap.Config.ARGB_8888)
        val scale = minOf(1f, 420f / w)
        val small = if (scale < 1f) Bitmap.createScaledBitmap(bm, (w * scale).toInt(), (h * scale).toInt(), true) else bm
        val bos = ByteArrayOutputStream()
        small.compress(Bitmap.CompressFormat.JPEG, 75, bos)
        if (small !== bm) small.recycle()
        bm.recycle()
        return bos.toByteArray()
    }

    /**
     * 記一筆。type＝類型（例「條件沒做到」），title＝這次的重點（同類型同標題算同一種情況）。
     * info 會原樣放進報告；frame＋geo 有給就附盤面小圖。出錯一律吞掉（報告不能影響轉珠）。
     */
    fun record(
        ctx: Context, type: String, title: String, info: Map<String, Any?> = emptyMap(),
        frame: Frame? = null, geo: BoardGeometry? = null,
    ) {
        val app = ctx.applicationContext
        val crop = runCatching { cropBoard(frame, geo) }.getOrNull()
        val time = ts.format(Date())
        runCatching { io.execute { runCatching { write(app, type, title, info, crop, time) } } }
    }

    private fun write(ctx: Context, type: String, title: String, info: Map<String, Any?>, crop: IntArray?, time: String) {
        val d = dir(ctx)
        val key = "$type|$title"
        val countsFile = File(d, COUNTS)
        val counts = runCatching { JSONObject(countsFile.readText()) }.getOrElse { JSONObject() }
        val n = counts.optJSONObject(key)?.optInt("n") ?: 0
        counts.put(key, JSONObject().put("type", type).put("title", title).put("n", n + 1)
            .put("first", counts.optJSONObject(key)?.optString("first") ?: time).put("last", time))
        countsFile.writeText(counts.toString())
        val ev = File(d, EVENTS)
        // 同一種情況只留「最新的」3 筆：超過就把最舊的那筆（和它的盤面圖）刪掉，換成這次的
        if (n >= FULL_PER_KEY && ev.exists()) {
            val lines = ev.readLines().filter { it.isNotBlank() }.toMutableList()
            val idx = lines.indexOfFirst { runCatching { JSONObject(it).let { o -> o.optString("type") == type && o.optString("title") == title } }.getOrDefault(false) }
            if (idx >= 0) {
                runCatching { JSONObject(lines[idx]).optString("image").takeIf { it.isNotEmpty() }?.let { File(d, it).delete() } }
                lines.removeAt(idx)
                ev.writeText(if (lines.isEmpty()) "" else lines.joinToString("\n", postfix = "\n"))
            }
        }

        val o = JSONObject().put("time", time).put("type", type).put("title", title).put("build", appVersion(ctx))
        val io2 = JSONObject()
        for ((k, v) in info) io2.put(k, when (v) {
            null -> JSONObject.NULL
            is Collection<*> -> JSONArray(v.map { it.toString() })
            is IntArray -> JSONArray(v.toList())
            else -> v.toString()
        })
        o.put("info", io2)
        if (crop != null) {
            val imgs = File(d, "boards").apply { mkdirs() }
            // 圖太多就刪最舊的，新的一定存得進去
            imgs.listFiles()?.sortedBy { it.name }?.let { fs -> if (fs.size >= MAX_IMAGES) fs.take(fs.size - MAX_IMAGES + 1).forEach { it.delete() } }
            run {
                val name = "b" + System.currentTimeMillis() + ".jpg"
                File(imgs, name).writeBytes(jpeg(crop))
                o.put("image", "boards/$name")
            }
        }
        ev.appendText(o.toString() + "\n")
        // 太多就丟掉最舊的
        val lines = ev.readLines()
        if (lines.size > MAX_EVENTS) ev.writeText(lines.takeLast(MAX_EVENTS).joinToString("\n", postfix = "\n"))
    }

    /** 目前有幾筆（App 顯示用） */
    fun size(ctx: Context): Int = runCatching {
        val c = JSONObject(File(dir(ctx), COUNTS).readText())
        c.keys().asSequence().sumOf { c.getJSONObject(it).optInt("n") }
    }.getOrDefault(0)

    fun clear(ctx: Context) { dir(ctx).deleteRecursively() }

    fun suggestedName(): String =
        "自轉珠機制報告-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()) + ".zip"

    /** 讀得懂的摘要：每種情況的次數，以及每筆的細節 */
    private fun readable(ctx: Context): String {
        val d = dir(ctx)
        val sb = StringBuilder()
        sb.append("自轉珠機制報告\n")
        sb.append("產生時間：").append(ts.format(Date())).append('\n')
        sb.append("版本：").append(appVersion(ctx)).append('\n')
        sb.append("手機：").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append("，Android ").append(Build.VERSION.RELEASE).append('\n')
        val dm = ctx.resources.displayMetrics
        sb.append("螢幕：").append(dm.widthPixels).append('x').append(dm.heightPixels).append('\n')
        runCatching {
            val s = AppSettings(ctx)
            if (s.hasGeometry) { val g = s.geometry; sb.append("盤面：${g.rows} 列，左 ${g.left.toInt()} 上 ${g.top.toInt()} 格 ${g.cell.toInt()}\n") }
            sb.append("設定：每步 ${s.stepMs} 毫秒、轉珠時間 ${s.timeLimitSec} 秒、計算 ${s.solveBudgetMs} 毫秒、${s.dirs} 方向\n")
        }
        runCatching { sb.append("記住的關卡：").append(StagePresetStore(ctx).load().size).append(" 關\n") }
        runCatching {
            val sh = ShapeStore(ctx).load()
            val names = "火水木光暗心"
            sb.append("教過的黑白珠形狀：").append(sh.size).append(" 筆")
            if (sh.isNotEmpty()) sb.append("（").append((0 until 6).joinToString(" ") { c -> "${names[c]}${sh.count { it.first == c }}" }).append("）")
            sb.append('\n')
        }

        sb.append("\n==== 各種情況的次數 ====\n")
        runCatching {
            val c = JSONObject(File(d, COUNTS).readText())
            val rows = c.keys().asSequence().map { c.getJSONObject(it) }.sortedByDescending { it.optInt("n") }.toList()
            for (r in rows) sb.append("・[${r.optString("type")}] ${r.optString("title")}　× ${r.optInt("n")}（${r.optString("first")} ～ ${r.optString("last")}）\n")
        }.onFailure { sb.append("（沒有記錄）\n") }

        sb.append("\n==== 每筆細節（最新的在前）====\n")
        runCatching {
            val lines = File(d, EVENTS).readLines().filter { it.isNotBlank() }.reversed()
            for (l in lines) {
                val o = JSONObject(l)
                sb.append("\n--- ${o.optString("time")}　[${o.optString("type")}] ${o.optString("title")}　（${o.optString("build")}）\n")
                val info = o.optJSONObject("info") ?: JSONObject()
                for (k in info.keys()) {
                    val v = info.get(k)
                    val text = if (v is JSONArray) (0 until v.length()).joinToString("、") { v.get(it).toString() } else v.toString()
                    if (text.contains('\n')) sb.append("$k：\n").append(text.prependIndent("    ")).append('\n')
                    else sb.append("$k：").append(text).append('\n')
                }
                if (o.has("image")) sb.append("盤面圖：").append(o.optString("image")).append('\n')
            }
        }
        return sb.toString()
    }

    /** 匯出成 zip：機制報告.txt（給人看）、events.jsonl（給程式看）、盤面小圖、錯誤紀錄 */
    fun exportTo(ctx: Context, uri: Uri) {
        io.submit {}.get()   // 等背景寫完
        val d = dir(ctx)
        ctx.contentResolver.openOutputStream(uri, "wt")?.use { os ->
            ZipOutputStream(os).use { z ->
                fun put(name: String, bytes: ByteArray) { z.putNextEntry(ZipEntry(name)); z.write(bytes); z.closeEntry() }
                put("機制報告.txt", readable(ctx).toByteArray(Charsets.UTF_8))
                File(d, EVENTS).takeIf { it.exists() }?.let { put(EVENTS, it.readBytes()) }
                File(d, COUNTS).takeIf { it.exists() }?.let { put(COUNTS, it.readBytes()) }
                File(d, "boards").listFiles()?.sortedBy { it.name }?.forEach { put("boards/${it.name}", it.readBytes()) }
                File(ctx.filesDir, "crash_log.txt").takeIf { it.exists() }?.let { put("錯誤紀錄.txt", it.readBytes()) }
                // 記住的關卡條件（不含畫面特徵數字，只有條件文字）
                runCatching {
                    val ps = StagePresetStore(ctx).load()
                    val t = ps.mapIndexed { i, p -> "關卡 ${i + 1}：${p.rules.summary()}（轉過 ${p.seen} 回合、做到 ${p.met}、沒做到 ${p.unmet}）\n    ${p.rules.serialize()}" }
                    put("記住的關卡.txt", t.joinToString("\n").toByteArray(Charsets.UTF_8))
                }
            }
        } ?: error("沒辦法寫入這個檔案")
    }
}
