package com.rcj811.tossolver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.util.zip.InflaterInputStream

/**
 * 真實截圖測試（使用者的手機畫面，盤面區域縮小一半後存成 .rgbz）：
 *  skin_board：石頭藤蔓造型符石（截圖上有校正畫面的標籤，會混進取樣範圍，比實際更難）
 *  frozen_board：整盤冰封
 */
class RealBoardTest {
    private val truth = "心火暗火木暗光暗心心木光暗木心木火光心暗暗火心木火火光心木水"
        .map { "火水木光暗心".indexOf(it) }.toIntArray()
    private val geo = BoardGeometry(4f, 11f, 90.25f, 5)

    /** .rgbz：寬、高（int32）＋ zlib 壓縮的 RGB */
    private fun load(name: String): Frame {
        val input = DataInputStream(javaClass.classLoader!!.getResourceAsStream(name))
        val w = input.readInt()
        val h = input.readInt()
        val raw = InflaterInputStream(input).readBytes()
        val px = IntArray(w * h) { i ->
            val r = raw[i * 3].toInt() and 0xFF
            val g = raw[i * 3 + 1].toInt() and 0xFF
            val b = raw[i * 3 + 2].toInt() and 0xFF
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return Frame(w, h, px)
    }

    @Test
    fun correctedCellsBecomeKnownExamples() {
        val f = load("skin_board.rgbz")
        val model = ColorModel.defaults()
        val before = OrbRecognizer.recognize(f, geo, model)
        // 使用者每種顏色只改一顆（每種第一次出現的位置），儲存
        val picked = (0 until 6).map { c -> truth.indexOfFirst { it == c } }
        for (i in picked) model.addProto(before.features[i], truth[i])
        // 之後遇到同樣的珠子：不再是「?」，而且顏色正確
        val after = OrbRecognizer.recognize(f, geo, model)
        for (i in 0 until 30) {
            assertEquals("cell $i", truth[i], after.colors[i])
            assertTrue("cell $i conf ${after.confidence[i]}", !after.isUnknown(i))
        }
        val wrongBefore = (0 until 30).count { before.colors[it] != truth[it] || before.isUnknown(it) }
        assertTrue("改之前應該大多認不出來（$wrongBefore）", wrongBefore >= 15)
    }

    @Test
    fun unlabeledColorsShowAsUnknownNotWrong() {
        val f = load("skin_board.rgbz")
        val model = ColorModel.defaults()
        val base = OrbRecognizer.recognize(f, geo, model)
        val labeled = intArrayOf(0, 5, 4)            // 只改了 火、心、暗
        for (c in labeled) { val i = truth.indexOfFirst { it == c }; model.addProto(base.features[i], c) }
        val rec = OrbRecognizer.recognize(f, geo, model)
        for (i in 0 until 30) {
            if (truth[i] in labeled) assertEquals("cell $i", truth[i], rec.colors[i])
            else if (!rec.isUnknown(i)) assertTrue("cell $i 不能被誤認成已標的顏色", rec.colors[i] !in labeled)
        }
    }

    @Test
    fun frozenBoardLearnedFromTwoCells() {
        val normal = load("skin_board.rgbz")
        val frozen = load("frozen_board.rgbz")
        val model = ColorModel.defaults()
        val sm = StateModel()
        // 校正普通盤（30 格普通）＋在冰封盤長按標 2 顆冰封
        val rn = OrbRecognizer.recognize(normal, geo, model, sm)
        sm.learn(rn.stateDesc!!.toList(), List(30) { OrbState.NORMAL })
        val rf = OrbRecognizer.recognize(frozen, geo, model, sm)
        sm.learn(listOf(rf.stateDesc!![0], rf.stateDesc!![17]), listOf(OrbState.LOCKED, OrbState.LOCKED))
        val f2 = OrbRecognizer.recognize(frozen, geo, model, sm)
        assertEquals(30, f2.states.count { it == OrbState.LOCKED })
        val n2 = OrbRecognizer.recognize(normal, geo, model, sm)
        assertEquals(30, n2.states.count { it == OrbState.NORMAL })
    }

    @Test
    fun copyIsIndependentAndProtosMerge() {
        val m = ColorModel.defaults()
        m.addProto(floatArrayOf(0.1f, 0.1f, 0.5f), 2)
        m.addProto(floatArrayOf(0.11f, 0.1f, 0.5f), 2)          // 很接近 → 合併
        assertEquals(1, m.protos.size)
        val c = m.copy()
        c.addProto(floatArrayOf(-0.5f, 0.2f, 0.9f), 3)
        assertEquals(1, m.protos.size)
        assertEquals(2, c.protos.size)
    }
}
