package com.rcj811.tossolver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillParserTest {

    /** 使用者截圖（未知惡靈・澤西惡魔）的技能視窗，模擬 OCR 讀出的文字 */
    private val jerseyDevil = """
        未知惡靈・澤西惡魔 ⓘ
        666   670,000,000
        ▶【水連擊零化】
        ▶【水之佑律】
        ▶【首消 ≥8 組屬性符石盾】
        ▶【終結倒數】
    """.trimIndent()

    @Test
    fun parsesFirstOrbsShield() {
        for (t in listOf("【首消≥15粒符石盾】", "【首消 ≧15 粒符石盾】", "【首批消除15粒或以上符石】", "【符石數量盾≥15】"))
            assertEquals(t, 15, SkillParser.parse(t).rules.firstOrbsMin)
        assertEquals(0, SkillParser.parse("【首消≥8組屬性符石盾】").rules.firstOrbsMin)
    }

    @Test
    fun parsesTheScreenshotBoss() {
        val r = SkillParser.parse(jerseyDevil)
        assertEquals(listOf("水連擊零化", "水之佑律", "首消≥8組屬性符石盾", "終結倒數"), r.skills)
        assertEquals(8, r.rules.firstAttrMin)
        assertEquals(1 shl 1, r.rules.antiMask)           // 首消不能消水
        assertEquals(0, r.rules.firstMin)
        assertEquals(listOf("終結倒數"), r.noEffect)
        assertEquals(listOf("水之佑律"), r.unknown)       // 查不到定義，不亂猜
        assertTrue(r.manual.isEmpty())
        // 這個王的條件在一般盤面上不可能（需要角色技能）
        assertTrue(r.rules.firstBatchImpossible(IntArray(30) { it % 6 }) != null)
    }

    @Test
    fun toleratesOcrNoise() {
        for (t in listOf("[首消 ≧ 8 組屬性符石盾]", "【首消>8组属性符石盾】", "「首消８組屬性符石盾」")) {
            assertEquals(t, 8, SkillParser.parse(t).rules.firstAttrMin)
        }
        assertEquals(1 shl 5, SkillParser.parse("〔心連撃零化〕").rules.antiMask)
    }

    @Test
    fun mapsCommonShields() {
        val r = SkillParser.parse("【≥7連擊盾】【首消≥5連擊盾】【固定3連擊盾】【反首消心符石盾】【水火木三屬盾】【黏腐之軌】")
        assertEquals(7, r.rules.comboMin)
        assertEquals(5, r.rules.firstMin)
        assertEquals(3, r.rules.firstExact)
        assertEquals(1 shl 5, r.rules.antiMask)
        assertEquals(0b111, r.rules.elemMask)
        assertTrue(r.rules.sticky)
        assertEquals(6, r.applied.size)
        assertEquals(0b11111, SkillParser.parse("【五屬盾】").rules.elemMask)
    }

    @Test
    fun shapesAndTerrainNeedTheUser() {
        val r = SkillParser.parse("【指定形狀盾】【起結有序】【電擊符石】【攻前盾】")
        assertEquals(2, r.manual.size)
        assertEquals(listOf("電擊符石"), r.byRecognition)
        assertEquals(listOf("攻前盾"), r.noEffect)
        assertTrue(r.rules.isEmpty())
        val generic = SkillParser.parse("【三屬盾】")
        assertEquals(1, generic.manual.size)
    }

    @Test
    fun nothingFoundWithoutBrackets() {
        val r = SkillParser.parse("戰鬥中 HP 12345")
        assertFalse(r.found)
    }
}
