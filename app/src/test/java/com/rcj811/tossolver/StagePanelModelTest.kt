package com.rcj811.tossolver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * 模擬使用者在「關」面板上的點擊，檢查：常見關卡幾下就設好、點錯好回復、衝突即時說明。
 */
class StagePanelModelTest {
    private val FIRE = 0; private val WATER = 1; private val WOOD = 2; private val LIGHT = 3; private val DARK = 4; private val HEART = 5

    private fun model() = StagePanelModel(StageRules())
    private fun StagePanelModel.choice(id: String) = find(id) as Ctl.Choice
    private fun StagePanelModel.mask(id: String) = find(id) as Ctl.Mask
    /** 點數值按鈕，再點選項（用選項文字找） */
    private fun StagePanelModel.set(id: String, option: String) {
        val c = choice(id)
        tapButton(c)
        assertTrue("點「$id」後應打開選項列", open === c)
        val k = c.options.indexOf(option)
        assertTrue("「$id」應有選項 $option，實際 ${c.options}", k >= 0)
        pickOption(k)
        assertNull("選完選項列應收起", open)
    }

    @Test
    fun everyControlIsOnThePanel() {
        val m = model()
        val shown = m.brushes.size + m.buttons.size + m.masks.size
        assertEquals("每個條件模組的控制項都要出現在面板上", StageConds.CONTROLS.size, shown)
        assertEquals(10, m.brushes.size)
        // 版面跟之前一樣：筆刷 4、3、3
        assertEquals(listOf(4, 3, 3), m.brushRows().map { it.size })
        assertEquals(listOf("拼圖盾", "燃燒(不碰)", "火焰區(終點)", "黑洞(不停)"), m.brushRows()[0].map { it.name })
        assertEquals(listOf("combo", "first", "three", "attr", "num", "shape", "sticky", "fire", "groupK"), m.buttons.map { it.id })
        assertEquals(listOf("allMask", "antiMask", "groupMask"), m.masks.map { it.id })
    }

    @Test
    fun anyNumberTakesTwoTaps() {
        // 每個數值條件的每個選項：點按鈕＋點選項＝2 下
        for (c in model().buttons.filterIsInstance<Ctl.Choice>()) for (k in c.options.indices) {
            val m = model()
            val before = m.taps
            m.tapButton(m.find(c.id)); m.pickOption(k)
            assertEquals("${c.id} 第 $k 個選項", 2, m.taps - before)
            assertEquals(k, (m.find(c.id) as Ctl.Choice).selected(m.edit))
        }
    }

    @Test
    fun lightDarkOneGroupEach() {
        // 光暗各 1 組 3 顆：點光、點暗＝2 下
        val m = model()
        m.tapMask(m.mask("groupMask"), LIGHT); m.tapMask(m.mask("groupMask"), DARK)
        assertEquals(2, m.taps)
        assertTrue(m.edit.groupActive())
        assertEquals(1, m.edit.groupK)
        assertTrue(m.status(), m.status().contains("光暗 各 1 組"))
        assertFalse(m.hasConflicts())
        val v = m.edit.toInts()
        assertEquals((1 shl LIGHT) or (1 shl DARK), v[114]); assertEquals(1, v[115])
    }

    @Test
    fun woodFireLightAndTwoGroups() {
        val m = model()
        for (c in listOf(WOOD, FIRE, LIGHT)) m.tapMask(m.mask("groupMask"), c)
        assertEquals(3, m.taps)
        m.set("groupK", "各 2 組")
        assertEquals(5, m.taps)
        assertEquals(2, m.edit.groupK)
        assertTrue(m.edit.summary().contains("火木光 各 2 組"))
    }

    @Test
    fun groupNeedsTwoToFourColors() {
        val m = model()
        m.tapMask(m.mask("groupMask"), LIGHT)
        assertTrue("只選 1 色要即時說明", m.status().contains("2～4"))
        assertTrue(m.hasConflicts())
        for (c in listOf(FIRE, WATER, WOOD)) m.tapMask(m.mask("groupMask"), c)
        assertEquals(4, Integer.bitCount(m.edit.groupMask))
        m.tapMask(m.mask("groupMask"), DARK)
        assertEquals("第 5 色不會選上", 4, Integer.bitCount(m.edit.groupMask))
        assertTrue(m.status().contains("最多選 4 色"))
        // 點錯可以再點一下拿掉
        m.tapMask(m.mask("groupMask"), FIRE)
        assertEquals(3, Integer.bitCount(m.edit.groupMask))
        assertFalse(m.hasConflicts())
    }

    @Test
    fun banColorRemovesItFromMustColors() {
        val m = model()
        m.tapMask(m.mask("groupMask"), LIGHT); m.tapMask(m.mask("groupMask"), DARK)
        m.tapMask(m.mask("allMask"), WATER)
        m.tapMask(m.mask("antiMask"), LIGHT)
        assertEquals("首消不能消光 → 光從同色組數拿掉", 1 shl DARK, m.edit.groupMask)
        assertTrue(m.status(), m.status().contains("已把光從「同色組數」拿掉"))
        m.tapMask(m.mask("allMask"), LIGHT)
        assertEquals("再把光設成必消 → 從首消不能消拿掉", 0, m.edit.antiMask)
        assertTrue(m.status().contains("已把光從「首消不能消」拿掉"))
    }

    @Test
    fun numShieldSetChangeAndClear() {
        val m = model()
        m.set("num", "24")
        assertEquals(24, m.edit.firstOrbsExact)
        assertEquals(2, m.taps)
        m.set("num", "12")
        assertEquals(12, m.edit.firstOrbsExact)
        // 長按＝回到無
        m.longPressButton(m.choice("num"))
        assertEquals(0, m.edit.firstOrbsExact)
        assertTrue(m.edit.isEmpty())
    }

    @Test
    fun firstComboTurnsOnThreeEachWithNotice() {
        val m = model()
        m.set("first", "4")
        assertEquals(4, m.edit.firstExact)
        assertTrue(m.edit.first3Only)
        assertTrue("自動打開每組 3 顆要告訴使用者", m.status().contains("首消每組3顆"))
        m.tapButton(m.find("three"))
        assertFalse(m.edit.first3Only)
        m.set("first", "無")
        assertEquals(-1, m.edit.firstExact)
        assertTrue(m.edit.isEmpty())
    }

    @Test
    fun crossShieldTwo() {
        val m = model()
        m.set("shape", "十字≥2")
        assertEquals(1, m.edit.shapeType); assertEquals(2, m.edit.shapeMin)
        assertEquals(2, m.taps)
    }

    @Test
    fun pickerOpenCloseIsForgiving() {
        val m = model()
        val num = m.choice("num")
        m.tapButton(num); assertTrue(m.open === num)
        m.tapButton(num); assertNull("再點同一顆收起", m.open)
        m.tapButton(num); m.tapButton(m.choice("combo"))
        assertTrue("點另一顆換成那顆的選項", m.open === m.choice("combo"))
        m.tapMask(m.mask("allMask"), FIRE)
        assertNull("點顏色收起選項列", m.open)
        m.tapButton(num); m.selectBrush(0)
        assertNull("點筆刷收起選項列", m.open)
        assertTrue(m.edit.allMask != 0 && m.edit.firstOrbsExact == 0 && m.edit.comboMin == 0)
        m.tapButton(num)
        assertTrue("選項列打開時說明要告訴使用者怎麼做", m.status().contains("點下面一個選項"))
        assertTrue(m.label(num).endsWith("▲"))
    }

    @Test
    fun puzzleShieldPlusFirstEightCombo() {
        // 拼圖盾 3 格（預設筆刷就是拼圖盾）＋首消剛好 8 combo：5 下
        val m = model()
        for (i in listOf(13, 14, 15)) m.tapCell(i)
        m.set("first", "8")
        assertEquals(5, m.taps)
        assertTrue(m.edit.summary().contains("拼圖盾 3 格"))
        assertFalse(m.hasConflicts())
        assertTrue(m.label(m.brushes[0]).contains("(3)"))
    }

    @Test
    fun cellMarksStayConsistent() {
        val m = model()
        m.tapCell(7)                         // 拼圖盾
        m.selectBrush(m.brushes.indexOfFirst { it.name == "裂痕" }); m.tapCell(7)
        assertEquals("同一格點裂痕會拿掉拼圖盾", StageRules.CRACK, m.edit.cellFlags[7])
        m.selectBrush(m.brushes.indexOfFirst { it.name == "必消(點珠)" }); m.tapCell(7)
        assertEquals(StageRules.MUST_ORB, m.edit.cellFlags[7])
        m.selectBrush(m.brushes.indexOfFirst { it.name == "不消(點珠)" }); m.tapCell(7)
        assertEquals(StageRules.NO_ORB, m.edit.cellFlags[7])
        m.tapCell(7)
        assertEquals("再點一次取消", 0, m.edit.cellFlags[7])
        // 起點／終點：點同一格取消，點別格移過去
        val g = m.brushes.indexOfFirst { it.name == "起點(綠)" }
        m.selectBrush(g); m.tapCell(0); assertEquals(0, m.edit.green)
        m.tapCell(5); assertEquals(5, m.edit.green)
        m.tapCell(5); assertEquals(-1, m.edit.green)
    }

    @Test
    fun endOnBlackHoleShowsConflictRightAway() {
        val m = model()
        m.selectBrush(m.brushes.indexOfFirst { it.name == "黑洞(不停)" }); m.tapCell(29)
        m.selectBrush(m.brushes.indexOfFirst { it.name == "終點(紅)" }); m.tapCell(29)
        assertTrue(m.hasConflicts())
        assertTrue(m.status(), m.status().contains("黑洞"))
        m.tapCell(28)
        assertFalse(m.hasConflicts())
    }

    @Test
    fun threeConditionStagesAreQuick() {
        // 常見 3 條件關卡：每一關點擊次數不多
        val m1 = model()
        m1.tapMask(m1.mask("groupMask"), LIGHT); m1.tapMask(m1.mask("groupMask"), DARK)
        m1.set("first", "4")
        m1.selectBrush(m1.brushes.indexOfFirst { it.name == "終點(紅)" }); m1.tapCell(29)
        assertTrue("光暗各1組＋首消4C＋終點：${m1.taps} 下", m1.taps <= 6)
        assertFalse(m1.status(), m1.hasConflicts())

        val m2 = model()
        m2.set("num", "12"); m2.tapMask(m2.mask("antiMask"), WOOD)
        m2.selectBrush(m2.brushes.indexOfFirst { it.name == "起點(綠)" }); m2.tapCell(0)
        assertTrue("NUM12＋首消禁木＋起點：${m2.taps} 下", m2.taps <= 5)

        val m3 = model()
        m3.set("shape", "十字≥1"); m3.tapMask(m3.mask("allMask"), WATER); m3.tapMask(m3.mask("allMask"), FIRE)
        assertTrue("十字＋必消水火：${m3.taps} 下", m3.taps <= 4)
        assertEquals("同色組數、首消4C、自動的每組3顆、終點", 4, m1.edit.summary().split("・").size)
    }

    @Test
    fun clearAllResetsEverything() {
        val m = model()
        m.set("combo", "≥7"); m.tapCell(3); m.tapMask(m.mask("groupMask"), HEART); m.set("groupK", "各 3 組")
        m.clearAll()
        assertTrue(m.edit.isEmpty())
        assertEquals(1, m.edit.groupK)
        assertNull(m.open)
        assertEquals(0, m.brush)
    }

    @Test
    fun modulesRoundTripRandomRules() {
        // 隨機點面板 → 存檔 → 讀回 → 一模一樣（每個模組的存檔、讀檔、複製都正確）
        val rnd = Random(7)
        repeat(300) {
            val m = model()
            repeat(rnd.nextInt(1, 15)) {
                when (rnd.nextInt(4)) {
                    0 -> { m.selectBrush(rnd.nextInt(m.brushes.size)); m.tapCell(rnd.nextInt(30)) }
                    1 -> { val c = m.buttons.random(rnd); m.tapButton(c); if (c is Ctl.Choice) m.pickOption(rnd.nextInt(c.options.size)) }
                    2 -> m.tapMask(m.masks.random(rnd), rnd.nextInt(6))
                    else -> m.longPressButton(m.buttons.random(rnd))
                }
            }
            val s = m.edit.serialize()
            val back = StageRules().apply { deserialize(s) }
            assertEquals(s, back.serialize())
            assertEquals(m.edit.summary(), back.summary())
            assertTrue(m.edit.toInts().contentEquals(back.toInts()))
            val cp = StageRules().apply { copyFrom(m.edit) }
            assertEquals(s, cp.serialize())
            cp.clear()
            assertTrue(cp.isEmpty())
            // 面板上的即時衝突說明一定也在轉珠前的衝突檢查裡
            val colors = IntArray(30) { it % 6 }
            val all = m.edit.conflicts(IntArray(30) { it % 6 }, colors)
            for (c in m.edit.ruleConflicts()) assertTrue(c, all.contains(c))
        }
    }
}
