package com.rcj811.tossolver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.imageio.ImageIO

/**
 * 真實截圖測試：使用者的石頭藤蔓造型符石（skin_board.jpg）。
 * 截圖上有校正畫面的標籤（顏色圓點、?），會混進取樣範圍，比 App 實際截到的畫面更難。
 */
class RealBoardTest {
    private val truth = "心火暗火木暗光暗心心木光暗木心木火光心暗暗火心木火火光心木水"
        .map { "火水木光暗心".indexOf(it) }.toIntArray()
    private val geo = BoardGeometry(68f, 922f, 180.5f, 5)

    private fun frame(): Frame {
        val img = ImageIO.read(javaClass.classLoader!!.getResourceAsStream("skin_board.jpg"))
        val px = IntArray(img.width * img.height)
        img.getRGB(0, 0, img.width, img.height, px, 0, img.width)
        return Frame(img.width, img.height, px)
    }

    @Test
    fun oneExamplePerColorRecognizesTheWholeBoard() {
        val f = frame()
        val model = ColorModel.defaults()
        val base = OrbRecognizer.recognize(f, geo, model)
        // 使用者每種顏色只改一顆（取每種第一次出現的位置）
        val picked = (0 until 6).map { c -> truth.indexOfFirst { it == c } }
        for (i in picked) model.addProto(base.features[i], truth[i])
        val rec = OrbRecognizer.recognize(f, geo, model)
        for (i in 0 until 30) {
            assertEquals("cell $i", truth[i], rec.colors[i])
            assertTrue("cell $i conf ${rec.confidence[i]}", !rec.isUnknown(i))
        }
    }

    @Test
    fun unlabeledColorsShowAsUnknownNotWrong() {
        val f = frame()
        val model = ColorModel.defaults()
        val base = OrbRecognizer.recognize(f, geo, model)
        // 只標了 火、心、暗
        for (c in intArrayOf(0, 5, 4)) { val i = truth.indexOfFirst { it == c }; model.addProto(base.features[i], c) }
        val rec = OrbRecognizer.recognize(f, geo, model)
        for (i in 0 until 30) {
            if (truth[i] in intArrayOf(0, 5, 4)) assertEquals("cell $i", truth[i], rec.colors[i])
            else if (!rec.isUnknown(i)) assertTrue("cell $i 不能被誤認成已標的顏色", rec.colors[i] !in intArrayOf(0, 5, 4))
        }
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
