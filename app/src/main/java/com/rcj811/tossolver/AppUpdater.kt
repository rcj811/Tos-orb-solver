package com.rcj811.tossolver

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * 自動更新（與「隨身聽」相同做法）：
 * 每次開啟 App 檢查 GitHub Releases，有新版就跳出版本號與更新內容，一鍵下載並開啟安裝畫面。
 */
object AppUpdater {
    private const val REPO = "rcj811/Tos-orb-solver"
    private const val APK_NAME = "tos-orb-solver.apk"
    private const val APP_LABEL = "自轉珠"
    private const val VERSION_PREFIX = "v0.1."

    data class Release(val build: Long, val apkUrl: String, val notes: String) {
        val version get() = versionOf(build)
    }

    fun versionOf(build: Long) = VERSION_PREFIX + build

    fun currentBuild(context: Context): Long {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    }

    fun currentVersion(context: Context) = versionOf(currentBuild(context))

    /** 開 App 時呼叫（manual = true 時，已是最新版也會提示）。 */
    fun check(activity: Activity, manual: Boolean = false) {
        val main = Handler(Looper.getMainLooper())
        thread(name = "update-check", isDaemon = true) {
            val r = fetchNewer(activity)
            main.post {
                if (activity.isFinishing || activity.isDestroyed) return@post
                when {
                    r != null -> showDialog(activity, r)
                    manual -> Toast.makeText(activity, "已是最新版本 ${currentVersion(activity)} ✓", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun fetchNewer(context: Context): Release? = try {
        val conn = URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        val text = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()
        val json = JSONObject(text)
        val build = json.optString("tag_name").removePrefix("build-").toLongOrNull()
        var url: String? = null
        val assets = json.optJSONArray("assets")
        if (assets != null) for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.optString("name") == APK_NAME) url = a.optString("browser_download_url")
        }
        if (build == null || url == null || build <= currentBuild(context)) null
        else {
            val notes = json.optString("body").lines()
                .filterNot { it.startsWith("## ") }
                .joinToString("\n") { it.removePrefix("### ") }
                .trim()
            Release(build, url, notes)
        }
    } catch (_: Exception) {
        null
    }

    private fun showDialog(activity: Activity, r: Release) {
        AlertDialog.Builder(activity)
            .setTitle("有新版本 ${r.version}")
            .setMessage("目前版本 ${currentVersion(activity)}\n\n" + r.notes.ifBlank { "包含最新的修正與改進。" })
            .setPositiveButton("立即更新") { _, _ -> startUpdate(activity, r) }
            .setNegativeButton("下次再說", null)
            .show()
    }

    private fun startUpdate(activity: Activity, r: Release) {
        if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) {
            Toast.makeText(activity, "請允許「安裝不明應用程式」，返回後會再提示更新", Toast.LENGTH_LONG).show()
            activity.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
            )
            return
        }
        val app = activity.applicationContext
        val dm = app.getSystemService(DownloadManager::class.java)
        val id = dm.enqueue(
            DownloadManager.Request(Uri.parse(r.apkUrl))
                .setTitle("$APP_LABEL ${r.version}")
                .setDescription("下載新版中…")
                .setMimeType("application/vnd.android.package-archive")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "${APK_NAME.removeSuffix(".apk")}-${r.build}.apk")
        )
        Toast.makeText(activity, "下載新版中，完成後會自動開啟安裝畫面", Toast.LENGTH_LONG).show()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) != id) return
                runCatching { app.unregisterReceiver(this) }
                val uri = dm.getUriForDownloadedFile(id) ?: return
                runCatching {
                    app.startActivity(
                        Intent(Intent.ACTION_VIEW)
                            .setDataAndType(uri, "application/vnd.android.package-archive")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
        }
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        else @Suppress("UnspecifiedRegisterReceiverFlag") app.registerReceiver(receiver, filter)
    }
}
