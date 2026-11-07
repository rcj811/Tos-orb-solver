package com.rcj811.tossolver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StageRulesTest {

    @Test
    fun emptyEncodesAsNoRules() {
        val r = StageRules()
        assertTrue(r.isEmpty())
        assertEquals(0, r.toInts().size)
    }

    @Test
    fun encodingMatchesNativeLayout() {
        val r = StageRules()
        r.comboMin = 7; r.firstMin = 4; r.firstExact = 3
        r.antiMask = 1 shl 5; r.elemMask = 0b111
        r.sticky = true; r.green = 0; r.red = 29
        for (i in intArrayOf(8, 13, 14, 15, 20)) r.toggle(i, StageRules.SHIELD)
        r.toggle(3, StageRules.FLAME_END)
        r.toggle(2, StageRules.BLACK_HOLE)
        val v = r.toInts()
        assertEquals(StageRules.INTS, v.size)
        assertEquals(112, v.size)                       // 與 tos_solver.h 的 RULES_INTS 相同
        assertEquals(1, v[0])
        assertEquals(7, v[1]); assertEquals(4, v[2]); assertEquals(3, v[3])
        assertEquals(32, v[4]); assertEquals(7, v[5])
        assertEquals(1, v[6]); assertEquals(0, v[7])
        assertEquals(0, v[10]); assertEquals(29, v[11])
        assertEquals(StageRules.SHIELD, v[12 + 14])
        assertEquals(StageRules.FLAME_END, v[12 + 3])
        assertEquals(StageRules.BLACK_HOLE, v[12 + 2])
        assertEquals(-1, v[12 + 48])                    // 變形魔球：無
        assertEquals(5, r.count(StageRules.SHIELD))
    }

    @Test
    fun attrShieldEncodingAndFeasibility() {
        val r = StageRules().apply { firstAttrMin = 8; antiMask = 1 shl 1 }   // 截圖的王：首消≥8組屬性、水連擊零化
        val v = r.toInts()
        assertEquals(8, v[108])
        // 一般隨機盤：去掉水和心後只剩 4 色約 20 顆 → 最多 6 組，不可能 8 組
        val board = IntArray(30) { it % 6 }
        assertTrue(r.firstBatchImpossible(board) != null)
        // 全部都是可用顏色（每色 ≥6 顆）→ 可能
        val rich = IntArray(30) { intArrayOf(0, 2, 3, 4)[it % 4] }
        assertEquals(null, StageRules().apply { firstAttrMin = 8 }.firstBatchImpossible(rich))
        val back = StageRules().apply { deserialize(r.serialize()) }
        assertEquals(8, back.firstAttrMin)
        val merged = StageRules().apply { comboMin = 7; mergeFrom(r) }
        assertEquals(7, merged.comboMin); assertEquals(8, merged.firstAttrMin); assertEquals(2, merged.antiMask)
    }

    @Test
    fun firstOrbsShield() {
        val r = StageRules().apply { firstOrbsExact = 15 }
        assertTrue(!r.isEmpty())
        assertEquals(15, r.toInts()[109])
        assertEquals(15, StageRules().apply { deserialize(r.serialize()) }.firstOrbsExact)
        assertEquals(null, r.firstBatchImpossible(IntArray(30) { it % 6 }))
        // 每色 5 顆：14 = 5+5+4 湊得出來
        assertEquals(null, StageRules().apply { firstOrbsExact = 14 }.firstBatchImpossible(IntArray(30) { it % 6 }))
        // 4 色各 3 顆（只能 0/3/6/9/12）→ 剛好 15 粒不可能
        assertTrue(StageRules().apply { firstOrbsExact = 10 }.firstBatchImpossible(IntArray(30) { if (it < 12) it % 4 else -1 }) != null)
        // 能消的只有 12 顆 → 不可能 15 粒
        assertTrue(StageRules().apply { firstOrbsExact = 15 }.firstBatchImpossible(IntArray(30) { if (it < 12) it % 4 else -1 }) != null)
    }

    @Test
    fun mustColorsFeasibility() {
        val board = IntArray(30) { it % 6 }                       // 每色 5 顆
        assertEquals(null, StageRules().apply { elemMask = 0b11111 }.firstBatchImpossible(board))
        val fewWater = IntArray(30) { if (it < 2) 1 else intArrayOf(0, 2, 3, 4, 5)[it % 5] }   // 水只有 2 顆
        assertTrue(StageRules().apply { elemMask = 0b11 }.firstBatchImpossible(fewWater) != null)
        // 兩個條件同時設定：NUM 12 ＋ 必消水火
        val two = StageRules().apply { firstOrbsExact = 12; elemMask = 0b11 }
        assertEquals(null, two.firstBatchImpossible(board))
        assertEquals(12, two.toInts()[109]); assertEquals(0b11, two.toInts()[5])
    }

    @Test
    fun tappedOrbMarks() {
        val r = StageRules().apply { toggle(0, StageRules.MUST_ORB); toggle(5, StageRules.NO_ORB); firstOrbsExact = 12 }
        val cells = IntArray(30) { it % 6 }
        r.applyOrbMarks(cells)
        assertEquals(0 or 0x10, cells[0]); assertEquals(5 or 0x20, cells[5]); assertEquals(1, cells[1])
        assertEquals(0, r.toInts()[12])                       // 點珠標記不當格子規則傳給求解器
        val saved = r.withoutOrbMarks()
        assertEquals(0, saved.count(StageRules.MUST_ORB)); assertEquals(12, saved.firstOrbsExact)
        assertTrue(StageRules().apply { toggle(0, StageRules.MUST_ORB) }.withoutOrbMarks().isEmpty())
        assertEquals(null, r.firstBatchImpossible(IntArray(30) { it % 6 }))
        // 火只有 2 顆卻標必消 → 不可能
        val few = IntArray(30) { if (it < 2) 0 else 1 + it % 5 }
        assertTrue(StageRules().apply { toggle(0, StageRules.MUST_ORB) }.firstBatchImpossible(few) != null)
    }

    @Test
    fun firstThreeOnly() {
        val r = StageRules().apply { firstExact = 4; first3Only = true }
        assertEquals(1, r.toInts()[110]); assertEquals(4, r.toInts()[3])
        assertTrue(StageRules().apply { deserialize(r.serialize()) }.first3Only)
        assertTrue(r.summary().contains("每組 3 顆"))
        // 4 combo × 3 顆 = 12 粒，跟 NUM 15 矛盾
        assertTrue(StageRules().apply { firstExact = 4; first3Only = true; firstOrbsExact = 15 }.firstBatchImpossible(IntArray(30) { it % 6 }) != null)
        assertEquals(null, StageRules().apply { firstExact = 4; first3Only = true; firstOrbsExact = 12 }.firstBatchImpossible(IntArray(30) { it % 6 }))
    }

    @Test
    fun toggleTwiceClears() {
        val r = StageRules()
        r.toggle(10, StageRules.SHIELD)
        r.toggle(10, StageRules.SHIELD)
        assertTrue(r.isEmpty())
    }

    @Test
    fun serializeRoundTrip() {
        val r = StageRules()
        r.comboMin = 8; r.firstExact = 2; r.elemMask = 0b101010; r.firePath = true; r.red = 17
        r.toggle(5, StageRules.CRACK); r.toggle(6, StageRules.NO_TOUCH or StageRules.SHIELD)
        val back = StageRules().apply { deserialize(r.serialize()) }
        assertTrue(r.toInts().contentEquals(back.toInts()))
        assertEquals(r.summary(), back.summary())
        assertFalse(back.isEmpty())
    }
}
