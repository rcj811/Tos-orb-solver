package com.rcj811.tossolver

import kotlin.math.abs
import kotlin.math.min

// 不依賴 Android API（可在 JVM 單元測試中驗證）

/**
 * 點擊規則：「畫面上這個位置長得像錄製時的樣子，就點它」。
 * 用來自動處理戰鬥以外的畫面（結算、再次挑戰、選戰友、出戰…）。
 */
class TapRule(val x: Float, val y: Float, val size: Float, val sig: FloatArray, val action: Int = ACTION_TAP) {
    companion object {
        const val ACTION_TAP = 0     // 看到就點
        const val ACTION_STOP = 1    // 看到就停止自動執行並提醒（例如戰敗、體力不足）
    }
}

object TapMatcher {
    const val GRID = 12               // 特徵：點擊位置周圍正方形切成 12x12 格的平均顏色
    const val MATCH_DIFF = 0.07f      // 平均色差（0~1）低於此值視為同一畫面

    fun defaultSize(frame: Frame) = 0.09f * min(frame.width, frame.height)

    fun signature(frame: Frame, x: Float, y: Float, size: Float): FloatArray? {
        val half = size / 2f
        return rectSignature(frame, x - half, y - half, size, size, GRID, GRID)
    }

    /** 矩形區域切成 gx × gy 格，每格取 3x3 點的平均 RGB（0~1）；超出畫面回傳 null */
    fun rectSignature(frame: Frame, left: Float, top: Float, w: Float, h: Float, gxN: Int, gyN: Int): FloatArray? {
        if (left < 0 || top < 0 || left + w > frame.width || top + h > frame.height || w <= 0 || h <= 0) return null
        val out = FloatArray(gxN * gyN * 3)
        val cw = w / gxN
        val ch = h / gyN
        for (gy in 0 until gyN) for (gx in 0 until gxN) {
            var r = 0f
            var g = 0f
            var b = 0f
            var n = 0
            for (sy in 0..2) for (sx in 0..2) {
                val px = (left + (gx + (sx + 0.5f) / 3f) * cw).toInt().coerceIn(0, frame.width - 1)
                val py = (top + (gy + (sy + 0.5f) / 3f) * ch).toInt().coerceIn(0, frame.height - 1)
                val c = frame.pixels[py * frame.width + px]
                r += (c shr 16 and 0xFF); g += (c shr 8 and 0xFF); b += (c and 0xFF); n++
            }
            val o = (gy * gxN + gx) * 3
            out[o] = r / n / 255f
            out[o + 1] = g / n / 255f
            out[o + 2] = b / n / 255f
        }
        return out
    }

    fun meanAbsDiff(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return 1f
        var d = 0f
        for (i in a.indices) d += abs(a[i] - b[i])
        return d / a.size
    }

    /** 目前畫面與規則錄製時的平均色差；超出畫面回傳 1 */
    fun diff(frame: Frame, rule: TapRule): Float {
        val s = signature(frame, rule.x, rule.y, rule.size) ?: return 1f
        return meanAbsDiff(s, rule.sig)
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

/** 記住的關卡：敵人區畫面特徵 ＋ 這關的機制 */
class StagePreset(val sig: FloatArray, val rules: StageRules)

/**
 * 自動判斷關卡：比對盤面上方「敵人區」的畫面特徵。
 * 敵人區 = 畫面頂端 6% 到盤面上緣再往上 1.3 格（避開隊伍頭像與血條）。
 */
object StageMatcher {
    const val GX = 16
    const val GY = 10
    const val MATCH_DIFF = 0.06f   // 寧可判斷成「一般關卡」，也不要套錯別關的機制

    fun signature(frame: Frame, geo: BoardGeometry): FloatArray? {
        val top = frame.height * 0.06f
        var bottom = geo.top - geo.cell * 1.3f
        if (bottom - top < geo.cell) bottom = geo.top - 2f
        if (bottom - top < geo.cell * 0.5f) return null
        return TapMatcher.rectSignature(frame, 0f, top, frame.width.toFloat(), bottom - top, GX, GY)
    }

    /** 回傳最相似的關卡索引與色差；沒有在門檻內的回傳 -1 */
    fun best(sig: FloatArray?, presets: List<StagePreset>): Pair<Int, Float> {
        if (sig == null) return -1 to 1f
        var bi = -1
        var bd = 1f
        for (i in presets.indices) {
            val d = TapMatcher.meanAbsDiff(sig, presets[i].sig)
            if (d < bd) { bd = d; bi = i }
        }
        return if (bd < MATCH_DIFF) bi to bd else -1 to bd
    }
}
