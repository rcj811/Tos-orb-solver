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
        val r = StageRules().apply { firstOrbsMin = 15 }
        assertTrue(!r.isEmpty())
        assertEquals(15, r.toInts()[109])
        assertEquals(15, StageRules().apply { deserialize(r.serialize()) }.firstOrbsMin)
        assertEquals(null, r.firstBatchImpossible(IntArray(30) { it % 6 }))
        // 能消的只有 12 顆 → 不可能 15 粒
        assertTrue(StageRules().apply { firstOrbsMin = 15 }.firstBatchImpossible(IntArray(30) { if (it < 12) it % 4 else -1 }) != null)
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
