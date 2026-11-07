package com.rcj811.tossolver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sin

/** 符石狀態：只標註一盤，新盤面要認得出強化 / 風化 / 觸電 / 冰封 */
class StateModelTest {
    private val w = 1080
    private val h = 2340
    private val geo = BoardGeometry(0f, 1200f, 180f, 5)

    private fun cl(v: Float) = v.toInt().coerceIn(0, 255)
    private fun rgb(r: Float, g: Float, b: Float) = (0xFF shl 24) or (cl(r) shl 16) or (cl(g) shl 8) or cl(b)

    private fun render(board: IntArray, states: IntArray, rnd: Random): Frame {
        val px = IntArray(w * h) { rgb(40f, 32f, 26f) }
        val c = geo.cell
        for (i in 0 until geo.cells) {
            val col = OrbRecognizer.DEFAULT_RGB[board[i]]
            val cr = (col shr 16 and 0xFF).toFloat()
            val cg = (col shr 8 and 0xFF).toFloat()
            val cb = (col and 0xFF).toFloat()
            val cx = geo.centerX(i)
            val cy = geo.centerY(i)
            val rad = c * 0.44f
            for (y in (cy - rad).toInt()..(cy + rad).toInt()) for (x in (cx - rad).toInt()..(cx + rad).toInt()) {
                val d = hypot(x - cx, y - cy)
                if (d > rad) continue
                val shade = 1f - 0.35f * (d / rad)
                val n = (rnd.nextInt(31) - 15).toFloat()
                var r = cr * shade + n
                var g = cg * shade + n
                var b = cb * shade + n
                if (hypot(x - (cx + c * 0.06f), y - (cy - c * 0.05f)) < c * 0.10f) { r = 250f; g = 250f; b = 245f }
                when (states[i]) {
                    OrbState.ENHANCED -> {
                        r = r * 1.15f + 25; g = g * 1.15f + 25; b = b * 1.15f + 25
                        if (d > rad * 0.70f && d < rad * 0.85f) { r = 255f; g = 250f; b = 220f }
                    }
                    OrbState.NO_TOUCH -> {
                        val gray = (r + g + b) / 3f / 128f
                        r = r * 0.3f + 120 * 0.7f * gray; g = g * 0.3f + 105 * 0.7f * gray; b = b * 0.3f + 90 * 0.7f * gray
                        if (abs((x - cx) - (y - cy) * 0.6f) % (c * 0.25f) < c * 0.03f) { r = 40f; g = 35f; b = 30f }
                    }
                    OrbState.ELECTRIC -> {
                        val z = abs(((x - cx + rad) * 0.08f + sin((y - cy + rad) * 0.15f) * 3f) % 6f - 3f) < 0.6f
                        if (z) { r = 255f; g = 255f; b = 160f }
                    }
                    OrbState.LOCKED -> { r = r * 0.4f + 190 * 0.6f; g = g * 0.4f + 225 * 0.6f; b = b * 0.4f + 255 * 0.6f }
                }
                px[y * w + x] = rgb(r, g, b)
            }
        }
        return Frame(w, h, px)
    }

    private fun sample(seed: Long, p: Double): Triple<IntArray, IntArray, Frame> {
        val rnd = Random(seed)
        val board = IntArray(30) { rnd.nextInt(6) }
        val states = IntArray(30) { if (rnd.nextDouble() < p) 1 + rnd.nextInt(4) else 0 }
        return Triple(board, states, render(board, states, rnd))
    }

    @Test
    fun learnsStatesFromOneBoard() {
        val model = ColorModel.defaults()
        val sm = StateModel()
        // 使用者在校正畫面標註一盤（約 45% 是特殊符石）
        val (_, s1, f1) = sample(1, 0.45)
        val r1 = OrbRecognizer.recognize(f1, geo, model, sm)
        sm.learn(r1.stateDesc!!.toList(), s1.toList())
        var ok = 0
        var tot = 0
        var danger = 0
        var special = 0
        for (seed in 10L until 20L) {
            val (_, s, f) = sample(seed, 0.3)
            val r = OrbRecognizer.recognize(f, geo, model, sm)
            for (i in 0 until 30) {
                tot++
                if (r.states[i] == s[i]) ok++
                if (s[i] >= OrbState.NO_TOUCH) {
                    special++
                    if (r.states[i] <= OrbState.ENHANCED && !r.isUnknown(i)) danger++   // 不能碰的被當成可以碰
                }
            }
        }
        val acc = ok.toFloat() / tot
        assertTrue("狀態正確率 $acc", acc >= 0.85f)
        assertTrue("危險誤判 $danger / $special", danger <= special * 0.08f)
    }

    @Test
    fun solverFlagsFollowStates() {
        val rec = Recognition(5, IntArray(30) { 1 }, FloatArray(30) { 0.9f }, Array(30) { FloatArray(3) },
            IntArray(30) { it % 5 })
        assertEquals(1, rec.solverCell(0))            // 普通
        assertEquals(1, rec.solverCell(1))            // 強化：照常
        assertEquals(1 or 0x48, rec.solverCell(2))    // 風化/黏腐：不碰、盡量消
        assertEquals(1 or 0x50, rec.solverCell(3))    // 觸電：不碰、必消
        assertEquals(1 or 0x60, rec.solverCell(4))    // 冰封/石化：不碰、不消
        assertEquals(-1, rec.dissolvableColors()[4])
        assertEquals(0, rec.unknownCount())
    }

    @Test
    fun customTypesMapToSolverFlags() {
        OrbTypes.custom = listOf(
            OrbType(10, "化血", false, OrbType.D_MUST),        // 可以碰、必須消除
            OrbType(11, "弱化", false, OrbType.D_AVOID),       // 可以碰、盡量不要消除
            OrbType(12, "刺血", true, OrbType.D_NORMAL),       // 不能碰、照常消除
            OrbType(13, "石牆", false, OrbType.D_CANNOT),      // 可以碰、不能消除
        )
        try {
            val rec = Recognition(5, IntArray(30) { 2 }, FloatArray(30) { 0.9f }, Array(30) { FloatArray(3) },
                IntArray(30) { if (it < 4) 10 + it else 0 })
            assertEquals(2 or 0x10, rec.solverCell(0))
            assertEquals(2 or 0x80, rec.solverCell(1))
            assertEquals(2 or 0x40, rec.solverCell(2))
            assertEquals(2 or 0x20, rec.solverCell(3))
            assertEquals(-1, rec.dissolvableColors()[3])
            // 已刪除的類別 → 當障礙
            val gone = Recognition(5, IntArray(30), FloatArray(30) { 0.9f }, Array(30) { FloatArray(3) }, IntArray(30) { 99 })
            assertEquals(0x60, gone.solverCell(0))
            // 長按循環：內建 → 自訂 → 回到普通
            assertEquals(OrbState.START, OrbTypes.next(OrbState.LOCKED))
            assertEquals(10, OrbTypes.next(OrbState.START))
            // GO 起點：顏色信心低也不當障礙，且找得到起點格
            val go = Recognition(5, IntArray(30) { 3 }, FloatArray(30) { if (it == 19) 0.3f else 0.9f }, Array(30) { FloatArray(3) },
                IntArray(30) { if (it == 19) OrbState.START else 0 })
            assertEquals(19, go.startCell())
            assertEquals(3, go.solverCell(19))
            assertEquals(-1, rec.startCell())
            assertEquals(OrbState.NORMAL, OrbTypes.next(13))
            assertEquals("化", OrbTypes.get(10).badge)
            assertEquals("可以碰・必須消除", OrbTypes.get(10).describe())
        } finally {
            OrbTypes.custom = emptyList()
        }
    }

    @Test
    fun emptyModelKeepsEverythingNormal() {
        val (_, _, f) = sample(3, 0.5)
        val r = OrbRecognizer.recognize(f, geo, ColorModel.defaults(), StateModel())
        assertTrue(r.states.all { it == OrbState.NORMAL })
    }
}
