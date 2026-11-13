package com.rcj811.tossolver

import android.content.Context
import android.provider.Settings
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import kotlin.random.Random

/**
 * 自動化測試用（只在模擬器設定了 settings global tos_orb_test=1 時啟用；使用者手機上完全不作用）。
 * 測試腳本用 adb broadcast 送指令，這裡把指令轉成真的按鈕點擊／觸控，並把每一步寫進 logcat（TOSTEST）。
 */
object TestHooks {
    const val ACTION = "com.rcj811.tossolver.TEST"
    const val TAG = "TOSTEST"

    fun enabled(ctx: Context): Boolean =
        runCatching { Settings.Global.getString(ctx.contentResolver, "tos_orb_test") == "1" }.getOrDefault(false)

    fun log(msg: String) { Log.i(TAG, msg) }

    /** 一個 View 樹裡所有可以點的元件 */
    fun clickables(root: View?): List<View> {
        val out = ArrayList<View>()
        fun walk(v: View) {
            if (v.visibility != View.VISIBLE) return
            if (v.isClickable || v.isLongClickable) out.add(v)
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        root?.let { walk(it) }
        return out
    }

    fun label(v: View): String = (v as? android.widget.TextView)?.text?.toString()?.take(20) ?: v.javaClass.simpleName

    /** 在 View 上的 (x, y) 模擬一次點擊或長按（真的觸控事件，走一樣的程式路徑） */
    fun touch(v: View, x: Float, y: Float, holdMs: Long) {
        val t0 = android.os.SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(t0, t0, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(t0, t0 + holdMs, MotionEvent.ACTION_UP, x, y, 0)
        v.dispatchTouchEvent(down)
        v.dispatchTouchEvent(up)
        down.recycle(); up.recycle()
    }

    /** 隨機點一些可點元件（排除會關掉整個程式的按鈕） */
    fun monkey(roots: List<View?>, n: Int, rnd: Random, skip: Set<String>) {
        repeat(n) {
            val all = roots.flatMap { clickables(it) }.filter { label(it) !in skip }
            if (all.isEmpty()) return
            val v = all[rnd.nextInt(all.size)]
            val long = rnd.nextInt(6) == 0 && v.isLongClickable
            log("monkey ${if (long) "長按" else "點"}「${label(v)}」")
            if (long) v.performLongClick() else v.performClick()
        }
    }
}
