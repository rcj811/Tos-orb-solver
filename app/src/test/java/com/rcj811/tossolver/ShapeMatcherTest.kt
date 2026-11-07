package com.rcj811.tossolver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.util.zip.InflaterInputStream

/** 黑白盤面（黑白符石）：用形狀邊緣判斷珠種，使用者的真實截圖 */
class ShapeMatcherTest {
    // 火0 水1 木2 光3 暗4 心5
    private val truth = intArrayOf(2, 1, 2, 2, 1, 4, 2, 4, 2, 5, 2, 3, 4, 0, 0, 2, 3, 5, 1, 0, 0, 5, 1, 4, 0, 5, 1, 5, 1, 5)

    private fun load(name: String): Frame {
        val input = DataInputStream(javaClass.classLoader!!.getResourceAsStream(name))
        val w = input.readInt()
        val h = input.readInt()
        val raw = InflaterInputStream(input).readBytes()
        val px = IntArray(w * h) { i ->
            val r = raw[i * 3].toInt() and 255; val g = raw[i * 3 + 1].toInt() and 255; val b = raw[i * 3 + 2].toInt() and 255
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return Frame(w, h, px)
    }

    @Test
    fun recognizesBlackAndWhiteBoardByShape() {
        val f = load("bw_board.rgbz")
        val geo = BoardGeometry(0f, 0f, 180f, 5)
        assertTrue(ShapeMatcher.isGray(f, geo))
        val rec = OrbRecognizer.recognize(f, geo, ColorModel.defaults(), StateModel())
        assertTrue(rec.gray)
        val ok = (0 until 30).count { rec.colors[it] == truth[it] }
        assertEquals(30, ok)
        assertEquals(0, rec.unknownCount())
    }

    @Test
    fun toleratesSlightlyMisalignedGrid() {
        val f = load("bw_board.rgbz")
        for (g in listOf(BoardGeometry(10f, 0f, 180f, 5), BoardGeometry(0f, -10f, 180f, 5), BoardGeometry(-6f, 4f, 186f, 5))) {
            val r = ShapeMatcher.recognize(f, g)
            val ok = (0 until 30).count { r.colors[it] == truth[it] }
            assertTrue("對位偏差時 $ok/30", ok >= 29)
        }
    }

    @Test
    fun colorBoardIsNotGray() {
        val input = DataInputStream(javaClass.classLoader!!.getResourceAsStream("skin_board.rgbz"))
        val w = input.readInt(); val h = input.readInt()
        val raw = InflaterInputStream(input).readBytes()
        val px = IntArray(w * h) { i -> (0xFF shl 24) or ((raw[i * 3].toInt() and 255) shl 16) or ((raw[i * 3 + 1].toInt() and 255) shl 8) or (raw[i * 3 + 2].toInt() and 255) }
        assertFalse(ShapeMatcher.isGray(Frame(w, h, px), BoardGeometry(4f, 11f, 90.25f, 5)))
    }

    @Test
    fun learnedShapesAreUsed() {
        val f = load("bw_board.rgbz")
        val geo = BoardGeometry(0f, 0f, 180f, 5)
        val base = ShapeMatcher.recognize(f, geo)
        // 把第 0 格的形狀教成「光」：同一格就會被認成光
        val r = ShapeMatcher.recognize(f, geo, listOf(3 to base.shapes[0]))
        assertEquals(3, r.colors[0])
    }
}
