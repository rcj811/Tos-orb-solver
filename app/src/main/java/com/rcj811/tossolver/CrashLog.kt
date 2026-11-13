package com.rcj811.tossolver

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩潰紀錄：程式出錯時把原因存下來，下次打開 App 時顯示，可以一鍵複製給開發者。
 * - 沒抓到的錯誤（會讓程式崩潰）：記下完整錯誤位置後照常崩潰
 * - 抓到的錯誤（record）：記下來，程式繼續跑
 * - 原生程式（C++）崩潰、無回應：開 App 時從 Android 的結束紀錄補上
 */
object CrashLog {
    private const val FILE = "crash_log.txt"
    private const val MAX_CHARS = 12_000

    fun install(ctx: Context) {
        val app = ctx.applicationContext
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        if (prev is Handler) return
        Thread.setDefaultUncaughtExceptionHandler(Handler(app, prev))
    }

    private class Handler(val app: Context, val prev: Thread.UncaughtExceptionHandler?) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(t: Thread, e: Throwable) {
            runCatching { write(app, "崩潰（執行緒 ${t.name}）", e) }
            prev?.uncaughtException(t, e)
        }
    }

    /** 抓到的錯誤：記下來，程式繼續 */
    fun record(ctx: Context, where: String, e: Throwable) {
        runCatching { write(ctx.applicationContext, "已處理的錯誤：$where", e) }
    }

    private fun write(ctx: Context, title: String, e: Throwable) {
        val sw = StringWriter()
        e.printStackTrace(PrintWriter(sw))
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val entry = "==== $time ${appVersion(ctx)} Android ${Build.VERSION.RELEASE}\n$title\n$sw\n"
        val f = File(ctx.filesDir, FILE)
        val old = if (f.exists()) f.readText() else ""
        f.writeText((entry + old).take(MAX_CHARS))   // 新的放最前面
    }

    /** 開 App 時要顯示的內容（沒有就回傳 null） */
    fun pending(ctx: Context, settings: AppSettings): String? {
        val sb = StringBuilder()
        val f = File(ctx.filesDir, FILE)
        if (f.exists()) sb.append(f.readText())
        // Android 記錄的結束原因（原生程式崩潰、無回應）
        if (Build.VERSION.SDK_INT >= 30) runCatching {
            val am = ctx.getSystemService(ActivityManager::class.java)
            val since = settings.lastExitSeen
            var newest = since
            for (x in am.getHistoricalProcessExitReasons(ctx.packageName, 0, 5)) {
                if (x.timestamp <= since) continue
                newest = maxOf(newest, x.timestamp)
                val why = when (x.reason) {
                    ApplicationExitInfo.REASON_CRASH -> "崩潰"
                    ApplicationExitInfo.REASON_CRASH_NATIVE -> "原生程式（C++）崩潰"
                    ApplicationExitInfo.REASON_ANR -> "沒有回應（ANR）"
                    ApplicationExitInfo.REASON_LOW_MEMORY -> "記憶體不足被系統關閉"
                    else -> null
                } ?: continue
                val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(x.timestamp))
                sb.append("==== $time Android 結束紀錄：$why\n").append(x.description ?: "").append('\n')
                if (x.reason == ApplicationExitInfo.REASON_ANR || x.reason == ApplicationExitInfo.REASON_CRASH_NATIVE) {
                    runCatching { x.traceInputStream?.bufferedReader()?.use { r -> sb.append(r.readText().take(4000)).append('\n') } }
                }
            }
            settings.lastExitSeen = newest
        }
        return sb.toString().trim().ifEmpty { null }?.take(MAX_CHARS)
    }

    fun clear(ctx: Context) { File(ctx.filesDir, FILE).delete() }
}
