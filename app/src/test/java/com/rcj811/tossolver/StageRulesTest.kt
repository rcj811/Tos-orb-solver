package com.rcj811.tossolver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.rcj811.tossolver.StageRules.Companion.CRACK
import com.rcj811.tossolver.StageRules.Companion.FLAME_END
import com.rcj811.tossolver.StageRules.Companion.NO_TOUCH
import com.rcj811.tossolver.StageRules.Companion.SHIELD

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
        assertEquals(116, v.size)                       // 與 tos_solver.h 的 RULES_INTS 相同
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
    fun mustMarkOverridesUnsureAndNoTouch() {
        val r = StageRules().apply { toggle(0, StageRules.MUST_ORB); toggle(1, StageRules.NOTOUCH_ORB); toggle(2, StageRules.MUST_ORB) }
        val cells = intArrayOf(3 or 0x60, 2, 1 or 0x40) + IntArray(27) { it % 6 }
        r.applyOrbMarks(cells)
        assertEquals(3 or 0x40 or 0x10, cells[0])   // 沒把握的珠標必消：拿掉不能消、保留不能碰
        assertEquals(2 or 0x40, cells[1])           // 不碰(點珠)
        assertEquals(1 or 0x40 or 0x10, cells[2])   // 觸電類：不碰但要消
        assertEquals(0, r.toInts()[13])
        assertTrue(r.withoutOrbMarks().isEmpty())
        assertTrue(r.summary().contains("不碰珠 1 顆"))
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
    fun conflictsAreExplained() {
        val board = IntArray(30) { it % 6 }
        // 拼圖盾 3 格（橫）＋起點＋終點：沒有衝突（就算這盤沒把握，也照樣轉）
        val ok = StageRules().apply { toggle(13, SHIELD); toggle(14, SHIELD); toggle(15, SHIELD); green = 0; red = 29 }
        assertTrue(ok.conflicts(board, board).isEmpty())
        // 拼圖盾只標 2 格（分開）：不可能剛好消掉，要說出是哪幾格
        val two = StageRules().apply { toggle(7, SHIELD); toggle(22, SHIELD) }
        assertTrue(two.conflicts(board, board).any { it.contains("不可能剛好消掉") && it.contains("第 2 列第 2 格") })
        // L 形 4 格：轉角那格在橫三上、直的那格要在直三上 → 3 橫＋1 突出不行
        val l4 = StageRules().apply { toggle(12, SHIELD); toggle(13, SHIELD); toggle(14, SHIELD); toggle(20, SHIELD) }
        assertTrue(l4.conflicts(board, board).any { it.contains("第 4 列第 3 格") })
        // 十字 5 格可以
        val cross = StageRules().apply { for (i in intArrayOf(8, 13, 14, 15, 20)) toggle(i, SHIELD) }
        assertTrue(cross.conflicts(board, board).none { it.contains("不可能剛好消掉") })
        // 起點設在燃燒格
        val burn = StageRules().apply { toggle(0, NO_TOUCH); green = 0 }
        assertTrue(burn.conflicts(board, board).single().contains("起點"))
        // 拼圖盾格又是裂痕
        val crack = StageRules().apply { toggle(2, SHIELD); toggle(3, SHIELD); toggle(4, SHIELD); toggle(3, CRACK) }
        assertTrue(crack.conflicts(board, board).any { it.contains("裂痕") })
        // 終點不在火焰區
        val flame = StageRules().apply { toggle(10, FLAME_END); red = 29 }
        assertTrue(flame.conflicts(board, board).any { it.contains("火焰區") })
        // 拼圖盾 9 格，但每色只有 5 顆
        val big = StageRules().apply { for (i in 0 until 6) toggle(i, SHIELD); for (i in 6 until 9) toggle(i, SHIELD) }
        assertTrue(big.conflicts(board, board).any { it.contains("同色可消的珠最多只有 5 顆") })
        // 起點的珠不能碰（冰封）
        val cells = board.copyOf().also { it[0] = it[0] or 0x60 }
        assertTrue(StageRules().apply { green = 0 }.conflicts(cells, board).any { it.contains("不能碰") })
    }

    @Test
    fun mustClearAllColors() {
        val r = StageRules().apply { allMask = 0b11 }
        assertTrue(!r.isEmpty())
        assertEquals(0b11, r.toInts()[111])
        assertEquals(0b11, StageRules().apply { deserialize(r.serialize()) }.allMask)
        assertTrue(r.summary().contains("第一批全部消光"))
        assertEquals(null, r.firstBatchImpossible(IntArray(30) { it % 6 }))
        // 水只有 2 顆 → 湊不出三連
        val few = IntArray(30) { if (it < 2) 1 else intArrayOf(0, 2, 3, 4, 5)[it % 5] }
        assertTrue(r.firstBatchImpossible(few) != null)
    }

    @Test
    fun preciseConditionsUseFourDirections() {
        // 形狀、NUM、首消剛好、每組3顆、同色組數、全部消光、拼圖盾 → 只走上下左右
        assertTrue(StageRules().apply { shapeType = 2; shapeMin = 2 }.precise())
        assertTrue(StageRules().apply { firstOrbsExact = 12 }.precise())
        assertTrue(StageRules().apply { firstExact = 4 }.precise())
        assertTrue(StageRules().apply { groupMask = 0b11000 }.precise())
        assertTrue(StageRules().apply { allMask = 1 }.precise())
        assertTrue(StageRules().apply { toggle(3, SHIELD) }.precise())
        // Combo 盾、起終點、一般盤 → 照設定（可以斜走）
        assertFalse(StageRules().apply { comboMin = 7; green = 0; red = 29 }.precise())
        assertFalse(StageRules().precise())
    }

    @Test
    fun userStartBeatsDetectedGo() {
        // 「關」設的起點優先於盤面偵測到的 GO；沒設才用 GO
        val r = StageRules().apply { green = 3; red = 29; shapeType = 2; shapeMin = 2 }
        assertEquals(3, r.toInts(forceStart = 10)[10])
        assertEquals(29, r.toInts(forceStart = 10)[11])
        assertEquals(10, StageRules().apply { comboMin = 5 }.toInts(forceStart = 10)[10])
        // T 形 ×2＋起終點不是衝突
        assertTrue(r.conflicts(IntArray(30) { it % 6 }, IntArray(30) { it % 6 }).isEmpty())
    }

    @Test
    fun colorGroupShield() {
        // 光暗各 1 組（每組 3 顆）
        val r = StageRules().apply { groupMask = (1 shl 3) or (1 shl 4); groupK = 1 }
        assertFalse(r.isEmpty())
        val v = r.toInts()
        assertEquals(r.groupMask, v[114]); assertEquals(1, v[115])
        val back = StageRules().apply { deserialize(r.serialize()) }
        assertEquals(r.groupMask, back.groupMask); assertEquals(1, back.groupK)
        assertTrue(r.summary().contains("光暗"))
        assertEquals(null, r.firstBatchImpossible(IntArray(30) { it % 6 }))
        // 只選 1 色 → 不送給求解器，並說明要選 2～4 色
        val one = StageRules().apply { groupMask = 1 shl 3 }
        assertEquals(0, one.toInts()[114])
        assertTrue(one.conflicts(IntArray(30), IntArray(30) { it % 6 }).any { it.contains("2～4") })
        // 和首消禁同色 → 矛盾
        val anti = StageRules().apply { groupMask = 0b11000; antiMask = 0b1000 }
        assertTrue(anti.conflicts(IntArray(30), IntArray(30) { it % 6 }).isNotEmpty())
        // 每色 2 組要 6 顆，光只有 5 顆 → 做不到
        val two = StageRules().apply { groupMask = 0b11000; groupK = 2 }
        assertTrue(two.firstBatchImpossible(IntArray(30) { it % 6 }) != null)
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
