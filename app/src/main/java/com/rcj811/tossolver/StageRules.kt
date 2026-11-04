package com.rcj811.tossolver

// 不依賴 Android API（可在 JVM 單元測試中驗證）

/**
 * 這一關的機制（在工作列「關」設定），編碼後傳給 C++ 求解核心。
 * 編碼格式需與 tos_solver.h 的 RULES_INTS 說明一致。
 */
class StageRules {
    companion object {
        const val MAX_CELLS = 48
        const val INTS = 12 + MAX_CELLS * 2

        const val SHIELD = 1        // 拼圖盾格
        const val CRACK = 2         // 裂痕（不能消除）
        const val NO_TOUCH = 4      // 燃燒 / 地獄火 / 黏手之地 / 雷射（不能碰）
        const val FLAME_END = 8     // 火焰區（最後一步要停在其中一格）
        const val BLACK_HOLE = 16   // 黑洞（最後一步不能停在這）

        val ORB_SHORT = arrayOf("火", "水", "木", "光", "暗", "心")
    }

    var comboMin = 0       // Combo 盾（≥X）
    var firstMin = 0       // 首消 Combo 盾（≥X）
    var firstExact = -1    // 固定首消 X Combo（-1 = 無）
    var antiMask = 0       // 首消不能消的珠種（bit = 珠種）
    var elemMask = 0       // 本回合必須消的珠種（多屬性盾）
    var sticky = false     // 黏手路徑：碰過的格不能再碰
    var firePath = false   // 火焰路徑：不要重踩剛走過的格
    var green = -1         // 綠漩渦：起點
    var red = -1           // 紅漩渦：終點
    val cellFlags = IntArray(MAX_CELLS)

    fun isEmpty(): Boolean = comboMin == 0 && firstMin == 0 && firstExact < 0 && antiMask == 0 && elemMask == 0 &&
        !sticky && !firePath && green < 0 && red < 0 && cellFlags.all { it == 0 }

    fun clear() {
        comboMin = 0; firstMin = 0; firstExact = -1; antiMask = 0; elemMask = 0
        sticky = false; firePath = false; green = -1; red = -1
        cellFlags.fill(0)
    }

    fun count(flag: Int) = cellFlags.count { it and flag != 0 }

    fun toggle(cell: Int, flag: Int) {
        if (cell !in 0 until MAX_CELLS) return
        cellFlags[cell] = cellFlags[cell] xor flag
    }

    fun toInts(): IntArray {
        if (isEmpty()) return IntArray(0)
        val v = IntArray(INTS)
        v[0] = 1
        v[1] = comboMin; v[2] = firstMin; v[3] = firstExact
        v[4] = antiMask; v[5] = elemMask
        v[6] = if (sticky) 1 else 0
        v[7] = if (firePath) 1 else 0
        v[8] = 6; v[9] = 0
        v[10] = green; v[11] = red
        for (i in 0 until MAX_CELLS) {
            v[12 + i] = cellFlags[i]
            v[12 + MAX_CELLS + i] = -1
        }
        return v
    }

    private fun maskText(m: Int) = (0 until 6).filter { m shr it and 1 == 1 }.joinToString("") { ORB_SHORT[it] }

    fun summary(): String {
        if (isEmpty()) return "一般盤（沒有設定機制）"
        val parts = ArrayList<String>()
        if (count(SHIELD) > 0) parts.add("拼圖盾 ${count(SHIELD)} 格")
        if (comboMin > 0) parts.add("Combo≥$comboMin")
        if (firstMin > 0) parts.add("首消≥$firstMin")
        if (firstExact >= 0) parts.add("首消剛好 $firstExact")
        if (elemMask != 0) parts.add("必消 ${maskText(elemMask)}")
        if (antiMask != 0) parts.add("首消禁 ${maskText(antiMask)}")
        if (count(NO_TOUCH) > 0) parts.add("燃燒 ${count(NO_TOUCH)}")
        if (count(FLAME_END) > 0) parts.add("火焰區 ${count(FLAME_END)}")
        if (count(BLACK_HOLE) > 0) parts.add("黑洞 ${count(BLACK_HOLE)}")
        if (count(CRACK) > 0) parts.add("裂痕 ${count(CRACK)}")
        if (green >= 0 || red >= 0) parts.add("漩渦")
        if (sticky) parts.add("黏手路徑")
        if (firePath) parts.add("火焰路徑")
        return parts.joinToString("・")
    }

    fun copyFrom(o: StageRules) {
        comboMin = o.comboMin; firstMin = o.firstMin; firstExact = o.firstExact
        antiMask = o.antiMask; elemMask = o.elemMask; sticky = o.sticky; firePath = o.firePath
        green = o.green; red = o.red
        o.cellFlags.copyInto(cellFlags)
    }

    /** 簡單文字格式（存檔用）：key=value;... */
    fun serialize(): String = listOf(
        "c=$comboMin", "f=$firstMin", "x=$firstExact", "a=$antiMask", "e=$elemMask",
        "s=${if (sticky) 1 else 0}", "p=${if (firePath) 1 else 0}", "g=$green", "r=$red",
        "cells=" + cellFlags.joinToString(","),
    ).joinToString(";")

    fun deserialize(s: String) {
        clear()
        for (kv in s.split(';')) {
            val i = kv.indexOf('=')
            if (i <= 0) continue
            val k = kv.substring(0, i)
            val v = kv.substring(i + 1)
            when (k) {
                "c" -> comboMin = v.toIntOrNull() ?: 0
                "f" -> firstMin = v.toIntOrNull() ?: 0
                "x" -> firstExact = v.toIntOrNull() ?: -1
                "a" -> antiMask = v.toIntOrNull() ?: 0
                "e" -> elemMask = v.toIntOrNull() ?: 0
                "s" -> sticky = v == "1"
                "p" -> firePath = v == "1"
                "g" -> green = v.toIntOrNull() ?: -1
                "r" -> red = v.toIntOrNull() ?: -1
                "cells" -> v.split(',').forEachIndexed { j, t -> if (j < MAX_CELLS) cellFlags[j] = t.toIntOrNull() ?: 0 }
            }
        }
    }
}
