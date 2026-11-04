package com.rcj811.tossolver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.hypot

/** 用合成的「遊戲畫面」驗證盤面偵測與符石辨識 */
class OrbRecognizerTest {

    private fun clamp(v: Int) = v.coerceIn(0, 255)
    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (clamp(r) shl 16) or (clamp(g) shl 8) or clamp(b)

    /** 畫一個盤面：每格是帶雜訊的彩色圓珠，中央有白色高光圖示；上方是雜亂的彩色 UI */
    private fun render(
        w: Int, h: Int, geo: BoardGeometry, board: IntArray, rnd: Random,
        palette: IntArray = OrbRecognizer.DEFAULT_RGB,
    ): Frame {
        val px = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            px[y * w + x] = if (y < geo.top) {
                // 上方 UI：隨機彩色方塊
                val bx = x / 90
                val by = y / 90
                val r = Random((bx * 7919 + by * 104729).toLong())
                rgb(r.nextInt(256), r.nextInt(256), r.nextInt(256))
            } else rgb(40, 32, 26)
        }
        for (i in 0 until geo.cells) {
            val c = palette[board[i]]
            val cr = c shr 16 and 0xFF
            val cg = c shr 8 and 0xFF
            val cb = c and 0xFF
            val cx = geo.centerX(i)
            val cy = geo.centerY(i)
            val rad = geo.cell * 0.44f
            val x0 = (cx - rad).toInt()
            val y0 = (cy - rad).toInt()
            for (y in y0..(cy + rad).toInt()) for (x in x0..(cx + rad).toInt()) {
                if (x !in 0 until w || y !in 0 until h) continue
                val d = hypot(x - cx, y - cy)
                if (d > rad) continue
                val shade = 1f - 0.35f * (d / rad)   // 邊緣較暗
                val icon = hypot(x - (cx + geo.cell * 0.06f), y - (cy - geo.cell * 0.05f)) < geo.cell * 0.10f
                val n = rnd.nextInt(31) - 15
                px[y * w + x] = if (icon) rgb(250, 250, 245)
                else rgb((cr * shade).toInt() + n, (cg * shade).toInt() + n, (cb * shade).toInt() + n)
            }
        }
        return Frame(w, h, px)
    }

    private fun randomBoard(rows: Int, rnd: Random): IntArray {
        val b = IntArray(rows * 6)
        do {
            for (i in b.indices) b[i] = rnd.nextInt(6)
        } while (hasMatch(b, rows))
        return b
    }

    private fun hasMatch(b: IntArray, rows: Int): Boolean {
        for (y in 0 until rows) for (x in 0 until 4) if (b[y * 6 + x] == b[y * 6 + x + 1] && b[y * 6 + x] == b[y * 6 + x + 2]) return true
        for (x in 0 until 6) for (y in 0 until rows - 2) if (b[y * 6 + x] == b[(y + 1) * 6 + x] && b[y * 6 + x] == b[(y + 2) * 6 + x]) return true
        return false
    }

    @Test
    fun recognizesKnownBoard() {
        val rnd = Random(1)
        val w = 1080
        val h = 2340
        val geo = BoardGeometry(0f, 2340f - 120f - 900f, 180f, 5)
        val board = randomBoard(5, rnd)
        val frame = render(w, h, geo, board, rnd)
        val rec = OrbRecognizer.recognize(frame, geo, ColorModel.defaults())
        for (i in 0 until geo.cells) assertEquals("cell $i", board[i], rec.colors[i])
        assertTrue("min confidence ${rec.minConfidence()}", rec.minConfidence() >= OrbRecognizer.READY_CONF)
    }

    @Test
    fun autoDetectFindsBoard() {
        for ((seed, setup) in listOf(
            1L to Triple(1080, 2340, 0f),      // 一般手機，盤面滿版
            2L to Triple(1440, 3120, 0f),      // 高解析度
            3L to Triple(1080, 2400, 54f),     // 左右有邊距
        )) {
            val (w, h, margin) = setup
            val rnd = Random(seed)
            val cell = (w - 2 * margin) / 6f
            val geo = BoardGeometry(margin, h - h * 0.05f - cell * 5, cell, 5)
            val board = randomBoard(5, rnd)
            val frame = render(w, h, geo, board, rnd)
            val found = OrbRecognizer.autoDetect(frame, 5, ColorModel.defaults())
            assertNotNull("seed $seed", found)
            found!!
            assertTrue("left ${found.left} vs ${geo.left}", abs(found.left - geo.left) < cell * 0.12f)
            assertTrue("top ${found.top} vs ${geo.top}", abs(found.top - geo.top) < cell * 0.12f)
            assertTrue("cell ${found.cell} vs ${geo.cell}", abs(found.cell - geo.cell) < cell * 0.05f)
            val rec = OrbRecognizer.recognize(frame, found, ColorModel.defaults())
            for (i in 0 until 30) assertEquals("seed $seed cell $i", board[i], rec.colors[i])
        }
    }

    @Test
    fun learningAdaptsToDifferentColors() {
        // 遊戲實際顏色和預設不同（例如換了符石外觀）：學習一次後應能辨識
        val skin = intArrayOf(0xC83C1E, 0x1E9BC8, 0x2D9B50, 0xDCB43C, 0x823CAA, 0xDC5A96)
        val rnd = Random(5)
        val geo = BoardGeometry(0f, 1200f, 180f, 5)
        val model = ColorModel.defaults()
        val b1 = randomBoard(5, rnd)
        val f1 = render(1080, 2340, geo, b1, rnd, skin)
        val r1 = OrbRecognizer.recognize(f1, geo, model)
        // 使用者在校正畫面把所有格子標成正確顏色後學習
        model.learn(r1.features.toList(), b1.toList())
        val b2 = randomBoard(5, rnd)
        val f2 = render(1080, 2340, geo, b2, rnd, skin)
        val r2 = OrbRecognizer.recognize(f2, geo, model)
        for (i in 0 until 30) assertEquals("cell $i", b2[i], r2.colors[i])
        assertTrue(r2.minConfidence() >= OrbRecognizer.READY_CONF)
    }

    @Test
    fun blankScreenIsNotABoard() {
        val px = IntArray(1080 * 2340) { rgb(30, 30, 30) }
        assertNull(OrbRecognizer.autoDetect(Frame(1080, 2340, px), 5, ColorModel.defaults()))
    }

    @Test
    fun geometryHelpers() {
        val g = BoardGeometry(10f, 100f, 50f, 5)
        assertEquals(35f, g.centerX(0), 1e-4f)
        assertEquals(125f, g.centerY(0), 1e-4f)
        assertEquals(29, g.cellAt(g.centerX(29), g.centerY(29)))
        assertEquals(-1, g.cellAt(0f, 0f))
        val g6 = g.withRows(6)
        assertEquals(g.bottom, g6.bottom, 1e-4f)
    }
}
