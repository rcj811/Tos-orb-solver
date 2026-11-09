package com.rcj811.tossolver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.hypot

/** 關卡自動判斷：同一關（有雜訊、血條變化）要認得，不同敵人不能認錯 */
class StageMatcherTest {
    private val w = 1080
    private val h = 2340
    private val geo = BoardGeometry(0f, 1320f, 180f, 5)

    private fun rgb(r: Int, g: Int, b: Int) =
        (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    /** 敵人：(中心 x, 中心 y, 半徑, 顏色)；hp = 血條長度比例 */
    private fun battle(enemies: List<IntArray>, hp: Float, seed: Long): Frame {
        val rnd = Random(seed)
        val px = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val n = rnd.nextInt(25) - 12
            var c = rgb(30 + y / 40 + n, 25 + n, 50 + n)              // 背景漸層
            for (e in enemies) {
                if (hypot((x - e[0]).toFloat(), (y - e[1]).toFloat()) < e[2]) {
                    c = rgb((e[3] shr 16 and 0xFF) + n, (e[3] shr 8 and 0xFF) + n, (e[3] and 0xFF) + n)
                }
            }
            if (y in 1000..1030 && x in 100 until (100 + 880 * hp).toInt()) c = rgb(220, 40, 40)   // 敵人血條
            px[y * w + x] = c
        }
        return Frame(w, h, px)
    }

    private val stageA = listOf(intArrayOf(540, 600, 250, 0xC83C3C), intArrayOf(220, 750, 110, 0xE0C040))
    private val stageB = listOf(intArrayOf(540, 600, 250, 0x3CA0C8), intArrayOf(860, 700, 120, 0x50C050))

    @Test
    fun recognizesSameStage() {
        val rec = StageMatcher.signature(battle(stageA, 1f, 1), geo)!!
        val presets = listOf(StagePreset(rec, StageRules().apply { comboMin = 7 }))
        val later = StageMatcher.signature(battle(stageA, 0.35f, 2), geo)   // 打了幾回合：血條變短、雜訊不同
        val (idx, d) = StageMatcher.best(later, presets)
        assertEquals("diff=$d", 0, idx)
    }

    @Test
    fun rejectsOtherStages() {
        val presets = listOf(StagePreset(StageMatcher.signature(battle(stageA, 1f, 1), geo)!!, StageRules()))
        val (i1, d1) = StageMatcher.best(StageMatcher.signature(battle(stageB, 1f, 3), geo), presets)
        assertEquals("不同敵人 diff=$d1", -1, i1)
        val (i2, d2) = StageMatcher.best(StageMatcher.signature(battle(emptyList(), 1f, 4), geo), presets)
        assertEquals("沒有敵人 diff=$d2", -1, i2)
    }

    @Test
    fun picksTheRightOneAmongSeveral() {
        val presets = listOf(
            StagePreset(StageMatcher.signature(battle(stageA, 1f, 1), geo)!!, StageRules().apply { comboMin = 7 }),
            StagePreset(StageMatcher.signature(battle(stageB, 1f, 5), geo)!!, StageRules().apply { firstMin = 5 }),
        )
        assertEquals(1, StageMatcher.best(StageMatcher.signature(battle(stageB, 0.6f, 6), geo), presets).first)
        assertEquals(0, StageMatcher.best(StageMatcher.signature(battle(stageA, 0.6f, 7), geo), presets).first)
    }

    @Test
    fun differentSizedSignaturesNeverMatch() {
        assertTrue(ScreenSig.meanAbsDiff(FloatArray(4), FloatArray(5)) == 1f)
    }
}

/** 關卡自我學習：記住不同樣子、統計、顏色線上學習 */
class SelfLearningTest {
    @Test
    fun stageRemembersNewLooks() {
        val a = FloatArray(480) { 0.2f }
        val p = StagePreset(a, StageRules().apply { comboMin = 7 })
        // 第二波：畫面差 0.04（仍在門檻內）→ 多記一種樣子
        val wave2 = FloatArray(480) { 0.24f }
        p.learn(wave2)
        assertEquals(2, p.sigs.size)
        // 第三波：差 0.08，原本認不出來；但和第二波只差 0.04 → 認得
        val wave3 = FloatArray(480) { 0.28f }
        assertTrue(p.diff(wave3) < StageMatcher.MATCH_DIFF)
        assertEquals(0, StageMatcher.best(wave3, listOf(p)).first)
        // 幾乎一樣的畫面不會一直增加，而是微調
        p.learn(FloatArray(480) { 0.201f })
        assertEquals(2, p.sigs.size)
        // 改機制保留樣子
        val q = p.withRules(StageRules().apply { firstMin = 5 }, wave3)
        assertEquals(3, q.sigs.size)
        assertEquals(5, q.rules.firstMin)
    }

    @Test
    fun hardStageFlag() {
        val p = StagePreset(FloatArray(10), StageRules())
        p.met = 4; p.unmet = 2
        assertTrue(!p.hard)
        p.unmet = 3
        assertTrue(p.hard)
    }

    @Test
    fun onlineColorLearningMovesSlowly() {
        val m = ColorModel.defaults()
        val before = m.centroids[1].copyOf()
        val target = floatArrayOf(before[0] + 0.2f, before[1], before[2])
        m.learnOnline(List(10) { target }, List(10) { 1 })
        val moved = m.centroids[1][0] - before[0]
        assertTrue("moved=$moved", moved > 0.01f && moved < 0.05f)   // 慢慢靠近，不會一次跳過去
    }
}
