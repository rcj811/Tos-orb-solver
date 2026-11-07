package com.rcj811.tossolver

import kotlin.math.max
import kotlin.math.sqrt

/**
 * 黑白盤面（黑白符石：顏色被拿掉）時，改用符石「形狀邊緣」判斷珠種。
 *
 * 做法：每格轉灰階 → 縮成 32×32 → 算邊緣強度（Sobel）→ 和每種珠的形狀範本比對。
 * 範本由一般彩色盤面的邊緣平均而成（水滴、星形、火焰、葉子、月亮、愛心的輪廓與圖案），
 * 使用者在「校」確認／修正過的黑白珠也會存進資料庫，之後一起比對。
 * 格線稍微沒對準時，先用整個盤面找最吻合的位置與大小再判斷。
 */
object ShapeMatcher {
    const val N = 32
    private const val D = N * N
    /** 盤面取樣點顏色濃度（RGB 最大減最小）的中位數低於此值 → 黑白盤面 */
    private const val GRAY_CHROMA = 12
    /** 最佳與第二名的分數差距達到此值 → 信心 1 */
    private const val SURE_MARGIN = 0.08f

    private const val TEMPLATE_B64 =
        "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"

    private val templates: Array<FloatArray> by lazy {
        val b = java.util.Base64.getDecoder().decode(TEMPLATE_B64)
        Array(6) { k -> normalized(FloatArray(D) { b[k * D + it].toFloat() }) }
    }

    /** 使用者教過的黑白珠形狀（顏色, 形狀描述） */
    val learned: MutableList<Pair<Int, FloatArray>> = ArrayList()

    fun normalized(v: FloatArray): FloatArray {
        var s = 0.0
        for (x in v) s += x * x
        val n = sqrt(s).toFloat() + 1e-6f
        return FloatArray(v.size) { v[it] / n }
    }

    /** 是不是黑白盤面 */
    fun isGray(frame: Frame, geo: BoardGeometry): Boolean {
        val vals = ArrayList<Int>()
        for (i in 0 until geo.cells) {
            val cx = geo.centerX(i); val cy = geo.centerY(i)
            for (dy in -2..2) for (dx in -2..2) {
                val x = (cx + dx * geo.cell * 0.11f).toInt(); val y = (cy + dy * geo.cell * 0.11f).toInt()
                if (x < 0 || y < 0 || x >= frame.width || y >= frame.height) continue
                val p = frame.pixels[y * frame.width + x]
                val r = (p shr 16) and 255; val g = (p shr 8) and 255; val b = p and 255
                vals.add(maxOf(r, g, b) - minOf(r, g, b))
            }
        }
        if (vals.size < 10) return false
        vals.sort()
        return vals[vals.size / 2] < GRAY_CHROMA
    }

    /** 灰階積分圖（只算盤面附近），用來快速算任意方塊的平均亮度；緩衝區重複使用，不每次配置記憶體 */
    private var buf = IntArray(0)

    private class Integral(frame: Frame, val x0: Int, val y0: Int, val w: Int, val h: Int, val sum: IntArray) {
        init {
            val W = w + 1
            java.util.Arrays.fill(sum, 0, W, 0)
            for (y in 0 until h) {
                var row = 0
                val fy = y0 + y
                sum[(y + 1) * W] = 0
                for (x in 0 until w) {
                    val fx = x0 + x
                    if (fx in 0 until frame.width && fy in 0 until frame.height) {
                        val p = frame.pixels[fy * frame.width + fx]
                        row += (299 * ((p shr 16) and 255) + 587 * ((p shr 8) and 255) + 114 * (p and 255)) / 1000
                    }
                    sum[(y + 1) * W + x + 1] = sum[y * W + x + 1] + row
                }
            }
        }
        /** [ax, bx) × [ay, by) 的平均（座標為整張畫面） */
        fun mean(ax: Int, ay: Int, bx: Int, by: Int): Float {
            val x1 = (ax - x0).coerceIn(0, w); val x2 = (bx - x0).coerceIn(0, w)
            val y1 = (ay - y0).coerceIn(0, h); val y2 = (by - y0).coerceIn(0, h)
            val n = (x2 - x1) * (y2 - y1)
            if (n <= 0) return 0f
            val W = w + 1
            return (sum[y2 * W + x2] - sum[y1 * W + x2] - sum[y2 * W + x1] + sum[y1 * W + x1]).toFloat() / n
        }
    }

    /** 一格的形狀描述：左上角 (L, T)、邊長 S 的方塊 */
    private fun describe(ig: Integral, L: Int, T: Int, S: Int): FloatArray {
        val g = FloatArray(D)
        for (y in 0 until N) {
            val ya = T + y * S / N; val yb = max(ya + 1, T + (y + 1) * S / N)
            for (x in 0 until N) {
                val xa = L + x * S / N; val xb = max(xa + 1, L + (x + 1) * S / N)
                g[y * N + x] = ig.mean(xa, ya, xb, yb)
            }
        }
        fun at(a: FloatArray, x: Int, y: Int) = a[y.coerceIn(0, N - 1) * N + x.coerceIn(0, N - 1)]
        val m = FloatArray(D)
        for (y in 0 until N) for (x in 0 until N) {
            val gx = (at(g, x + 1, y - 1) + 2 * at(g, x + 1, y) + at(g, x + 1, y + 1)) - (at(g, x - 1, y - 1) + 2 * at(g, x - 1, y) + at(g, x - 1, y + 1))
            val gy = (at(g, x - 1, y + 1) + 2 * at(g, x, y + 1) + at(g, x + 1, y + 1)) - (at(g, x - 1, y - 1) + 2 * at(g, x, y - 1) + at(g, x + 1, y - 1))
            m[y * N + x] = sqrt(gx * gx + gy * gy)
        }
        val t = FloatArray(D)
        for (y in 0 until N) for (x in 0 until N) t[y * N + x] = (at(m, x, y - 1) + 2 * at(m, x, y) + at(m, x, y + 1)) / 4f
        val o = FloatArray(D)
        for (y in 0 until N) for (x in 0 until N) o[y * N + x] = (at(t, x - 1, y) + 2 * at(t, x, y) + at(t, x + 1, y)) / 4f
        var mean = 0f
        for (v in o) mean += v
        mean /= D
        for (i in 0 until D) o[i] -= mean
        return normalized(o)
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s
    }

    /** 每種珠的分數：範本與學過的形狀取最高 */
    private fun scores(d: FloatArray, extra: List<Pair<Int, FloatArray>>): FloatArray {
        val sc = FloatArray(6) { dot(d, templates[it]) }
        for ((c, p) in learned) if (c in 0..5) sc[c] = max(sc[c], dot(d, p))
        for ((c, p) in extra) if (c in 0..5) sc[c] = max(sc[c], dot(d, p))
        return sc
    }

    class Result(val colors: IntArray, val confidence: FloatArray, val shapes: Array<FloatArray>)

    /** 對位結果快取：格線不變就不重新對位（盤面位置整場戰鬥都一樣） */
    private var cacheKey: List<Float>? = null
    private var cached = floatArrayOf(0f, 0f, 0f)
    /** 對位時只用分散的 12 格試，省時間 */
    private val SAMPLE = intArrayOf(0, 2, 5, 7, 9, 13, 16, 20, 22, 24, 27, 29, 31, 34, 38, 41, 45, 47)

    @Synchronized
    fun recognize(frame: Frame, geo: BoardGeometry, extra: List<Pair<Int, FloatArray>> = emptyList()): Result {
        val s0 = geo.cell
        val mx = (s0 * 0.6f).toInt()
        val ix = (geo.left - mx).toInt(); val iy = (geo.top - mx).toInt()
        val w = (6 * s0 + 2 * mx).toInt(); val h = (geo.rows * s0 + 2 * mx).toInt()
        if (buf.size < (w + 1) * (h + 1)) buf = IntArray((w + 1) * (h + 1))
        val ig = Integral(frame, ix, iy, w, h, buf)
        val sample = SAMPLE.filter { it < geo.cells }.take(12)
        fun total(l: Float, t: Float, s: Float): Float {
            var sum = 0f
            for (i in sample) {
                val d = describe(ig, (l + (i % 6) * s).toInt(), (t + (i / 6) * s).toInt(), s.toInt())
                var b = -1f
                for (k in 0 until 6) b = max(b, dot(d, templates[k]))
                sum += b
            }
            return sum
        }
        var bl: Float; var bt: Float; var bs: Float
        val key = listOf(geo.left, geo.top, geo.cell, geo.rows.toFloat())
        if (key == cacheKey) {
            bl = cached[0]; bt = cached[1]; bs = cached[2]
        } else {
            // 整個盤面一起對位：1) 大小 2) 位置 3) 再試大小 4) 微調（以盤面中心縮放）
            bl = geo.left; bt = geo.top; bs = s0
            var best = total(bl, bt, bs)
            fun tryScale(l0: Float, t0: Float, sBase: Float, ds: Float) {
                val ss = sBase * (1 + ds)
                val l = l0 - (ss - sBase) * 3f; val t = t0 - (ss - sBase) * geo.rows / 2f
                val v = total(l, t, ss)
                if (v > best) { best = v; bl = l; bt = t; bs = ss }
            }
            for (ds in floatArrayOf(-0.06f, -0.04f, -0.02f, 0.02f, 0.04f, 0.06f)) tryScale(geo.left, geo.top, s0, ds)
            run {
                val l1 = bl; val t1 = bt; val s1 = bs
                for (dy in -2..2) for (dx in -2..2) {
                    if (dx == 0 && dy == 0) continue
                    val l = l1 + dx * 0.05f * s0; val t = t1 + dy * 0.05f * s0
                    val v = total(l, t, s1)
                    if (v > best) { best = v; bl = l; bt = t; bs = s1 }
                }
            }
            run {
                val l2 = bl; val t2 = bt; val s2 = bs
                for (ds in floatArrayOf(-0.02f, 0.02f)) tryScale(l2, t2, s2, ds)
            }
            run {
                val l3 = bl; val t3 = bt; val s3 = bs
                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val l = l3 + dx * 0.025f * s3; val t = t3 + dy * 0.025f * s3
                    val v = total(l, t, s3)
                    if (v > best) { best = v; bl = l; bt = t }
                }
            }
            cacheKey = key
            cached = floatArrayOf(bl, bt, bs)
        }
        val n = geo.cells
        val colors = IntArray(n)
        val conf = FloatArray(n)
        val shapes = Array(n) { i ->
            describe(ig, (bl + (i % 6) * bs).toInt(), (bt + (i / 6) * bs).toInt(), bs.toInt())
        }
        for (i in 0 until n) {
            val sc = scores(shapes[i], extra)
            var a = 0
            for (k in 1 until 6) if (sc[k] > sc[a]) a = k
            var b2 = -1f
            for (k in 0 until 6) if (k != a) b2 = max(b2, sc[k])
            colors[i] = a
            conf[i] = ((sc[a] - b2) / SURE_MARGIN).coerceIn(0f, 1f)
        }
        return Result(colors, conf, shapes)
    }
}
