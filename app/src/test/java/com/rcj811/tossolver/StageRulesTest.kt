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
        assertEquals(108, v.size)                       // 與 tos_solver.h 的 RULES_INTS 相同
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
