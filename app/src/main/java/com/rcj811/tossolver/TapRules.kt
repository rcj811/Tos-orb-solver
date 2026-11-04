package com.rcj811.tossolver

import kotlin.math.abs
import kotlin.math.min

// 不依賴 Android API（可在 JVM 單元測試中驗證）

/**
 * 點擊規則：「畫面上這個位置長得像錄製時的樣子，就點它」。
 * 用來自動處理戰鬥以外的畫面（結算、再次挑戰、選戰友、出戰…）。
 */
class TapRule(val x: Float, val y: Float, val size: Float, val sig: FloatArray)

object TapMatcher {
    const val GRID = 12               // 特徵：點擊位置周圍正方形切成 12x12 格的平均顏色
    const val MATCH_DIFF = 0.07f      // 平均色差（0~1）低於此值視為同一畫面

    fun defaultSize(frame: Frame) = 0.09f * min(frame.width, frame.height)

    fun signature(frame: Frame, x: Float, y: Float, size: Float): FloatArray? {
        val half = size / 2f
        if (x - half < 0 || y - half < 0 || x + half > frame.width || y + half > frame.height) return null
        val out = FloatArray(GRID * GRID * 3)
        val cs = size / GRID
        for (gy in 0 until GRID) for (gx in 0 until GRID) {
            var r = 0f
            var g = 0f
            var b = 0f
            var n = 0
            for (sy in 0..2) for (sx in 0..2) {
                val px = (x - half + (gx + (sx + 0.5f) / 3f) * cs).toInt().coerceIn(0, frame.width - 1)
                val py = (y - half + (gy + (sy + 0.5f) / 3f) * cs).toInt().coerceIn(0, frame.height - 1)
                val c = frame.pixels[py * frame.width + px]
                r += (c shr 16 and 0xFF); g += (c shr 8 and 0xFF); b += (c and 0xFF); n++
            }
            val o = (gy * GRID + gx) * 3
            out[o] = r / n / 255f
            out[o + 1] = g / n / 255f
            out[o + 2] = b / n / 255f
        }
        return out
    }

    /** 目前畫面與規則錄製時的平均色差；超出畫面回傳 1 */
    fun diff(frame: Frame, rule: TapRule): Float {
        val s = signature(frame, rule.x, rule.y, rule.size) ?: return 1f
        if (s.size != rule.sig.size) return 1f
        var d = 0f
        for (i in s.indices) d += abs(s[i] - rule.sig[i])
        return d / s.size
    }

    /** 回傳第一條符合目前畫面的規則索引，沒有則 -1（skip 中的規則略過） */
    fun firstMatch(frame: Frame, rules: List<TapRule>, skip: (Int) -> Boolean = { false }): Int {
        for (i in rules.indices) {
            if (skip(i)) continue
            if (diff(frame, rules[i]) < MATCH_DIFF) return i
        }
        return -1
    }
}
