package com.rcj811.tossolver

import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// 這個檔案不依賴 Android API，可在 JVM 單元測試中直接驗證。

/** 盤面在螢幕上的位置（螢幕像素座標） */
class BoardGeometry(val left: Float, val top: Float, val cell: Float, val rows: Int) {
    val cells: Int get() = rows * 6
    val right: Float get() = left + 6 * cell
    val bottom: Float get() = top + rows * cell
    fun centerX(i: Int) = left + (i % 6 + 0.5f) * cell
    fun centerY(i: Int) = top + (i / 6 + 0.5f) * cell

    fun cellAt(x: Float, y: Float): Int {
        val cx = floor((x - left) / cell).toInt()
        val cy = floor((y - top) / cell).toInt()
        return if (cx in 0 until 6 && cy in 0 until rows) cy * 6 + cx else -1
    }

    fun moved(dx: Float, dy: Float) = BoardGeometry(left + dx, top + dy, cell, rows)

    /** 改變列數，保持盤面底部位置不變（遊戲盤面固定貼在畫面下方） */
    fun withRows(r: Int) = BoardGeometry(left, bottom - r * cell, cell, r)
}

/** 一張截圖（ARGB 像素） */
class Frame(val width: Int, val height: Int, val pixels: IntArray)

class Recognition(
    val rows: Int,
    val colors: IntArray,          // 0~5 珠種
    val confidence: FloatArray,    // 0~1，該格取樣點投給最多票顏色的比例
    val features: Array<FloatArray>,
) {
    val cells: Int get() = rows * 6
    fun isUnknown(i: Int) = confidence[i] < OrbRecognizer.UNKNOWN_CONF
    fun unknownCount() = (0 until cells).count { isUnknown(it) }
    fun minConfidence() = (0 until cells).minOf { confidence[it] }
    fun totalConfidence() = (0 until cells).sumOf { confidence[it].toDouble() }.toFloat()
}

/** 六色的顏色中心（特徵空間），可依使用者校正逐步學習 */
class ColorModel(val centroids: Array<FloatArray>, val weights: FloatArray) {
    companion object {
        const val PRIOR = 5f
        private const val ADAPT_CAP = 60f
        private const val MAX_WEIGHT = 200f

        fun defaults(): ColorModel {
            val c = Array(6) { k -> FloatArray(3).also { OrbRecognizer.feature(OrbRecognizer.DEFAULT_RGB[k], it) } }
            return ColorModel(c, FloatArray(6) { PRIOR })
        }
    }

    /** 回傳最近的顏色，dist2 寫入距離平方 */
    fun nearest(f: FloatArray, dist2: FloatArray? = null): Int {
        var best = 0
        var bd = Float.MAX_VALUE
        for (k in 0 until 6) {
            val c = centroids[k]
            val dx = f[0] - c[0]
            val dy = f[1] - c[1]
            val dz = f[2] - c[2]
            val d = dx * dx + dy * dy + dz * dz
            if (d < bd) { bd = d; best = k }
        }
        if (dist2 != null) dist2[0] = bd
        return best
    }

    /** 以標註好的格子特徵更新顏色中心；回傳採用的樣本數 */
    fun learn(features: List<FloatArray>, labels: List<Int>): Int {
        val sum = Array(6) { FloatArray(3) }
        val cnt = IntArray(6)
        for (i in features.indices) {
            val k = labels[i]
            if (k !in 0..5) continue
            for (j in 0..2) sum[k][j] += features[i][j]
            cnt[k]++
        }
        var used = 0
        for (k in 0 until 6) {
            if (cnt[k] == 0) continue
            val w = min(weights[k], ADAPT_CAP)   // 舊資料權重有上限，新的盤面（皮膚、亮度）能較快被學進來
            for (j in 0..2) centroids[k][j] = (centroids[k][j] * w + sum[k][j]) / (w + cnt[k])
            weights[k] = min(w + cnt[k], MAX_WEIGHT)
            used += cnt[k]
        }
        return used
    }
}

object OrbRecognizer {
    const val UNKNOWN_CONF = 0.45f   // 低於此值視為「無法辨識」，求解時當成障礙避開
    const val READY_CONF = 0.6f      // 連續模式：每格都要高於此值才會出手

    private const val GRID = 7          // 每格取樣 7x7 點
    private const val SPAN = 0.44f      // 取樣範圍：格子中央 44%
    private const val VOTE_DIST2 = 0.42f * 0.42f

    /** 預設顏色（火、水、木、光、暗、心）；實際遊戲顏色靠校正學習修正 */
    val DEFAULT_RGB = intArrayOf(0xE65032, 0x328CE6, 0x46BE46, 0xF5D246, 0xA046C8, 0xF56EB4)

    /** 顏色特徵：(s·cos h, s·sin h, v)，色相在圓上、飽和度越低越靠近中心 */
    fun feature(rgb: Int, out: FloatArray) {
        val r = (rgb shr 16 and 0xFF) / 255f
        val g = (rgb shr 8 and 0xFF) / 255f
        val b = (rgb and 0xFF) / 255f
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val d = mx - mn
        val s = if (mx <= 0f) 0f else d / mx
        var h = 0f
        if (d > 1e-6f) {
            h = when (mx) {
                r -> 60f * (((g - b) / d) % 6f)
                g -> 60f * ((b - r) / d + 2f)
                else -> 60f * ((r - g) / d + 4f)
            }
            if (h < 0) h += 360f
        }
        val rad = Math.toRadians(h.toDouble())
        out[0] = (s * cos(rad)).toFloat()
        out[1] = (s * sin(rad)).toFloat()
        out[2] = mx
    }

    fun recognize(frame: Frame, geo: BoardGeometry, model: ColorModel): Recognition {
        val n = geo.cells
        val colors = IntArray(n)
        val conf = FloatArray(n)
        val feats = Array(n) { FloatArray(3) }
        val votes = IntArray(6)
        val f = FloatArray(3)
        val d2 = FloatArray(1)
        val half = geo.cell * SPAN / 2f
        val stepLen = 2f * half / GRID
        for (i in 0 until n) {
            votes.fill(0)
            var total = 0
            var sx = 0f
            var sy = 0f
            var sz = 0f
            var got = 0
            val x0 = geo.centerX(i) - half
            val y0 = geo.centerY(i) - half
            for (gy in 0 until GRID) {
                val y = (y0 + (gy + 0.5f) * stepLen).toInt()
                for (gx in 0 until GRID) {
                    val x = (x0 + (gx + 0.5f) * stepLen).toInt()
                    total++
                    if (x < 0 || y < 0 || x >= frame.width || y >= frame.height) continue
                    feature(frame.pixels[y * frame.width + x], f)
                    sx += f[0]; sy += f[1]; sz += f[2]; got++
                    val k = model.nearest(f, d2)
                    if (d2[0] < VOTE_DIST2) votes[k]++
                }
            }
            var best = 0
            for (k in 1 until 6) if (votes[k] > votes[best]) best = k
            colors[i] = best
            conf[i] = if (total == 0) 0f else votes[best].toFloat() / total
            if (got > 0) { feats[i][0] = sx / got; feats[i][1] = sy / got; feats[i][2] = sz / got }
        }
        return Recognition(geo.rows, colors, conf, feats)
    }

    private val EDGE_T = floatArrayOf(0.2f, 0.35f, 0.5f, 0.65f, 0.8f)

    /**
     * 對齊分數 = Σ(格內信心 − 格線上取樣點投給該格顏色的比例)。
     * 格線（珠與珠之間）應該是盤面背景；只看格內信心會偏向避開珠上的高光圖示，
     * 加上格線檢查才能把格子對準珠心，也能排除整片同色的 UI 區塊。
     */
    fun alignmentScore(frame: Frame, geo: BoardGeometry, model: ColorModel): Float {
        val rec = recognize(frame, geo, model)
        val f = FloatArray(3)
        val d2 = FloatArray(1)
        val c = geo.cell
        var score = 0f
        for (i in 0 until geo.cells) {
            val x0 = geo.left + (i % 6) * c
            val y0 = geo.top + (i / 6) * c
            var same = 0
            var total = 0
            for (e in EDGE_T) {
                for (side in 0 until 4) {
                    val px = when (side) { 0, 1 -> x0 + e * c; 2 -> x0; else -> x0 + c }
                    val py = when (side) { 0 -> y0; 1 -> y0 + c; else -> y0 + e * c }
                    total++
                    val x = px.toInt()
                    val y = py.toInt()
                    if (x < 0 || y < 0 || x >= frame.width || y >= frame.height) continue
                    feature(frame.pixels[y * frame.width + x], f)
                    val k = model.nearest(f, d2)
                    if (d2[0] < VOTE_DIST2 && k == rec.colors[i]) same++
                }
            }
            score += rec.confidence[i] - same.toFloat() / total
        }
        return score
    }

    /**
     * 自動找盤面：假設盤面 6 欄、貼近畫面底部、左右對稱。
     * 粗掃左右邊距與底部距離，再微調位置與格子大小，取對齊分數最高者。
     */
    fun autoDetect(frame: Frame, rows: Int, model: ColorModel): BoardGeometry? {
        val w = frame.width.toFloat()
        val h = frame.height.toFloat()
        var best: BoardGeometry? = null
        var bestScore = -1e9f
        for (mi in 0..10) {
            val m = w * mi * 0.01f
            val cell = (w - 2 * m) / 6f
            val bh = cell * rows
            if (bh > h * 0.8f) continue
            val step = max(2f, cell / 12f)
            var b = 0f
            while (b <= h * 0.35f) {
                val g = BoardGeometry(m, h - b - bh, cell, rows)
                val s = alignmentScore(frame, g, model)
                if (s > bestScore) { bestScore = s; best = g }
                b += step
            }
        }
        val coarse = best ?: return null
        // 細調：以盤面中心 x、底部 y 為基準，微調格子大小與位置
        val fine = max(1f, coarse.cell / 48f)
        val midX = coarse.left + 3 * coarse.cell
        val bottom = coarse.bottom
        var refined = coarse
        for (scale in floatArrayOf(0.98f, 0.99f, 1f, 1.01f, 1.02f)) {
            val c = coarse.cell * scale
            for (dy in -6..6) for (dx in -3..3) {
                val g = BoardGeometry(midX - 3 * c + dx * fine, bottom - rows * c + dy * fine, c, rows)
                val s = alignmentScore(frame, g, model)
                if (s > bestScore) { bestScore = s; refined = g }
            }
        }
        return if (bestScore >= rows * 6 * 0.55f) refined else null
    }

    /** 自動執行時允許無法辨識的格子比例（這些格子求解時當障礙避開） */
    const val MAX_UNKNOWN_FRAC = 0.15f

    fun maxUnknown(cells: Int) = max(2, (cells * MAX_UNKNOWN_FRAC).toInt())

    /** 辨識結果轉成盤面，無法辨識的格子記為 -1 */
    fun maskedBoard(rec: Recognition): IntArray = IntArray(rec.cells) { if (rec.isUnknown(it)) -1 else rec.colors[it] }

    /**
     * 兩次截圖是否為同一盤面：只比較兩邊都認得出來的格子（發光、閃爍的珠不影響），
     * 且兩邊都認得的格子要佔 70% 以上。
     */
    fun sameMasked(a: IntArray?, b: IntArray?): Boolean {
        if (a == null || b == null || a.size != b.size || a.isEmpty()) return false
        var both = 0
        for (i in a.indices) {
            if (a[i] < 0 || b[i] < 0) continue
            if (a[i] != b[i]) return false
            both++
        }
        return both >= a.size * 0.7f
    }

    /** 兩次辨識結果是否為同一盤面（只比較有信心的格子） */
    fun sameBoard(a: IntArray?, b: IntArray?): Boolean {
        if (a == null || b == null || a.size != b.size) return false
        for (i in a.indices) if (a[i] != b[i]) return false
        return true
    }
}
