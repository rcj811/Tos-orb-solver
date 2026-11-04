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

/**
 * 符石狀態：依網路整理的神魔之塔符石種類，按「求解時怎麼處理」分成 5 類。
 *  普通：一般珠、種族符石、化血、引爆、弱化、枯藤、泡沫…（看顏色，照常消除）
 *  強化：強化（充能）符石（照常消除）
 *  風化/黏腐：碰到就停止移動（不能碰，可被消除）
 *  觸電：電擊符石（不能碰，且必須消除，否則攻擊力為 0）
 *  冰封/石化：不能碰也不能消除
 *  （隱藏、黑白、迷霧等看不出顏色的，會因辨識信心低而當成障礙避開）
 */
object OrbState {
    const val NORMAL = 0
    const val ENHANCED = 1
    const val NO_TOUCH = 2
    const val ELECTRIC = 3
    const val LOCKED = 4
}

/**
 * 符石類別：內建 5 種＋使用者自訂。每種類別有兩項規則：能不能碰、消除方式。
 * 旗標需與 tos_solver.h 一致：0x08 盡量消除、0x10 必須消除、0x20 不能消除、0x40 不能碰、0x80 盡量不要消除。
 */
class OrbType(
    val id: Int,
    val name: String,
    val noTouch: Boolean,
    val dissolve: Int,
    val builtin: Boolean = false,
    badgeText: String? = null,
) {
    companion object {
        const val D_NORMAL = 0
        const val D_MUST = 1
        const val D_PREFER = 2
        const val D_AVOID = 3
        const val D_CANNOT = 4
        val DISSOLVE_NAMES = arrayOf("照常消除", "必須消除", "盡量消除", "盡量不要消除", "不能消除")
    }

    val badge: String = badgeText ?: name.take(1)

    /** 消除方式是「不能消除」時，顏色不重要 */
    val colorMatters: Boolean get() = dissolve != D_CANNOT

    val flags: Int
        get() = (if (noTouch) 0x40 else 0) or when (dissolve) {
            D_MUST -> 0x10
            D_PREFER -> 0x08
            D_AVOID -> 0x80
            D_CANNOT -> 0x20
            else -> 0
        }

    fun describe(): String = (if (noTouch) "不能碰" else "可以碰") + "・" + DISSOLVE_NAMES[dissolve]
}

object OrbTypes {
    const val FIRST_CUSTOM_ID = 10
    val BUILTIN = listOf(
        OrbType(OrbState.NORMAL, "普通", false, OrbType.D_NORMAL, true, ""),
        OrbType(OrbState.ENHANCED, "強化", false, OrbType.D_NORMAL, true, "強"),
        OrbType(OrbState.NO_TOUCH, "風化/黏腐", true, OrbType.D_PREFER, true, "風"),
        OrbType(OrbState.ELECTRIC, "觸電", true, OrbType.D_MUST, true, "電"),
        OrbType(OrbState.LOCKED, "冰封/石化", true, OrbType.D_CANNOT, true, "冰"),
    )

    /** 使用者自訂的類別（App 啟動 / 服務連線時從儲存載入） */
    @Volatile
    var custom: List<OrbType> = emptyList()

    fun all(): List<OrbType> = BUILTIN + custom

    /** 找不到（例如已刪除的自訂類別）時當障礙：不能碰、不能消除 */
    fun get(id: Int): OrbType = BUILTIN.firstOrNull { it.id == id } ?: custom.firstOrNull { it.id == id } ?: BUILTIN[OrbState.LOCKED]

    fun exists(id: Int) = all().any { it.id == id }

    /** 校正畫面長按時的下一個類別 */
    fun next(id: Int): Int {
        val a = all()
        val i = a.indexOfFirst { it.id == id }
        return a[(i + 1) % a.size].id
    }
}

/**
 * 符石狀態的學習模型：與顏色無關的描述子（飽和度、亮度、亮白、偏暗…）＋最近原型。
 * 模擬驗證：只標註一盤，新盤面的狀態正確率約 96%。
 */
class StateModel(val protos: MutableList<FloatArray> = ArrayList(), val labels: MutableList<Int> = ArrayList()) {
    companion object {
        const val DIM = 7
        const val MATCH = 0.25f
        const val NOVEL = -2          // 看起來不像任何學過的樣子
        private const val MIN_NORMAL_FOR_NOVELTY = 5
        const val MERGE = 0.04f
        const val MAX_PER_STATE = 40
    }

    val isEmpty: Boolean get() = protos.isEmpty()

    private fun dist(a: FloatArray, b: FloatArray): Float {
        var d = 0f
        for (i in 0 until DIM) { val t = a[i] - b[i]; d += t * t }
        return kotlin.math.sqrt(d)
    }

    /**
     * 回傳狀態；沒有夠近的原型時：
     *  已學過足夠的普通珠 → NOVEL（不像普通珠也不像學過的特殊珠 → 呼叫端當障礙避開，寧可少消也不要碰到不能碰的珠）
     *  否則 → -1（當普通珠）
     * 模擬（12 種標註盤 × 10 盤）：不能碰的珠被當成可碰 26% → 1.1%，普通珠被誤當障礙 0.8%。
     */
    fun classify(d: FloatArray): Int {
        var best = -1
        var bd = Float.MAX_VALUE
        for (i in protos.indices) {
            val x = dist(d, protos[i])
            if (x < bd) { bd = x; best = i }
        }
        if (best >= 0 && bd < MATCH) return labels[best]
        return if (labels.count { it == OrbState.NORMAL } >= MIN_NORMAL_FOR_NOVELTY) NOVEL else -1
    }

    /** 學習標註好的格子；回傳新增或更新的原型數 */
    fun learn(descs: List<FloatArray>, states: List<Int>): Int {
        var n = 0
        for (k in descs.indices) {
            val st = states[k]
            if (!OrbTypes.exists(st)) continue
            val d = descs[k]
            var near = -1
            var nd = Float.MAX_VALUE
            for (i in protos.indices) if (labels[i] == st) {
                val x = dist(d, protos[i]); if (x < nd) { nd = x; near = i }
            }
            if (near >= 0 && nd < MERGE) {
                for (j in 0 until DIM) protos[near][j] = (protos[near][j] + d[j]) / 2f
            } else {
                protos.add(d.copyOf()); labels.add(st)
                if (labels.count { it == st } > MAX_PER_STATE) {
                    val drop = labels.indexOf(st); protos.removeAt(drop); labels.removeAt(drop)
                }
            }
            n++
        }
        return n
    }
}

class Recognition(
    val rows: Int,
    val colors: IntArray,          // 0~5 珠種
    val confidence: FloatArray,    // 0~1，該格取樣點投給最多票顏色的比例
    val features: Array<FloatArray>,
    val states: IntArray = IntArray(colors.size),
    val stateDesc: Array<FloatArray>? = null,
) {
    val cells: Int get() = rows * 6

    /** 看不出來的格子：普通 / 強化狀態但顏色信心不足（有特殊狀態的格子視為已辨識） */
    fun isUnknown(i: Int) = states[i] <= OrbState.ENHANCED && confidence[i] < OrbRecognizer.UNKNOWN_CONF

    /**
     * 給求解核心的格子值（珠種 + 該類別的規則旗標）。
     * 顏色重要的類別但顏色看不出來 → 當障礙（不能碰、不能消除，0x60）
     */
    fun solverCell(i: Int): Int {
        val c = colors[i]
        val t = OrbTypes.get(states[i])
        if (t.colorMatters && confidence[i] < OrbRecognizer.UNKNOWN_CONF) return c or 0x60
        return c or t.flags
    }

    fun solverCells() = IntArray(cells) { solverCell(it) }

    /** 可消除格子的珠種（不能消除的給 -1），用來判斷首消條件是否可能 */
    fun dissolvableColors() = IntArray(cells) { i -> if (solverCell(i) and 0x20 != 0) -1 else colors[i] }
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

    /**
     * 自我學習（自動執行中）：用非常有把握的普通珠，讓顏色中心慢慢貼近實際畫面。
     * rate 很小，單一盤面誤判不會把顏色帶偏。回傳採用的樣本數。
     */
    fun learnOnline(features: List<FloatArray>, labels: List<Int>, rate: Float = 0.01f): Int {
        var n = 0
        for (i in features.indices) {
            val k = labels[i]
            if (k !in 0..5) continue
            for (j in 0..2) centroids[k][j] += rate * (features[i][j] - centroids[k][j])
            n++
        }
        return n
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

    /** 預設顏色（火、水、木、光、暗、心）：取自實際遊戲截圖的珠子中位色；之後靠校正學習修正 */
    val DEFAULT_RGB = intArrayOf(0xCE280D, 0x43A3E6, 0x25C325, 0xE9A301, 0xC51FDB, 0xE02B87)

    private const val STATE_GRID = 9
    private const val STATE_SPAN = 0.70f

    /** 狀態描述子（與顏色無關）：飽和度平均/標準差、亮度平均/標準差、亮白比例、偏暗比例、低飽和比例 */
    fun stateDescriptor(frame: Frame, geo: BoardGeometry, i: Int): FloatArray {
        val f = FloatArray(3)
        val half = geo.cell * STATE_SPAN / 2f
        val step = 2f * half / STATE_GRID
        val x0 = geo.centerX(i) - half
        val y0 = geo.centerY(i) - half
        var n = 0
        var ss = 0.0; var ss2 = 0.0; var sv = 0.0; var sv2 = 0.0
        var white = 0; var dark = 0; var lowSat = 0
        for (gy in 0 until STATE_GRID) for (gx in 0 until STATE_GRID) {
            val x = (x0 + (gx + 0.5f) * step).toInt().coerceIn(0, frame.width - 1)
            val y = (y0 + (gy + 0.5f) * step).toInt().coerceIn(0, frame.height - 1)
            feature(frame.pixels[y * frame.width + x], f)
            val s = kotlin.math.hypot(f[0], f[1])
            val v = f[2]
            ss += s; ss2 += s * s; sv += v; sv2 += v * v; n++
            if (s < 0.25f && v > 0.75f) white++
            if (v < 0.25f) dark++
            if (s < 0.35f) lowSat++
        }
        val ms = ss / n
        val mv = sv / n
        return floatArrayOf(
            ms.toFloat(), kotlin.math.sqrt(max(0.0, ss2 / n - ms * ms)).toFloat(),
            mv.toFloat(), kotlin.math.sqrt(max(0.0, sv2 / n - mv * mv)).toFloat(),
            white.toFloat() / n, dark.toFloat() / n, lowSat.toFloat() / n,
        )
    }

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

    /** stateModel 為 null 時不判斷狀態（自動偵測盤面時用，速度較快） */
    fun recognize(frame: Frame, geo: BoardGeometry, model: ColorModel, stateModel: StateModel? = null): Recognition {
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
        if (stateModel == null) return Recognition(geo.rows, colors, conf, feats)
        val states = IntArray(n)
        val descs = Array(n) { stateDescriptor(frame, geo, it) }
        if (!stateModel.isEmpty) for (i in 0 until n) {
            val st = stateModel.classify(descs[i])
            states[i] = when {
                st >= 0 -> st
                st == StateModel.NOVEL -> OrbState.LOCKED   // 沒見過的樣子：當障礙
                else -> OrbState.NORMAL
            }
        }
        return Recognition(geo.rows, colors, conf, feats, states, descs)
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

    /** 辨識結果轉成盤面（珠種＋狀態），無法辨識的格子記為 -1 */
    fun maskedBoard(rec: Recognition): IntArray =
        IntArray(rec.cells) { if (rec.isUnknown(it)) -1 else rec.colors[it] + 8 * rec.states[it] }

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
