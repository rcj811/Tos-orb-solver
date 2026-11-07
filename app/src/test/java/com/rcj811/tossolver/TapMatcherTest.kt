package com.rcj811.tossolver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class TapMatcherTest {

    private fun rgb(r: Int, g: Int, b: Int) =
        (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    /** 合成畫面：背景＋一顆按鈕（有條紋當作文字），noise = 每像素亂數雜訊幅度 */
    private fun screen(buttonColor: Int?, noise: Int, seed: Long, shiftX: Int = 0): Frame {
        val w = 1080
        val h = 2340
        val rnd = Random(seed)
        val px = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val n = if (noise > 0) rnd.nextInt(2 * noise + 1) - noise else 0
            var c = rgb(25 + n, 30 + n, 45 + n)
            if (buttonColor != null && x in (380 + shiftX)..(700 + shiftX) && y in 1500..1620) {
                val stripe = (x / 12) % 3 == 0 && y in 1540..1580
                val r = buttonColor shr 16 and 0xFF
                val g = buttonColor shr 8 and 0xFF
                val b = buttonColor and 0xFF
                c = if (stripe) rgb(250 + n, 250 + n, 250 + n) else rgb(r + n, g + n, b + n)
            }
            px[y * w + x] = c
        }
        return Frame(w, h, px)
    }

    @Test
    fun matchesSameScreenWithNoise() {
        val rec = screen(0x2E7D32, 0, 1)
        val size = TapMatcher.defaultSize(rec)
        val rule = TapRule(540f, 1560f, size, TapMatcher.signature(rec, 540f, 1560f, size)!!)
        assertTrue(TapMatcher.diff(screen(0x2E7D32, 12, 2), rule) < TapMatcher.MATCH_DIFF)
        assertEquals(0, TapMatcher.firstMatch(screen(0x2E7D32, 8, 3), listOf(rule)))
    }

    @Test
    fun rejectsOtherScreens() {
        val rec = screen(0x2E7D32, 0, 1)
        val size = TapMatcher.defaultSize(rec)
        val rule = TapRule(540f, 1560f, size, TapMatcher.signature(rec, 540f, 1560f, size)!!)
        assertTrue("沒有按鈕", TapMatcher.diff(screen(null, 5, 4), rule) >= TapMatcher.MATCH_DIFF)
        assertTrue("不同顏色的按鈕", TapMatcher.diff(screen(0xC62828, 5, 5), rule) >= TapMatcher.MATCH_DIFF)
        assertTrue("按鈕位置不同", TapMatcher.diff(screen(0x2E7D32, 5, 6, shiftX = 200), rule) >= TapMatcher.MATCH_DIFF)
        assertEquals(-1, TapMatcher.firstMatch(screen(null, 5, 7), listOf(rule)))
    }

    @Test
    fun skipAndBounds() {
        val f = screen(0x2E7D32, 0, 1)
        val size = TapMatcher.defaultSize(f)
        val rule = TapRule(540f, 1560f, size, TapMatcher.signature(f, 540f, 1560f, size)!!)
        assertEquals(-1, TapMatcher.firstMatch(f, listOf(rule)) { it == 0 })
        assertNull(TapMatcher.signature(f, 5f, 5f, size))
        assertEquals(1f, TapMatcher.diff(f, TapRule(5f, 5f, size, rule.sig)), 0f)
    }
}
